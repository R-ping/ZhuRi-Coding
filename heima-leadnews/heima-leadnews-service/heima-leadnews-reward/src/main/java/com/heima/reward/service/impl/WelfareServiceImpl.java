package com.heima.reward.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.entity.*;
import com.heima.reward.mapper.*;
import com.heima.reward.service.WelfareService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WelfareServiceImpl implements WelfareService {

    @Autowired
    private WelfareGoodsMapper goodsMapper;
    @Autowired
    private WelfareExchangeOrderMapper exchangeOrderMapper;
    @Autowired
    private WelfareStockLogMapper stockLogMapper;
    @Autowired
    private UserAssetsMapper userAssetsMapper;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public ResponseResult getGoodsList(Integer type, Integer page, Integer size) {
        if (page == null) page = 1;
        if (size == null) size = 20;
        if (type == null) type = 1;

        LambdaQueryWrapper<WelfareGoods> wrapper = new LambdaQueryWrapper<WelfareGoods>()
                .eq(WelfareGoods::getCategory, type)
                .eq(WelfareGoods::getStatus, 1)
                .orderByAsc(WelfareGoods::getSortOrder)
                .orderByDesc(WelfareGoods::getCreatedAt);

        Page<WelfareGoods> p = new Page<>(page, size);
        List<WelfareGoods> goodsList = goodsMapper.selectPage(p, wrapper).getRecords();

        List<Map<String, Object>> list = goodsList.stream().map(g -> {
            Map<String, Object> m = new HashMap<>();
            m.put("goodsId", g.getId());
            m.put("name", g.getName());
            m.put("description", g.getDescription());
            m.put("imageUrl", g.getImageUrl());
            m.put("type", g.getType());
            m.put("orePrice", g.getOrePrice());
            m.put("originalPrice", g.getOriginalPrice());
            m.put("discountTag", g.getDiscountTag());
            m.put("stock", g.getStock());
            m.put("exchangedCount", g.getExchangedCount());
            m.put("status", g.getStock() != null && g.getStock() <= 0 ? 0 : 1);
            m.put("isTimeLimited", g.getTimeLimitStart() != null);
            m.put("timeLimitDesc", g.getTimeLimitDesc());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("size", size);

        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getGoodsDetail(String goodsId) {
        WelfareGoods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            return ResponseResult.errorResult(400, "商品不存在");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("goodsId", goods.getId());
        data.put("name", goods.getName());
        data.put("description", goods.getDescription());
        data.put("imageUrl", goods.getImageUrl());
        data.put("type", goods.getType());
        data.put("orePrice", goods.getOrePrice());
        data.put("originalPrice", goods.getOriginalPrice());
        data.put("discountTag", goods.getDiscountTag());
        data.put("stock", goods.getStock());
        data.put("exchangedCount", goods.getExchangedCount());
        data.put("isVirtual", goods.getIsVirtual());
        data.put("status", goods.getStock() != null && goods.getStock() <= 0 ? 0 : 1);
        data.put("isTimeLimited", goods.getTimeLimitStart() != null);
        data.put("timeLimitDesc", goods.getTimeLimitDesc());
        data.put("exchangeNotice", "1. 虚拟商品兑换后即时发放，不可退款\n2. 实物商品将在15个工作日内发货\n3. 请确保收货地址正确，30天未填写地址将自动作废");

        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult exchange(Long userId, Map<String, Object> body) {
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

        // 6. Redis预扣库存
        String stockKey = "welfare:stock:" + goodsId;
        Long remainStock = redisTemplate.opsForValue().decrement(stockKey);
        if (remainStock != null && remainStock < 0) {
            redisTemplate.opsForValue().increment(stockKey); // 回滚
            return ResponseResult.errorResult(400, "库存不足，已抢光");
        }

        try {
            // 7. 数据库乐观锁扣库存
            int updated = goodsMapper.updateStock(goodsId);
            if (updated <= 0) {
                redisTemplate.opsForValue().increment(stockKey);
                return ResponseResult.errorResult(400, "库存不足，已抢光");
            }

            // ★ 8. 原子扣矿石（条件扣减，余额不足返回0，杜绝并发超扣/负余额）
            int oreUpdated = userAssetsMapper.deductOreBalance(userId, goods.getOrePrice());
            if (oreUpdated != 1) {
                // 并发下余额被先扣光：抛异常触发事务回滚（DB updateStock 回滚），catch 中再回滚 Redis 预扣，
                // 保证 库存(DB/Redis) 与 矿石余额 三者回滚一致。
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
                        .replace("{rand}", String.valueOf(new Random().nextInt(999999)));
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
            // 回滚Redis预扣
            redisTemplate.opsForValue().increment(stockKey);
            log.error("兑换失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public ResponseResult getMyExchanges(Long userId, Integer page, Integer size, String status) {
        if (page == null) page = 1;
        if (size == null) size = 20;

        LambdaQueryWrapper<WelfareExchangeOrder> wrapper = new LambdaQueryWrapper<WelfareExchangeOrder>()
                .eq(WelfareExchangeOrder::getUserId, userId)
                .orderByDesc(WelfareExchangeOrder::getCreatedAt);

        if (status != null && !"all".equals(status)) {
            int statusVal = "pending".equals(status) ? 1 : "completed".equals(status) ? 2 : 3;
            wrapper.eq(WelfareExchangeOrder::getStatus, statusVal);
        }

        Page<WelfareExchangeOrder> p = new Page<>(page, size);
        List<WelfareExchangeOrder> orders = exchangeOrderMapper.selectPage(p, wrapper).getRecords();

        List<Map<String, Object>> list = orders.stream().map(o -> {
            Map<String, Object> m = new HashMap<>();
            m.put("exchangeId", o.getExchangeId());
            m.put("goodsName", o.getGoodsName());
            m.put("isVirtual", o.getIsVirtual());
            m.put("oreCost", o.getOreCost());
            m.put("status", o.getStatus());
            m.put("virtualCode", o.getVirtualCode());
            m.put("createdAt", DateUtil.formatDateTime(o.getCreatedAt()));

            if (o.getIsVirtual() != null && o.getIsVirtual()) {
                m.put("statusText", "已完成");
            } else {
                String statusText;
                switch (o.getStatus()) {
                    case 1: statusText = "待发货"; break;
                    case 2: statusText = "已发货"; break;
                    case 3: statusText = "已过期"; break;
                    default: statusText = "未知";
                }
                m.put("statusText", statusText);
            }
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("size", size);

        return ResponseResult.okResult(data);
    }
}
