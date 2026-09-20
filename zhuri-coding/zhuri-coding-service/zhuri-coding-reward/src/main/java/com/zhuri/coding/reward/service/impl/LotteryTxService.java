package com.zhuri.coding.reward.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.reward.entity.*;
import com.zhuri.coding.reward.mapper.*;
import com.zhuri.coding.reward.service.VirtualAssetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 抽奖<b>事务体内核</b>（P0-6 修复：无锁/无幂等/空池越界）。
 *
 * <p>与签到（{@link CheckinTxService}）同款拆分：外层 {@link LotteryServiceImpl}
 * 只做「加锁 → 委托 → finally 解锁」，同用户抽奖完全串行化后：
 * <ul>
 *   <li>幸运值 read-modify-write（{@code updateLuckyValue} 直写绝对值）不再并发覆盖；</li>
 *   <li>免费次数 / 每日次数在此基础上再以<b>条件原子 SQL</b> 兜底（纵深防御，即使锁失效也不重复发放）；</li>
 *   <li>空奖池不再 {@code pool.get(0)} 越界或绕过解锁规则，统一走矿石兜底或显式回滚。</li>
 * </ul>
 */
@Service
@Slf4j
public class LotteryTxService {

    @Autowired
    private LotteryPrizePoolMapper prizePoolMapper;
    @Autowired
    private LotteryDrawRecordMapper drawRecordMapper;
    @Autowired
    private LotteryPhysicalOrderMapper physicalOrderMapper;
    @Autowired
    private LotteryDailyStateMapper dailyStateMapper;
    @Autowired
    private LotteryBroadcastMessageMapper broadcastMapper;
    @Autowired
    private UserAssetsMapper userAssetsMapper;
    @Autowired
    private VirtualAssetService virtualAssetService;

    /** 幸运值保底阈值 */
    private static final int LUCKY_THRESHOLD = 6000;
    /** 每次未中奖累积的幸运值 */
    private static final int LUCKY_GAIN_PER_DRAW = 10;

    // ========================================================================
    // 抽奖（事务体）
    // ========================================================================

