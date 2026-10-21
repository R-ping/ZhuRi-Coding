package com.zhuri.coding.reward.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.reward.entity.UserAssets;
import com.zhuri.coding.reward.entity.WelfareExchangeOrder;
import com.zhuri.coding.reward.entity.WelfareGoods;
import com.zhuri.coding.reward.entity.WelfareStockLog;
import com.zhuri.coding.reward.mapper.UserAssetsMapper;
import com.zhuri.coding.reward.mapper.WelfareExchangeOrderMapper;
import com.zhuri.coding.reward.mapper.WelfareGoodsMapper;
import com.zhuri.coding.reward.mapper.WelfareStockLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 兑换的事务内核：校验 → 扣库存 → 扣矿石 → 落订单与库存流水，全程一个事务。
 *
 * <p><b>为什么单独一个 Bean</b>：幂等外壳（{@code WelfareServiceImpl#exchange}）需要在事务
 * <b>之外</b>做「查已有订单 / 抢短锁 / 撞唯一键后回查并返回原单」。Spring 的 {@code @Transactional}
 * 靠代理生效，同类内自调用不走代理、抛出的唯一键冲突也没法在事务已标记回滚后再查一次——
 * 把事务内核拆到独立 Bean，外层才能安全地捕获 {@code DuplicateKeyException} 并回查。
 * 这与项目里 {@code CheckinTxService} / {@code LotteryTxService} 的分层方式一致。
 */
@Slf4j
@Service
public class WelfareExchangeTxService {

    /** 兑换码随机数：使用加密安全随机源，防止兑换码被预测 */
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    @Autowired
    private WelfareGoodsMapper goodsMapper;
    @Autowired
    private WelfareExchangeOrderMapper exchangeOrderMapper;
    @Autowired
    private WelfareStockLogMapper stockLogMapper;
    @Autowired
    private UserAssetsMapper userAssetsMapper;

    /**
     * 执行一次兑换（必须在事务内）。
     *
     * @param idempotencyKey 客户端幂等号，可为 null；非空时随订单落库，由唯一索引
     *                       {@code uk_user_idem(user_id, idempotency_key)} 兜住并发重复
     */
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult exchangeInTx(Long userId, Map<String, Object> body, String idempotencyKey) {

        String goodsId = (String) body.get("goodsId");
        if (goodsId == null) {
            return ResponseResult.errorResult(400, "缺少商品ID");
        }

        // 1. 校验商品
        WelfareGoods goods = goodsMapper.selectById(goodsId);
        if (goods == null || goods.getStatus() == null || goods.getStatus() != 1) {
            return ResponseResult.errorResult(400, "商品不存在或已下架");
        }

        // 2. 校验库存
        if (goods.getStock() != null && goods.getStock() <= 0) {
            return ResponseResult.errorResult(400, "库存不足，已抢光");
        }

        // 3. 校验限时规则
        if (goods.getTimeLimitStart() != null && goods.getTimeLimitEnd() != null) {
            DayOfWeek dow = LocalDate.now().getDayOfWeek();
            boolean isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            LocalTime now = LocalTime.now();
            LocalTime start = LocalTime.parse(goods.getTimeLimitStart());
            LocalTime end = LocalTime.parse(goods.getTimeLimitEnd());
            if (!(isWeekend && !now.isBefore(start) && !now.isAfter(end))) {
                return ResponseResult.errorResult(400, "当前不在兑换时间窗口内");
            }
        }

        // 4. 校验用户矿石余额
        UserAssets assets = userAssetsMapper.selectById(userId);
        int oreBalance = (assets != null) ? assets.getOreBalance() : 0;
        if (oreBalance < goods.getOrePrice()) {
            return ResponseResult.errorResult(400, "矿石不足，当前余额：" + oreBalance);
        }

        // 5. 校验实物地址
        boolean isVirtual = goods.getIsVirtual() != null && goods.getIsVirtual();
        String receiverName = null;
        String phone = null;
        String address = null;
        if (!isVirtual) {
            receiverName = (String) body.get("receiverName");
            phone = (String) body.get("phone");
            address = (String) body.get("address");
            if (receiverName == null || phone == null || address == null) {
                return ResponseResult.errorResult(400, "实物商品请填写收货地址");
            }
        }
        String remark = (String) body.get("remark");

        try {
            // 6. 数据库条件扣库存（唯一权威闸）
            //
            // 这里刻意不再做 Redis 预扣：库存是强一致数据，预扣必须同时解决三件事——
            // ① key 预热（缺失时 DECR 直接得到 -1，商品一个都兑不出去）；
            // ② 补货后同步（DB 补了库存，Redis 仍是 0，会永久拒单）；
            // ③ 事务回滚补偿（DB 失败要把预扣还回去）。
            // 收益只是省一次主键更新，而兑换本身是低频操作，不值得引入这三套机制。
            // updateStock 是 `stock = stock - 1 WHERE stock > 0` 的条件更新，并发下天然防超卖。
            int updated = goodsMapper.updateStock(goodsId);
            if (updated <= 0) {
                return ResponseResult.errorResult(400, "库存不足，已抢光");
            }

            // ★ 7. 原子扣矿石（条件扣减，余额不足返回0，杜绝并发超扣/负余额）
            int oreUpdated = userAssetsMapper.deductOreBalance(userId, goods.getOrePrice());
            if (oreUpdated != 1) {
                // 并发下余额被先扣光：抛异常触发事务回滚，把上一步扣掉的库存一起还原，
                // 保证「库存 + 矿石余额」在同一事务里要么都变、要么都不变。
                throw new IllegalStateException("矿石余额不足，无法兑换，当前余额：" + oreBalance);
            }
            // 扣减后的剩余余额（供返回给前端展示）
            oreBalance = oreBalance - goods.getOrePrice();
            // 内存中 assets 对象仅用于返回展示，不再回写（避免与原子扣减产生竞态）
            if (assets != null) {
                assets.setOreBalance(oreBalance);
            }

            // 9. 生成订单
            WelfareExchangeOrder order = new WelfareExchangeOrder();
            String exchangeId = "EX" + IdUtil.fastSimpleUUID().substring(0, 16).toUpperCase();
            order.setExchangeId(exchangeId);
            order.setUserId(userId);
            order.setGoodsId(goodsId);
            order.setGoodsName(goods.getName());
            order.setIsVirtual(isVirtual);
            order.setOreCost(goods.getOrePrice());
            order.setReceiverName(receiverName);
            order.setPhone(phone);
            order.setAddress(address);
            order.setRemark(remark);
            order.setStatus(isVirtual ? 2 : 1); // 虚拟直接完成，实物待处理

            // 虚拟商品生成兑换码
            if (isVirtual && goods.getVirtualCodeTemplate() != null) {
                String code = goods.getVirtualCodeTemplate()
                        .replace("{timestamp}", String.valueOf(System.currentTimeMillis()))
                        // SecureRandom：兑换码可预测会带来薅羊毛风险，不能用普通 Random
                        .replace("{rand}", String.valueOf(SECURE_RANDOM.nextInt(999999)));
                order.setVirtualCode(code);
                // 设置过期时间为30天后
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_MONTH, 30);
                order.setAddressExpireAt(cal.getTime());
            } else if (!isVirtual) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_MONTH, 30);
                order.setAddressExpireAt(cal.getTime());
            }

            // 幂等键随订单落库：唯一索引 uk_user_idem 保证同一用户同一 key 只成一单
            order.setIdempotencyKey(idempotencyKey);
            order.setCreatedAt(new Date());
            order.setUpdatedAt(new Date());
            exchangeOrderMapper.insert(order);

            // 10. 记录库存扣减日志
            WelfareStockLog stockLog = new WelfareStockLog();
            stockLog.setGoodsId(goodsId);
            stockLog.setChangeAmount(-1);
            stockLog.setExchangeId(exchangeId);
            stockLog.setCreatedAt(new Date());
            stockLogMapper.insert(stockLog);

            // ★ 注：exchanged_count 已在 updateStock 的 SQL（stock=stock-1, exchanged_count=exchanged_count+1）中原子完成，
            // 此处不再 setExchangedCount(+1) 后 updateById，避免"非原子丢失更新 + 与 updateStock 重复累加"问题。

            // 12. 构建返回
            Map<String, Object> data = new HashMap<>();
            data.put("exchangeId", exchangeId);
            data.put("goodsName", goods.getName());
            data.put("isVirtual", isVirtual);
            data.put("oreCost", goods.getOrePrice());
            data.put("remainingOre", oreBalance);
            data.put("orderStatus", isVirtual ? "completed" : "pending_shipping");
            data.put("virtualCode", isVirtual ? order.getVirtualCode() : null);
            data.put("expireAt", order.getAddressExpireAt() != null ? DateUtil.formatDateTime(order.getAddressExpireAt()) : null);

            return ResponseResult.okResult(data);

        } catch (Exception e) {
            // 库存与矿石都在同一个 DB 事务里，回滚交给 @Transactional，无需再手工补偿
            log.error("兑换失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 幂等命中时按原单回放结果：结构与首次兑换成功完全一致，前端不需要区分"新单"与"回放"。
     */
    public ResponseResult replayResult(Long userId, WelfareExchangeOrder order) {
        UserAssets assets = userAssetsMapper.selectById(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("exchangeId", order.getExchangeId());
        data.put("goodsName", order.getGoodsName());
        data.put("isVirtual", order.getIsVirtual());
        data.put("oreCost", order.getOreCost());
        data.put("remainingOre", assets == null ? 0 : assets.getOreBalance());
        data.put("orderStatus", Boolean.TRUE.equals(order.getIsVirtual()) ? "completed" : "pending_shipping");
        data.put("virtualCode", order.getVirtualCode());
        data.put("expireAt", order.getAddressExpireAt() != null
                ? DateUtil.formatDateTime(order.getAddressExpireAt()) : null);
        return ResponseResult.okResult(data);
    }
}
