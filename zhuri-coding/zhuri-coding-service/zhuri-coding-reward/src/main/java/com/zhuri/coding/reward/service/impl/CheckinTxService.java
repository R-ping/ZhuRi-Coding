package com.heima.reward.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.entity.SignRecord;
import com.heima.reward.entity.UserAssets;
import com.heima.reward.entity.UserCheckinState;
import com.heima.reward.mapper.SignRecordMapper;
import com.heima.reward.mapper.UserAssetsMapper;
import com.heima.reward.mapper.UserCheckinStateMapper;
import com.heima.reward.util.SignRewardUtil;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 签到/补签的<b>事务体内核</b>（P0-6 事务边界修复）。
 *
 * <p><b>为什么单独拆一个类</b>：原实现把 Redis 锁的 {@code unlock} 写在
 * {@code @Transactional} 方法的 finally 里 —— 锁在<b>事务提交之前</b>就被释放，
 * 并发窗口内下一请求进入临界区却读不到上一事务未提交的数据（隔离级别决定可见性），
 * 靠 sign_record 唯一键兜底才没出事；补签路径（扣补签卡 + 矿石多退少补）无唯一键兜底，
 * 存在重复扣减风险。修复后：外层 {@code CheckinServiceImpl} 只做「加锁 → 委托 → 解锁」，
 * 事务在内层提交/回滚<b>之后</b>才释放锁。
 *
 * <p><b>为什么不能留在原类用 this.xxx() 调用</b>：Spring 自调用不走代理，
 * {@code @Transactional} 会静默失效（同类历史 P1 坑）—— 必须拆成独立 Bean 经容器代理调用。
 */
@Service
@Slf4j
public class CheckinTxService {

    @Autowired
    private SignRecordMapper signRecordMapper;
    @Autowired
    private UserCheckinStateMapper userCheckinStateMapper;
    @Autowired
    private UserAssetsMapper userAssetsMapper;

    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Shanghai");
    private static final java.time.format.DateTimeFormatter DATE_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private LocalDate getToday() {
        return LocalDate.now(ZONE);
    }