    @Transactional(rollbackFor = Exception.class)
    public ResponseResult drawTx(Long userId, String type, Boolean useFree) {
        // 默认为单抽
        if (type == null) type = "single";
        boolean isTen = "ten".equals(type);
        int drawCount = isTen ? 10 : 1;
        int costOre = isTen ? 2000 : 200;

        // 1. 获取今日抽奖状态
        String todayStr = DateUtil.today();
        Date todayDate = java.sql.Date.valueOf(todayStr);
        LotteryDailyState daily = dailyStateMapper.selectOne(
                new LambdaQueryWrapper<LotteryDailyState>()
                        .eq(LotteryDailyState::getUserId, userId)
                        .eq(LotteryDailyState::getStatDate, todayDate)
        );

        int todayDrawCount = (daily != null && daily.getDrawCount() != null) ? daily.getDrawCount() : 0;

        // 2. 校验免费次数（是否可用仅作前置提示；真正的占用在事务末尾用条件原子 SQL 兜底，
        //    并发下即使前置检查通过，markFreeUsed 也只允许一个请求成功）
        boolean useFreeDraw = useFree != null && useFree && !isTen;
        if (useFreeDraw && daily != null && daily.getFreeUsed() != null && daily.getFreeUsed()) {
            return ResponseResult.errorResult(400, "今日免费次数已使用");
        }
        if (useFreeDraw) {
            costOre = 0;
        }

        // 3. 校验矿石余额
        UserAssets assets = userAssetsMapper.selectById(userId);
        int oreBalance = (assets != null) ? assets.getOreBalance() : 0;
        if (!useFreeDraw && oreBalance < costOre) {
            return ResponseResult.errorResult(400, "矿石不足，当前余额：" + oreBalance);
        }
        if (isTen && oreBalance < 2000) {
            return ResponseResult.errorResult(400, "矿石不足2000，无法十连抽，当前余额：" + oreBalance);
        }

        // 4. 获取有效奖池（P0-6：空池前置校验，花钱之前拒绝，绝不越界）
        List<LotteryPrizePool> effectivePool = prizePoolMapper.selectList(
                new LambdaQueryWrapper<LotteryPrizePool>()
                        .eq(LotteryPrizePool::getStatus, 1)
                        .orderByAsc(LotteryPrizePool::getSortOrder)
        );
        if (effectivePool == null || effectivePool.isEmpty()) {
            log.warn("奖池为空，拒绝抽奖: userId={}", userId);
            return ResponseResult.errorResult(400, "奖池暂未开放，请稍后再试");
        }

        // 5. 执行抽奖
        String batchId = IdUtil.fastSimpleUUID();
        List<Map<String, Object>> results = new ArrayList<>();
        int totalCost = 0;
        int currentLucky = (assets != null && assets.getLuckyValue() != null) ? assets.getLuckyValue() : 0;
        // 本轮抽中矿石奖励的合计（仅用于返回等值展示；实际累加通过原子 SQL 落库，防并发丢失）
        int totalOreWon = 0;

        for (int i = 0; i < drawCount; i++) {
            int luckyBefore = currentLucky;
            // 判断是否触发保底
            int gainedLucky = LUCKY_GAIN_PER_DRAW;
            boolean isGuaranteed = (currentLucky + gainedLucky) >= LUCKY_THRESHOLD;
            int currentDrawIdx = i; // 用于lambda的最终变量

            LotteryPrizePool selectedPrize;
            if (isGuaranteed) {
                // 保底：从实物奖品中抽取
                int unlockThreshold = todayDrawCount + currentDrawIdx;
                List<LotteryPrizePool> physicalPrizes = effectivePool.stream()
                        .filter(p -> p.getIsPhysical() != null && p.getIsPhysical()
                                && (p.getUnlockRequiredDraws() == null || p.getUnlockRequiredDraws() <= unlockThreshold))
                        .collect(Collectors.toList());
                if (!physicalPrizes.isEmpty()) {
                    selectedPrize = physicalPrizes.get(0);
                } else {
                    // 降级为最高价值矿石（P0-6：无矿石奖品时显式回滚，绝不 get(0) 越界或发未解锁奖品）
                    selectedPrize = effectivePool.stream()
                            .filter(p -> p.getType() == 1)
                            .max(Comparator.comparing(LotteryPrizePool::getMaxOre))
                            .orElse(null);
                }
                if (selectedPrize == null) {
                    throw new IllegalStateException("奖池配置异常：保底无实物且无矿石奖品，userId=" + userId);
                }
                int overflow = (currentLucky + gainedLucky) - LUCKY_THRESHOLD;
                currentLucky = Math.max(0, overflow);
            } else {
                // 普通抽取：按概率权重（randomDraw 内部空有效集时兜底矿石，全池无矿石则显式回滚）
                selectedPrize = randomDraw(effectivePool, todayDrawCount + i);
                if (selectedPrize == null) {
                    throw new IllegalStateException("奖池配置异常：有效奖池为空且无矿石兜底，userId=" + userId);
                }
                if (selectedPrize.getIsPhysical() != null && selectedPrize.getIsPhysical()) {
                    currentLucky = 0;
                } else {
                    currentLucky += gainedLucky;
                }
            }

            // ★ 实物奖品库存占用：发放前原子占用，售罄/并发抢空则降级为矿石兜底，防实物超发
            if (selectedPrize.getType() != null && selectedPrize.getType() == 3) {
                selectedPrize = occupyOrDowngrade(selectedPrize);
            }

            // 构建结果
            Map<String, Object> result = new HashMap<>();
            result.put("prizeId", selectedPrize.getId());
            result.put("prizeName", selectedPrize.getName());
            result.put("prizeType", selectedPrize.getType() == 1 ? "ore" : selectedPrize.getType() == 2 ? "virtual" : "physical");

            Long physicalOrderId = null;
            if (selectedPrize.getType() == 1) {
                // 矿石奖励：随机范围
                int oreAmount = selectedPrize.getMinOre()
                        + ThreadLocalRandom.current().nextInt(selectedPrize.getMaxOre() - selectedPrize.getMinOre() + 1);
                result.put("oreAmount", oreAmount);
                // 直接增加矿石（原子累加，避免读改写覆盖丢失）
                if (assets == null) {
                    assets = new UserAssets();
                    assets.setUserId(userId);
                    assets.setOreBalance(0);
                    assets.setFrozenOre(0);
                    assets.setLuckyValue(0);
                    assets.setCreatedAt(new Date());
                    assets.setUpdatedAt(new Date());
                    userAssetsMapper.insert(assets);
                }
                userAssetsMapper.addOreBalance(userId, oreAmount);
                totalOreWon += oreAmount;
            } else if (selectedPrize.getType() == 3) {
                // 实物：创建订单
                LotteryPhysicalOrder order = new LotteryPhysicalOrder();
                order.setUserId(userId);
                order.setPrizeId(selectedPrize.getId());
                order.setPrizeName(selectedPrize.getName());
                order.setStatus(1); // 待填地址
                Calendar expireCal = Calendar.getInstance();
                expireCal.add(Calendar.DAY_OF_MONTH, 30);
                order.setExpireAt(expireCal.getTime());
                order.setCreatedAt(new Date());
                order.setUpdatedAt(new Date());
                physicalOrderMapper.insert(order);
                physicalOrderId = order.getId();
                result.put("physicalOrderId", physicalOrderId);

                // 添加中奖播报
                LotteryBroadcastMessage msg = new LotteryBroadcastMessage();
                msg.setUserId(userId);
                msg.setUserNickname("用户" + userId);
                msg.setPrizeName(selectedPrize.getName());
                msg.setPrizeType(3);
                msg.setCreatedAt(new Date());
                broadcastMapper.insert(msg);
            }

            result.put("luckyValueGained", isGuaranteed ? 0 : gainedLucky);
            result.put("isSpecialUnlock", isGuaranteed);
            results.add(result);

            // 虚拟道具入账：type==2 时累加持有数量，供"我的道具"展示 + 课程下单核销
            if (selectedPrize.getType() == 2 && selectedPrize.getVirtualItemCode() != null) {
                virtualAssetService.credit(userId, selectedPrize.getVirtualItemCode(),
                        selectedPrize.getName(), 1);
            }

            // 记录抽奖记录
            LotteryDrawRecord record = new LotteryDrawRecord();
            record.setDrawBatchId(batchId);
            record.setUserId(userId);
            record.setPrizeId(selectedPrize.getId());
            record.setPrizeName(selectedPrize.getName());
            record.setPrizeType(selectedPrize.getType());
            record.setOreAmount(selectedPrize.getType() == 1 ? (Integer) result.get("oreAmount") : 0);
            record.setVirtualItemCode(selectedPrize.getVirtualItemCode());
            record.setPhysicalOrderId(physicalOrderId);
            record.setLuckyValueBefore(luckyBefore);
            record.setLuckyValueAfter(currentLucky);
            record.setTodayDrawCountAtTime(todayDrawCount + i);
            record.setCostOre(useFreeDraw ? 0 : costOre / drawCount);
            record.setIsFree(useFreeDraw);
            record.setCreatedAt(new Date());
            drawRecordMapper.insert(record);
        }

        // 6. 扣矿石成本（原子扣减，防止并发下被同时抽成负余额/覆盖丢失）
        if (!useFreeDraw) {
            if (userAssetsMapper.deductOreBalance(userId, costOre) != 1) {
                // 进入时余额足够，但并发下被先抽走：回滚本轮已占用的库存/订单/记录
                log.warn("抽奖扣矿石失败(余额不足或并发抢先)，userId={}, cost={}", userId, costOre);
                throw new IllegalStateException("矿石不足，无法支付本次抽奖");
            }
        }
        if (assets == null) {
            assets = new UserAssets();
            assets.setUserId(userId);
            assets.setOreBalance(0);
            assets.setFrozenOre(0);
            assets.setLuckyValue(0);
            assets.setCreatedAt(new Date());
            assets.setUpdatedAt(new Date());
            userAssetsMapper.insert(assets);
        }
        // 更新资产：幸运值走条件更新（原子 SQL 直写）；外层同用户锁串行化后读改写安全
        userAssetsMapper.updateLuckyValue(userId, currentLucky);

        // 7. 更新每日抽奖状态（P0-6：全部条件原子 SQL，替代原 updateById 整行读改写）
        if (daily == null) {
            // 首次：直接插入当日记录；并发首撞唯一键 uniq_user_date 则降级为更新路径
            daily = new LotteryDailyState();
            daily.setUserId(userId);
            daily.setStatDate(todayDate);
            daily.setDrawCount(drawCount);
            daily.setFreeUsed(useFreeDraw);
            try {
                dailyStateMapper.insert(daily);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.info("每日抽奖状态并发首建冲突，降级为原子更新: userId={}", userId);
                applyDailyUpdateAtomically(userId, todayDate, drawCount, useFreeDraw);
            }
        } else {
            applyDailyUpdateAtomically(userId, todayDate, drawCount, useFreeDraw);
        }

        // 8. 构建返回
        Map<String, Object> data = new HashMap<>();
        data.put("drawId", batchId);
        data.put("results", results);
        data.put("totalOreCost", useFreeDraw ? 0 : costOre);
        data.put("remainingOre", oreBalance - (useFreeDraw ? 0 : costOre) + totalOreWon);
        data.put("newLuckyValue", currentLucky);
        data.put("todayDrawCountUpdated", todayDrawCount + drawCount);

        return ResponseResult.okResult(data);
    }

