package com.zhuri.coding.reward.service.impl;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.reward.entity.*;
import com.zhuri.coding.reward.mapper.*;
import com.zhuri.coding.reward.service.LotteryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 抽奖服务（外层：分布式锁编排 + 只读查询）。
 *
 * <p><b>P0-6 修复</b>（与签到 P1-1 同款拆分）：原 `draw()` 225 行在单个
 * `@Transactional` 方法里完成全部业务且无锁——免费次数 check-then-set 可并发重复使用（资损）、
 * 每日次数/幸运值读改写丢失更新、空奖池 `get(0)` 越界。
 * 修复后本类只做「加锁 → 委托 {@link LotteryTxService}（事务体内核）→ finally 解锁」，
 * 同用户抽奖完全串行化；事务体内再做条件原子 SQL 兜底（纵深防御）。
 *
 * <p>锁 TTL 10s：十连抽含 10 轮循环 + 多次 insert，比签到（3s）放宽；进程崩溃由 TTL 兜底。
 */
@Service
@Slf4j
public class LotteryServiceImpl implements LotteryService {

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
    private StringRedisTemplate redisTemplate;
    @Autowired
    private LotteryTxService lotteryTxService;

    private static final String LOCK_KEY_PREFIX = "lottery:lock:";
    private static final long LOCK_EXPIRE_SECONDS = 10;

    // ========================================================================
    // 锁编排工具
    // ========================================================================

