package com.heima.reward.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.article.ILevelClient;
import com.heima.apis.user.IUserClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.entity.SignRecord;
import com.heima.reward.entity.UserAssets;
import com.heima.reward.entity.UserCheckinState;
import com.heima.reward.mapper.SignRecordMapper;
import com.heima.reward.mapper.UserAssetsMapper;
import com.heima.reward.mapper.UserCheckinStateMapper;
import com.heima.reward.service.CheckinService;
import com.heima.reward.util.SignRewardUtil;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class CheckinServiceImpl implements CheckinService {

    @Autowired
    private SignRecordMapper signRecordMapper;
    @Autowired
    private UserCheckinStateMapper userCheckinStateMapper;
    @Autowired
    private UserAssetsMapper userAssetsMapper;
    @Autowired
    private IUserClient userClient;
    @Autowired
    private ILevelClient levelClient;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String LOCK_KEY_PREFIX = "sign:lock:";
    private static final long LOCK_EXPIRE_SECONDS = 3;

    // ========================================================================
    // 核心工具方法
    // ========================================================================

    /**
     * 从 targetDate 开始向前回溯，计算真实的连续签到天数
     * 从 targetDate 的前一天开始逐日向前查询，直到断签为止
     */
    private int calculateContinuousDays(Long userId, LocalDate targetDate) {
        int count = 0;
        LocalDate cursor = targetDate.minusDays(1);
        int maxScan = 60; // 最多扫描60天，防止无限循环
        while (maxScan-- > 0) {
            SignRecord record = signRecordMapper.selectOne(
                    new LambdaQueryWrapper<SignRecord>()
                            .eq(SignRecord::getUserId, userId)
                            .eq(SignRecord::getSignDate, cursor)
            );
            if (record == null) break;
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    /**
     * 获取服务器当前日期（Asia/Shanghai）
     */
    private LocalDate getToday() {
        return LocalDate.now(ZONE);
    }

    /**
     * 尝试获取Redis分布式锁
     */
    private boolean tryLock(Long userId) {
        String key = LOCK_KEY_PREFIX + userId;
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(key, "1",
                java.time.Duration.ofSeconds(LOCK_EXPIRE_SECONDS));
        return Boolean.TRUE.equals(locked);
    }

    /**
     * 释放Redis分布式锁
     */
    private void unlock(Long userId) {
        String key = LOCK_KEY_PREFIX + userId;
        redisTemplate.delete(key);
    }

    // ========================================================================
    // 1. 获取签到状态与日历数据
    // ========================================================================

    @Override
    public ResponseResult getStatus(Long userId) {
        LocalDate today = getToday();
        String todayStr = today.format(DATE_FMT);

        // 今日是否已签到
        boolean todaySigned = signRecordMapper.selectCount(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .eq(SignRecord::getSignDate, today)
        ) > 0;

        // 计算截至今日的连续签到天数（不含今日）
        int continuousDays = calculateContinuousDays(userId, today);
        // 若今日已签到，连续天数 = 回溯结果 + 1
        int displayContinuousDays = todaySigned ? continuousDays + 1 : continuousDays;

        // 用户资产
        UserAssets assets = userAssetsMapper.selectById(userId);
        int totalOre = (assets != null) ? assets.getOreBalance() : 0;

        // 签到状态
        UserCheckinState state = userCheckinStateMapper.selectById(userId);
        int totalSignDays = (state != null && state.getTotalCheckinDays() != null) ? state.getTotalCheckinDays() : 0;
        int patchCardCount = (state != null && state.getPatchCardCount() != null) ? state.getPatchCardCount() : 0;

        // 构建日历数据：当前月 + 上个月
        List<Map<String, Object>> calendarMonths = new ArrayList<>();
        calendarMonths.add(buildCalendarMonth(userId, today.minusMonths(1), today, todaySigned, continuousDays));
        calendarMonths.add(buildCalendarMonth(userId, today, today, todaySigned, continuousDays));

        // 用户信息
        Map<String, Object> userInfo = buildUserInfo(userId);

        // 构建里程碑进度
        Map<String, Object> milestoneProgress = buildMilestoneProgress(displayContinuousDays);

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("continuousDays", displayContinuousDays);
        data.put("totalSignDays", totalSignDays);
        data.put("totalOre", totalOre);
        data.put("extraCards", patchCardCount);
        data.put("today", todayStr);
        data.put("todaySigned", todaySigned);
        data.put("userInfo", userInfo);
        data.put("calendarMonths", calendarMonths);
        data.put("milestoneProgress", milestoneProgress);

        // 下一个特殊奖励节点
        data.put("nextSpecial", buildNextSpecial(displayContinuousDays));

        return ResponseResult.okResult(data);
    }

    /**
     * 构建单个月的日历数据
     */
    private Map<String, Object> buildCalendarMonth(Long userId, LocalDate month,
                                                    LocalDate today, boolean todaySigned, int continuousDaysBeforeToday) {
        int year = month.getYear();
        int monthValue = month.getMonthValue();
        int totalDays = month.lengthOfMonth();
        LocalDate monthStart = LocalDate.of(year, monthValue, 1);
        LocalDate monthEnd = LocalDate.of(year, monthValue, totalDays);

        // 查询该月所有签到记录
        List<SignRecord> records = signRecordMapper.selectList(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .ge(SignRecord::getSignDate, monthStart)
                        .le(SignRecord::getSignDate, monthEnd)
        );
        Map<LocalDate, SignRecord> recordMap = records.stream()
                .collect(Collectors.toMap(
                        r -> new java.sql.Date(r.getSignDate().getTime()).toLocalDate(),
                        r -> r,
                        (a, b) -> a
                ));

        // 计算该月的第一天是星期几（0=周日）
        int firstDayOfWeek = monthStart.getDayOfWeek().getValue() % 7; // 0=周日

        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= totalDays; d++) {
            LocalDate date = LocalDate.of(year, monthValue, d);
            Map<String, Object> day = new HashMap<>();
            day.put("date", date.format(DATE_FMT));
            day.put("dayOfMonth", d);
            day.put("dayOfWeek", date.getDayOfWeek().getValue() % 7);
            day.put("isToday", date.equals(today));

            SignRecord record = recordMap.get(date);

            if (record != null) {
                // 已签到
                day.put("status", record.getIsExtra() ? "extra_signed" : "signed");
                day.put("oreAmount", record.getAwardOre());
                day.put("canExtra", false);
                day.put("isSpecialDay", SignRewardUtil.isSpecialDay(
                        // 需要计算这颗签到在连续段中的位置
                        findPositionInSegment(userId, date, today)
                ));
            } else if (date.isAfter(today)) {
                // 未来日期：显示预期奖励
                day.put("status", "future");
                day.put("canExtra", false);
                // 计算预期连续天数：今日已签到的连续天数 + 未来偏移
                int baseDays = todaySigned ? continuousDaysBeforeToday + 1 : continuousDaysBeforeToday;
                int futureOffset = (int) ChronoUnit.DAYS.between(today, date);
                int expectedContinuousDay = baseDays + futureOffset;
                day.put("oreAmount", SignRewardUtil.getRewardByContinuousDays(expectedContinuousDay));
                day.put("isSpecialDay", SignRewardUtil.isSpecialDay(expectedContinuousDay));
            } else if (date.isBefore(today.minusDays(30))) {
                // 过期不可补签（超过30天）
                day.put("status", "expired");
                day.put("oreAmount", 0);
                day.put("canExtra", false);
                day.put("isSpecialDay", false);
            } else if (date.equals(today)) {
                // 今日未签到
                day.put("status", "unsigned");
                day.put("oreAmount", 0);
                day.put("canExtra", false);
                day.put("isSpecialDay", false);
            } else {
                // 可补签（过去30天内且未签到）
                day.put("status", "unsigned");
                day.put("oreAmount", 0);
                day.put("canExtra", true);
                day.put("isSpecialDay", false);
            }

            days.add(day);
        }

        Map<String, Object> calendarMonth = new HashMap<>();
        calendarMonth.put("year", year);
        calendarMonth.put("month", monthValue);
        calendarMonth.put("firstDayOfWeek", firstDayOfWeek);
        calendarMonth.put("days", days);

        return calendarMonth;
    }

    /**
     * 查找某签到日期在连续段中的位置（用于判断是否特殊奖励日）
     */
    private int findPositionInSegment(Long userId, LocalDate date, LocalDate today) {
        // 向前找连续段起点
        LocalDate segStart = date;
        int maxScan = 60;
        while (maxScan-- > 0) {
            LocalDate prev = segStart.minusDays(1);
            SignRecord r = signRecordMapper.selectOne(
                    new LambdaQueryWrapper<SignRecord>()
                            .eq(SignRecord::getUserId, userId)
                            .eq(SignRecord::getSignDate, prev)
            );
            if (r == null) break;
            segStart = prev;
        }
        return (int) ChronoUnit.DAYS.between(segStart, date) + 1;
    }

    // ========================================================================
    // 2. 每日签到
    // ========================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult doCheckin(Long userId) {
        // 分布式锁
        if (!tryLock(userId)) {
            return ResponseResult.errorResult(429, "操作过于频繁，请稍后再试");
        }
        try {
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

            // 更新矿石余额
            UserAssets assets = userAssetsMapper.selectById(userId);
            if (assets == null) {
                assets = new UserAssets();
                assets.setUserId(userId);
                assets.setOreBalance(award);
                assets.setFrozenOre(0);
                assets.setLuckyValue(0);
                userAssetsMapper.insert(assets);
            } else {
                assets.setOreBalance(assets.getOreBalance() + award);
                userAssetsMapper.updateById(assets);
            }

            // 赠送免费抽奖次数（暂为日志）
            log.info("签到成功，userId={}，连续天数={}，获得矿石={}", userId, newContinuousDays, award);

            // 构建返回
            Map<String, Object> data = new HashMap<>();
            data.put("awardOre", award);
            data.put("continuousDays", newContinuousDays);
            data.put("totalSignDays", state.getTotalCheckinDays());
            data.put("totalOre", assets.getOreBalance());
            data.put("milestoneProgress", buildMilestoneProgress(newContinuousDays));

            // 计算下一个特殊奖励节点
            Map<String, Object> nextSpecial = buildNextSpecial(newContinuousDays);
            data.put("nextSpecial", nextSpecial);

            return ResponseResult.okResult(data);
        } finally {
            unlock(userId);
        }
    }

    // ========================================================================
    // 3. 补签操作（最复杂核心）
    // ========================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult doExtra(Long userId, String targetDateStr) {
        // 分布式锁
        if (!tryLock(userId)) {
            return ResponseResult.errorResult(429, "操作过于频繁，请稍后再试");
        }
        try {
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

            // 6. 更新矿石余额
            if (extraOreSum != 0) {
                UserAssets assets = userAssetsMapper.selectById(userId);
                if (assets != null) {
                    assets.setOreBalance(assets.getOreBalance() + extraOreSum);
                    userAssetsMapper.updateById(assets);
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
        } finally {
            unlock(userId);
        }
    }

    // ========================================================================
    // 4. 获取今日签到状态（侧边栏用）
    // ========================================================================

    @Override
    public ResponseResult getTodayStatus(Long userId) {
        LocalDate today = getToday();
        boolean todaySigned = signRecordMapper.selectCount(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .eq(SignRecord::getSignDate, today)
        ) > 0;

        int continuousDays = calculateContinuousDays(userId, today);
        if (todaySigned) {
            continuousDays = continuousDays + 1;
        }

        UserAssets assets = userAssetsMapper.selectById(userId);
        int totalOre = (assets != null) ? assets.getOreBalance() : 0;

        UserCheckinState state = userCheckinStateMapper.selectById(userId);
        int patchCardCount = (state != null && state.getPatchCardCount() != null) ? state.getPatchCardCount() : 0;

        Map<String, Object> data = new HashMap<>();
        data.put("isSignedIn", todaySigned);
        data.put("consecutiveDays", continuousDays);
        data.put("totalOre", totalOre);
        data.put("patchCardCount", patchCardCount);

        return ResponseResult.okResult(data);
    }

    // ========================================================================
    // 5. 获取用户连续签到天数（含今日，供其他服务 Feign 调用，成就勋章判定用）
    // ========================================================================

    /**
     * 获取用户连续签到天数（含今日，供其他服务 Feign 调用，成就勋章判定用）
     */
    @Override
    public ResponseResult getContinuousCheckinDays(Long userId) {
        LocalDate today = getToday();
        boolean todaySigned = signRecordMapper.selectCount(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .eq(SignRecord::getSignDate, today)
        ) > 0;
        int continuousDays = calculateContinuousDays(userId, today);
        int display = todaySigned ? continuousDays + 1 : continuousDays;

        Map<String, Object> data = new HashMap<>();
        data.put("continuousDays", display);
        return ResponseResult.okResult(data);
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 构建里程碑进度数据
     */
    private Map<String, Object> buildMilestoneProgress(int continuousDays) {
        int[] specialDays = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30};
        int[] specialOres = {100, 150, 512, 250, 300, 350, 1024, 450, 500, 550, 600, 650, 700, 2048, 700, 700, 700, 700, 700, 700, 4096, 700, 700, 700, 700, 700, 700, 700, 700, 5120};

        List<Map<String, Object>> specialDayList = new ArrayList<>();
        for (int i = 0; i < specialDays.length; i++) {
            int sd = specialDays[i];
            int ore = specialOres[i];
            Map<String, Object> m = new HashMap<>();
            m.put("day", sd);
            m.put("ore", ore);
            m.put("achieved", continuousDays >= sd);
            m.put("isCurrent", continuousDays == sd);
            m.put("isSpecial", ore > 700);
            specialDayList.add(m);
        }

        int percent = Math.min((int) (((continuousDays % 30) * 100.0) / 30), 100);

        Map<String, Object> progress = new HashMap<>();
        progress.put("current", continuousDays % 30 == 0 ? 30 : continuousDays % 30);
        progress.put("total", 30);
        progress.put("percent", percent);
        progress.put("specialDays", specialDayList);

        return progress;
    }

    /**
     * 构建下一个特殊奖励节点信息
     */
    private Map<String, Object> buildNextSpecial(int currentContinuousDays) {
        int[] specialDays = {3, 7, 14, 21, 30};
        int[] specialOres = {512, 1024, 2048, 4096, 5120};
        int periodDay = currentContinuousDays % 30 == 0 ? 30 : currentContinuousDays % 30;

        for (int i = 0; i < specialDays.length; i++) {
            if (periodDay < specialDays[i]) {
                int daysLeft = specialDays[i] - periodDay;
                Map<String, Object> result = new HashMap<>();
                result.put("day", specialDays[i]);
                result.put("ore", specialOres[i]);
                result.put("daysLeft", daysLeft);
                return result;
            }
        }
        // 当前周期已过所有特殊节点，返回下一个周期的第一个特殊节点
        int daysLeft = (30 - periodDay) + 3;
        Map<String, Object> result = new HashMap<>();
        result.put("day", 3);
        result.put("ore", 512);
        result.put("daysLeft", daysLeft);
        return result;
    }

    /**
     * 构建用户信息
     */
    private Map<String, Object> buildUserInfo(Long userId) {
        Map<String, Object> userInfo = new HashMap<>();
        try {
            ResponseResult userResult = userClient.getBasicInfo(userId);
            if (userResult != null && userResult.getCode() == 200 && userResult.getData() != null) {
                Map<String, Object> userData = (Map<String, Object>) userResult.getData();
                userInfo.put("userId", userData.getOrDefault("userId", userId));
                userInfo.put("nickname", userData.getOrDefault("nickname", "用户" + userId));
                userInfo.put("avatar", userData.getOrDefault("avatar", ""));
            } else {
                userInfo.put("userId", userId);
                userInfo.put("nickname", "用户" + userId);
                userInfo.put("avatar", "");
            }
        } catch (Exception e) {
            log.warn("获取用户信息失败 userId={}: {}", userId, e.getMessage());
            userInfo.put("userId", userId);
            userInfo.put("nickname", "用户" + userId);
            userInfo.put("avatar", "");
        }

        try {
            Map<String, Object> levelInfo = levelClient.getUserLevelInfo(userId);
            if (levelInfo != null && !levelInfo.isEmpty()) {
                Integer dailyLevel = levelInfo.get("dailyLevel") instanceof Integer
                        ? (Integer) levelInfo.get("dailyLevel")
                        : 1;
                userInfo.put("level", "ZR." + dailyLevel);
            } else {
                userInfo.put("level", "ZR.1");
            }
        } catch (Exception e) {
            log.warn("获取用户等级信息失败 userId={}: {}", userId, e.getMessage());
            userInfo.put("level", "ZR.1");
        }

        return userInfo;
    }
}