    /**
     * 当日状态原子更新：次数用累加 SQL；免费次数用条件占用 SQL（返回 0 = 已被并发占用）。
     * <p>P0-6：免费次数占用失败时<b>不回滚整个事务</b>——矿石成本与奖品发放已经发生，
     * 本次按"付费抽"语义继续，仅告警（同用户锁串行下该分支理论上不可达，属纵深防御）。
     */
    private void applyDailyUpdateAtomically(Long userId, Date todayDate, int drawCount, boolean useFreeDraw) {
        dailyStateMapper.incrDrawCount(userId, todayDate, drawCount);
        if (useFreeDraw) {
            int occupied = dailyStateMapper.markFreeUsed(userId, todayDate);
            if (occupied != 1) {
                // 纵深防御分支：锁串行下不可达；真发生说明锁被绕过，按付费抽继续并告警
                log.error("[LOTTERY] 免费次数条件占用失败(疑似并发穿透)，按付费抽继续: userId={}", userId);
            }
        }
    }

    /**
     * 按概率权重抽奖。P0-6 修复：
     * <ul>
     *   <li>有效集为空时<b>不再</b> {@code pool.get(0)}（既可能越界，也会绕过解锁规则发未解锁奖品）；</li>
     *   <li>兜底优先取矿石奖品；全池无矿石返回 null，由调用方显式回滚事务。</li>
     * </ul>
     *
     * @return 选中的奖品；有效集为空且无矿石兜底时返回 null
     */
    LotteryPrizePool randomDraw(List<LotteryPrizePool> pool, int todayDrawCount) {
        // 构建有效奖池（排除未解锁的）
        List<LotteryPrizePool> effective = pool.stream()
                .filter(p -> p.getUnlockRequiredDraws() == null || p.getUnlockRequiredDraws() <= todayDrawCount)
                .collect(Collectors.toList());

        if (effective.isEmpty()) {
            log.warn("抽奖有效奖池为空（全部未解锁），兜底矿石奖品: todayDrawCount={}", todayDrawCount);
            return fallbackOrePrize(pool);
        }

        double rand = Math.random();
        double cumulative = 0.0;
        for (LotteryPrizePool p : effective) {
            cumulative += p.getProbability().doubleValue();
            if (rand <= cumulative) {
                return p;
            }
        }
        // 浮点累计误差兜底：优先返回第一个矿石奖品
        return effective.stream().filter(p -> p.getType() == 1).findFirst()
                .orElseGet(() -> fallbackOrePrize(effective));
    }