    /**
     * 从 targetDate 开始向前回溯，计算真实的连续签到天数
     * 一次查询最近60天窗口内全部签到记录，内存计算连续段，避免逐日查库（最多 60 次 select → 1 次）
     */
    private int calculateContinuousDays(Long userId, LocalDate targetDate) {
        LocalDate windowStart = targetDate.minusDays(60);
        LocalDate windowEnd = targetDate.minusDays(1);
        List<SignRecord> records = signRecordMapper.selectList(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .ge(SignRecord::getSignDate, windowStart)
                        .le(SignRecord::getSignDate, windowEnd)
        );
        java.util.Set<LocalDate> signedDates = records.stream()
                .map(r -> new java.sql.Date(r.getSignDate().getTime()).toLocalDate())
                .collect(Collectors.toSet());

        int count = 0;
        LocalDate cursor = targetDate.minusDays(1);
        while (signedDates.contains(cursor)) {
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    // ========================================================================
    // 每日签到（事务体）
    // ========================================================================

    /**
     * 每日签到业务体（事务内）。锁的获取与释放由外层 CheckinServiceImpl 编排。
     */
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult doCheckinTx(Long userId) {
        LocalDate today = getToday();

        // 校验今日是否已签到
        long count = signRecordMapper.selectCount(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .eq(SignRecord::getSignDate, today)
        );
        if (count > 0) {
            return ResponseResult.errorResult(400, "今日已签到，请勿重复签到");
        }

        // 计算截至昨天的连续天数
        int continuousDaysBefore = calculateContinuousDays(userId, today);
        int newContinuousDays = continuousDaysBefore + 1;

        // 计算奖励
        int award = SignRewardUtil.getRewardByContinuousDays(newContinuousDays);

        // 插入签到记录
        SignRecord record = new SignRecord();
        record.setUserId(userId);
        record.setSignDate(java.sql.Date.valueOf(today));
        record.setAwardOre(award);
        record.setIsExtra(false);
        try {
            signRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            return ResponseResult.errorResult(400, "今日已签到");
        }

        // 更新用户签到状态
        UserCheckinState state = userCheckinStateMapper.selectById(userId);
        if (state == null) {
            state = new UserCheckinState();
            state.setUserId(userId);
            state.setContinuousDays(newContinuousDays);
            state.setPeriodDay((newContinuousDays - 1) % 30 + 1);
            state.setLastCheckinDate(java.sql.Date.valueOf(today));
            state.setTotalCheckinDays(1);
            state.setPatchCardCount(0);
            userCheckinStateMapper.insert(state);
        } else {
            state.setContinuousDays(newContinuousDays);
            state.setPeriodDay((newContinuousDays - 1) % 30 + 1);
            state.setLastCheckinDate(java.sql.Date.valueOf(today));
            state.setTotalCheckinDays(state.getTotalCheckinDays() != null ? state.getTotalCheckinDays() + 1 : 1);
            userCheckinStateMapper.updateById(state);
        }

        // 更新矿石余额（已存在记录时原子累加，避免并发读改写覆盖丢失）
        UserAssets assets = userAssetsMapper.selectById(userId);
        if (assets == null) {
            assets = new UserAssets();
            assets.setUserId(userId);
            assets.setOreBalance(award);
            assets.setFrozenOre(0);
            assets.setLuckyValue(0);
            userAssetsMapper.insert(assets);
        } else {
            userAssetsMapper.addOreBalance(userId, award);
        }

        // 赠送免费抽奖次数（暂为日志）
        log.info("签到成功，userId={}，连续天数={}，获得矿石={}", userId, newContinuousDays, award);

        // 构建返回
        Map<String, Object> data = new HashMap<>();
        data.put("awardOre", award);
        data.put("continuousDays", newContinuousDays);
        data.put("totalSignDays", state.getTotalCheckinDays());
        data.put("totalOre", assets.getOreBalance());
        data.put("milestoneProgress", SignRewardUtil.milestoneProgress(newContinuousDays));

        // 计算下一个特殊奖励节点
        data.put("nextSpecial", SignRewardUtil.nextSpecial(newContinuousDays));

        return ResponseResult.okResult(data);
    }

    // ========================================================================
    // 补签操作（事务体）
    // ========================================================================

    /**
     * 补签业务体（事务内）。锁的获取与释放由外层 CheckinServiceImpl 编排。
     */
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult doExtraTx(Long userId, String targetDateStr) {
        LocalDate today = getToday();
        LocalDate targetDate = LocalDate.parse(targetDateStr, DATE_FMT);

        // 校验日期范围
        if (targetDate.isAfter(today.minusDays(1))) {
            return ResponseResult.errorResult(400, "不能补签今天或未来的日期");
        }
        if (targetDate.isBefore(today.minusDays(30))) {
            return ResponseResult.errorResult(400, "只能补签最近30天内的日期");
        }

        // 校验该日是否已签到
        SignRecord existing = signRecordMapper.selectOne(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .eq(SignRecord::getSignDate, targetDate)
        );
        if (existing != null) {
            return ResponseResult.errorResult(400, "该日已签到，无需补签");
        }

        // 校验补签卡
        UserCheckinState state = userCheckinStateMapper.selectById(userId);
        if (state == null || state.getPatchCardCount() == null || state.getPatchCardCount() <= 0) {
            return ResponseResult.errorResult(400, "补签卡不足");
        }

        // 扣减补签卡
        state.setPatchCardCount(state.getPatchCardCount() - 1);
        userCheckinStateMapper.updateById(state);

        // 1. 获取受影响时间窗口（45天窗口）
        LocalDate windowStart = targetDate.minusDays(45);
        LocalDate windowEnd = today.plusDays(1);
        List<SignRecord> windowRecords = signRecordMapper.selectList(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .ge(SignRecord::getSignDate, windowStart)
                        .le(SignRecord::getSignDate, windowEnd)
                        .orderByAsc(SignRecord::getSignDate)
        );
        Map<LocalDate, SignRecord> recordMap = windowRecords.stream()
                .collect(Collectors.toMap(
                        r -> new java.sql.Date(r.getSignDate().getTime()).toLocalDate(),
                        r -> r,
                        (a, b) -> a
                ));

        // 2. 模拟补签插入映射
        SignRecord extraRecord = new SignRecord();
        extraRecord.setUserId(userId);
        extraRecord.setSignDate(java.sql.Date.valueOf(targetDate));
        extraRecord.setAwardOre(0);
        extraRecord.setIsExtra(true);
        recordMap.put(targetDate, extraRecord);

        // 3. 寻找连续段 [segStart, segEnd]
        LocalDate segStart = targetDate;
        while (recordMap.containsKey(segStart.minusDays(1))) {
            segStart = segStart.minusDays(1);
        }
        LocalDate segEnd = targetDate;
        while (recordMap.containsKey(segEnd.plusDays(1))) {
            segEnd = segEnd.plusDays(1);
        }
        if (segEnd.isAfter(today)) segEnd = today;

        log.info("补签重算段: {} ~ {}", segStart, segEnd);

        // 4. 重算该段内每一天的奖励
        int extraOreSum = 0;
        List<Map<String, Object>> updatedDays = new ArrayList<>();

        for (LocalDate date = segStart; !date.isAfter(segEnd); date = date.plusDays(1)) {
            SignRecord rec = recordMap.get(date);
            if (rec == null) continue;

            int pos = (int) ChronoUnit.DAYS.between(segStart, date) + 1;
            int newOre = SignRewardUtil.getRewardByContinuousDays(pos);
            int oldOre = rec.getAwardOre() != null ? rec.getAwardOre() : 0;

            if (newOre != oldOre || rec.getId() == null) {
                int diff = newOre - oldOre;
                extraOreSum += diff;

                Map<String, Object> updatedDay = new HashMap<>();
                updatedDay.put("date", date.format(DATE_FMT));
                updatedDay.put("newOre", newOre);
                updatedDay.put("oldOre", rec.getId() == null ? null : oldOre);
                updatedDays.add(updatedDay);

                if (rec.getId() != null) {
                    // 已存在记录，更新 award_ore
                    rec.setAwardOre(newOre);
                    signRecordMapper.updateById(rec);
                } else {
                    // 补签新记录
                    rec.setAwardOre(newOre);
                    rec.setUserId(userId);
                    rec.setSignDate(java.sql.Date.valueOf(date));
                    rec.setIsExtra(true);
                    try {
                        signRecordMapper.insert(rec);
                    } catch (DuplicateKeyException e) {
                        log.warn("补签时发现重复记录: userId={}, date={}", userId, date);
                    }
                }
            }
        }

        // 5. 更新用户状态
        int newContinuousDays = calculateContinuousDays(userId, today);
        // 如果今天已签到，则连续天数+1
        boolean todaySigned = signRecordMapper.selectCount(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .eq(SignRecord::getSignDate, today)
        ) > 0;
        int displayContinuousDays = todaySigned ? newContinuousDays + 1 : newContinuousDays;

        state.setContinuousDays(displayContinuousDays);
        if (displayContinuousDays > 0) {
            state.setPeriodDay((displayContinuousDays - 1) % 30 + 1);
        }
        // 如果补签日期晚于 lastCheckinDate，则更新
        LocalDate lastDate = state.getLastCheckinDate() != null
                ? new java.sql.Date(state.getLastCheckinDate().getTime()).toLocalDate()
                : null;
        if (lastDate == null || targetDate.isAfter(lastDate)) {
            state.setLastCheckinDate(java.sql.Date.valueOf(targetDate));
        }
        state.setTotalCheckinDays(state.getTotalCheckinDays() != null ? state.getTotalCheckinDays() + 1 : 1);
        userCheckinStateMapper.updateById(state);

        // 6. 更新矿石余额（原子累加；extraOreSum 可为负，实现"多退少补"）
        if (extraOreSum != 0) {
            UserAssets assets = userAssetsMapper.selectById(userId);
            if (assets != null) {
                userAssetsMapper.addOreBalance(userId, extraOreSum);
            }
        }

        // 7. 构建返回结果
        Map<String, Object> data = new HashMap<>();
        data.put("extraOre", extraOreSum);
        data.put("newContinuousDays", displayContinuousDays);
        data.put("updatedDays", updatedDays);

        // 计算补签后的总矿石
        UserAssets finalAssets = userAssetsMapper.selectById(userId);
        data.put("totalOre", finalAssets != null ? finalAssets.getOreBalance() : 0);
        data.put("extraCards", state.getPatchCardCount());

        return ResponseResult.okResult(data);
    }
}