    private boolean tryLock(Long userId) {
        String key = LOCK_KEY_PREFIX + userId;
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(LOCK_EXPIRE_SECONDS));
        return Boolean.TRUE.equals(locked);
    }

    private void unlock(Long userId) {
        redisTemplate.delete(LOCK_KEY_PREFIX + userId);
    }

    // ========================================================================
    // 只读查询
    // ========================================================================

    @Override
    public ResponseResult getDashboard(Long userId) {
        UserAssets assets = userAssetsMapper.selectById(userId);
        int oreBalance = (assets != null) ? assets.getOreBalance() : 0;
        int luckyValue = (assets != null) ? assets.getLuckyValue() : 0;

        // 获取今日抽奖状态
        String todayStr = DateUtil.today();
        LotteryDailyState daily = dailyStateMapper.selectOne(
                new LambdaQueryWrapper<LotteryDailyState>()
                        .eq(LotteryDailyState::getUserId, userId)
                        .eq(LotteryDailyState::getStatDate, java.sql.Date.valueOf(todayStr))
        );
        int todayDrawCount = (daily != null && daily.getDrawCount() != null) ? daily.getDrawCount() : 0;
        boolean freeUsed = daily != null && daily.getFreeUsed() != null && daily.getFreeUsed();
        boolean freeAvailable = !freeUsed;

        // 获取奖池
        List<LotteryPrizePool> allPrizes = prizePoolMapper.selectList(
                new LambdaQueryWrapper<LotteryPrizePool>().eq(LotteryPrizePool::getStatus, 1)
                        .orderByAsc(LotteryPrizePool::getSortOrder)
        );

        List<Map<String, Object>> prizeList = new ArrayList<>();
        for (LotteryPrizePool p : allPrizes) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", p.getId());
            item.put("name", p.getName());
            item.put("icon", p.getIconUrl());
            item.put("type", p.getType() == 1 ? "ore" : p.getType() == 2 ? "virtual" : "physical");
            item.put("minAmount", p.getMinOre());
            item.put("maxAmount", p.getMaxOre());
            boolean isLocked = p.getUnlockRequiredDraws() != null && p.getUnlockRequiredDraws() > 0
                    && todayDrawCount < p.getUnlockRequiredDraws();
            item.put("isLocked", isLocked);
            if (isLocked) {
                item.put("lockHint", "再抽" + (p.getUnlockRequiredDraws() - todayDrawCount) + "次解锁");
            }
            item.put("unlockRequired", p.getUnlockRequiredDraws() != null ? p.getUnlockRequiredDraws() : 0);
            // 实物奖品剩余库存（-1=不限量），供前端展示"限量X件"
            item.put("stock", p.getTotalStock() != null ? p.getTotalStock() : -1);
            prizeList.add(item);
        }

        // 获取中奖播报
        List<LotteryBroadcastMessage> broadcasts = broadcastMapper.selectList(
                new LambdaQueryWrapper<LotteryBroadcastMessage>()
                        .orderByDesc(LotteryBroadcastMessage::getCreatedAt)
                        .last("LIMIT 5")
        );
        List<Map<String, Object>> broadcastList = broadcasts.stream().map(b -> {
            Map<String, Object> m = new HashMap<>();
            m.put("user", b.getUserNickname() != null ? b.getUserNickname() : "用户" + b.getUserId());
            m.put("prize", b.getPrizeName());
            m.put("time", DateUtil.formatDateTime(b.getCreatedAt()));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("oreBalance", oreBalance);
        data.put("freeDrawAvailable", freeAvailable);
        data.put("freeDrawUsed", freeUsed);
        data.put("todayDrawCount", todayDrawCount);
        data.put("luckyValue", luckyValue);
        data.put("luckyThreshold", 6000);
        data.put("prizePool", prizeList);
        data.put("broadcastMessages", broadcastList);

        return ResponseResult.okResult(data);
    }

    // ========================================================================
    // 抽奖（锁编排：加锁 → 事务体内核 → 解锁）
    // ========================================================================

    @Override
    public ResponseResult draw(Long userId, String type, Boolean useFree) {
        if (!tryLock(userId)) {
            return ResponseResult.errorResult(429, "操作过于频繁，请稍后再试");
        }
        try {
            // 事务在内层 LotteryTxService 提交/回滚后，才会执行到本 finally 的 unlock
            return lotteryTxService.drawTx(userId, type, useFree);
        } finally {
            unlock(userId);
        }
    }

    // ========================================================================
    // 实物收货地址提交（锁编排，防并发双提交）
    // ========================================================================

    @Override
    public ResponseResult claimPhysical(Long userId, Map<String, Object> body) {
        if (!tryLock(userId)) {
            return ResponseResult.errorResult(429, "操作过于频繁，请稍后再试");
        }
        try {
            return lotteryTxService.claimPhysicalTx(userId, body);
        } finally {
            unlock(userId);
        }
    }

    // ========================================================================
    // 只读查询（续）
    // ========================================================================

    @Override
    public ResponseResult getMyPrizes(Long userId, Integer page, Integer size, String type) {
        if (page == null) page = 1;
        if (size == null) size = 20;

        LambdaQueryWrapper<LotteryDrawRecord> wrapper = new LambdaQueryWrapper<LotteryDrawRecord>()
                .eq(LotteryDrawRecord::getUserId, userId)
                .orderByDesc(LotteryDrawRecord::getCreatedAt);

        if (type != null && !"all".equals(type)) {
            int prizeType = "ore".equals(type) ? 1 : "virtual".equals(type) ? 2 : 3;
            wrapper.eq(LotteryDrawRecord::getPrizeType, prizeType);
        }

        Page<LotteryDrawRecord> p = new Page<>(page, size);
        List<LotteryDrawRecord> records = drawRecordMapper.selectPage(p, wrapper).getRecords();

        // 奖品池索引，用于补充奖品图标等展示信息
        Map<String, LotteryPrizePool> prizeIndex = prizePoolMapper.selectList(null).stream()
                .collect(Collectors.toMap(LotteryPrizePool::getId, x -> x, (a, b) -> a));

        List<Map<String, Object>> list = records.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("drawId", r.getId());
            m.put("prizeId", r.getPrizeId());
            m.put("prizeName", r.getPrizeName());
            m.put("prizeType", r.getPrizeType());
            m.put("oreAmount", r.getOreAmount());
            m.put("virtualItemCode", r.getVirtualItemCode());
            m.put("createdAt", DateUtil.formatDateTime(r.getCreatedAt()));

            // 补充奖品图标
            LotteryPrizePool prize = r.getPrizeId() != null ? prizeIndex.get(r.getPrizeId()) : null;
            m.put("iconUrl", prize != null && prize.getIconUrl() != null ? prize.getIconUrl() : "");

            if (r.getPhysicalOrderId() != null) {
                LotteryPhysicalOrder po = physicalOrderMapper.selectById(r.getPhysicalOrderId());
                if (po != null) {
                    String statusText;
                    switch (po.getStatus()) {
                        case 1: statusText = "待填地址"; break;
                        case 2: statusText = "备货中"; break;
                        case 3: statusText = "运送中"; break;
                        case 4: statusText = "已收货"; break;
                        case 5: statusText = "已过期"; break;
                        default: statusText = "未知";
                    }
                    m.put("orderStatus", statusText);
                    m.put("orderStatusNum", po.getStatus());
                    m.put("orderId", po.getId());
                }
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

    @Override
    public ResponseResult getPhysicalOrderDetail(Long userId, Long orderId) {
        LotteryPhysicalOrder order = physicalOrderMapper.selectById(orderId);
        if (order == null) {
            return ResponseResult.errorResult(400, "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return ResponseResult.errorResult(400, "无权操作该订单");
        }

        // 状态文案，与「我的收获」列表保持一致
        String statusText;
        switch (order.getStatus()) {
            case 1: statusText = "待填地址"; break;
            case 2: statusText = "备货中"; break;
            case 3: statusText = "运送中"; break;
            case 4: statusText = "已收货"; break;
            case 5: statusText = "已过期"; break;
            default: statusText = "未知";
        }

        // 补充奖品图标
        LotteryPrizePool prize = prizePoolMapper.selectById(order.getPrizeId());

        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("prizeId", order.getPrizeId());
        data.put("prizeName", order.getPrizeName());
        data.put("iconUrl", prize != null && prize.getIconUrl() != null ? prize.getIconUrl() : "");
        data.put("status", order.getStatus());
        data.put("statusText", statusText);
        data.put("receiverName", order.getReceiverName() != null ? order.getReceiverName() : "");
        data.put("phone", order.getPhone() != null ? order.getPhone() : "");
        data.put("address", order.getAddress() != null ? order.getAddress() : "");
        data.put("expressNo", order.getExpressNo() != null ? order.getExpressNo() : "");
        data.put("createdAt", order.getCreatedAt() != null ? DateUtil.formatDateTime(order.getCreatedAt()) : "");

        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getBroadcast() {
        List<LotteryBroadcastMessage> broadcasts = broadcastMapper.selectList(
                new LambdaQueryWrapper<LotteryBroadcastMessage>()
                        .orderByDesc(LotteryBroadcastMessage::getCreatedAt)
                        .last("LIMIT 20")
        );
        List<Map<String, Object>> list = broadcasts.stream().map(b -> {
            Map<String, Object> m = new HashMap<>();
            m.put("user", b.getUserNickname() != null ? b.getUserNickname() : "用户" + b.getUserId());
            m.put("prize", b.getPrizeName());
            m.put("time", DateUtil.formatDateTime(b.getCreatedAt()));
            return m;
        }).collect(Collectors.toList());

        return ResponseResult.okResult(list);
    }
}