    /** 矿石兜底：全池无矿石奖品时返回 null（调用方抛异常回滚事务，铁律 4：付费抽奖绝不能无产出） */
    private LotteryPrizePool fallbackOrePrize(List<LotteryPrizePool> pool) {
        return pool.stream().filter(p -> p.getType() == 1).findFirst().orElse(null);
    }

    /**
     * 实物奖品发放前原子占用库存。
     * <ul>
     *   <li>不限量（total_stock 为 null 或 &lt;0）：直接返回原奖品，不扣减；</li>
     *   <li>限量（&gt;0）：原子扣减一件，成功返回原奖品；失败（已售罄/并发抢空）降级为矿石兜底。</li>
     * </ul>
     * 返回值保证为可正常发放的奖品类型（实物或矿石），杜绝"中奖实物却发不出"导致的超发/资损。
     *
     * @param physicalPrize 已抽中的实物奖品
     * @return 实际发放的奖品（占用成功则原实物，否则降级为矿石奖品）
     */
    private LotteryPrizePool occupyOrDowngrade(LotteryPrizePool physicalPrize) {
        Integer stock = physicalPrize.getTotalStock();
        // 不限量或未配置（兼容历史数据）：直接发放，不占用库存
        boolean unlimited = stock == null || stock < 0;
        if (!unlimited && prizePoolMapper.deductStock(physicalPrize.getId()) != 1) {
            // 库存 0 或并发下被抢先抽完：降级为矿石兜底
            LotteryPrizePool ore = new LotteryPrizePool();
            ore.setType(1);
            ore.setId(physicalPrize.getId());
            ore.setName("矿石");
            ore.setMinOre(1);
            int max = physicalPrize.getMaxOre() != null && physicalPrize.getMaxOre() > 0
                    ? physicalPrize.getMaxOre() : 50;
            ore.setMaxOre(max);
            log.info("实物奖品库存被抽完，降级为矿石兜底: prizeId={}, name={}",
                    physicalPrize.getId(), physicalPrize.getName());
            return ore;
        }
        return physicalPrize;
    }

    // ========================================================================
    // 实物收货地址提交（事务体，P0-6 顺手原子化防并发双提交）
    // ========================================================================

    @Transactional(rollbackFor = Exception.class)
    public ResponseResult claimPhysicalTx(Long userId, Map<String, Object> body) {
        Object orderIdRaw = body.get("orderId");
        if (orderIdRaw == null) {
            return ResponseResult.errorResult(400, "缺少订单ID");
        }
        // 兼容前端传数字或字符串两种形式
        Long orderId = Long.parseLong(String.valueOf(orderIdRaw));
        String receiverName = (String) body.get("receiverName");
        String phone = (String) body.get("phone");
        String address = (String) body.get("address");

        if (receiverName == null || address == null || phone == null) {
            return ResponseResult.errorResult(400, "收货信息不完整");
        }
        // 收货信息长度/格式校验，避免脏数据与异常信息入库
        String name = receiverName.trim();
        String addr = address.trim();
        String ph = phone.trim();
        if (name.isEmpty() || name.length() > 20) {
            return ResponseResult.errorResult(400, "收货人姓名长度不合法（1-20位）");
        }
        if (!ph.matches("1[3-9]\\d{9}")) {
            return ResponseResult.errorResult(400, "手机号格式不正确");
        }
        if (addr.isEmpty() || addr.length() > 120) {
            return ResponseResult.errorResult(400, "收货地址长度不合法（1-120位）");
        }

        LotteryPhysicalOrder order = physicalOrderMapper.selectById(orderId);
        if (order == null) {
            return ResponseResult.errorResult(400, "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return ResponseResult.errorResult(400, "无权操作该订单");
        }

        // P0-6：条件更新原子抢占 status=1 → 2，并发双提交只有一次成功（原 check-then-set 会双双通过）
        int updated = physicalOrderMapper.update(null, new LambdaUpdateWrapper<LotteryPhysicalOrder>()
                .eq(LotteryPhysicalOrder::getId, orderId)
                .eq(LotteryPhysicalOrder::getStatus, 1)
                .set(LotteryPhysicalOrder::getReceiverName, receiverName)
                .set(LotteryPhysicalOrder::getPhone, phone)
                .set(LotteryPhysicalOrder::getAddress, address)
                .set(LotteryPhysicalOrder::getStatus, 2) // 待发货
                .set(LotteryPhysicalOrder::getUpdatedAt, new Date()));
        if (updated != 1) {
            return ResponseResult.errorResult(400, "订单状态不正确或已提交过地址");
        }

        return ResponseResult.okResult("收货地址已提交");
    }
}
