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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 签到服务（外层：分布式锁编排 + 只读查询）。
 *
 * <p><b>P0-6 事务边界修复</b>：原实现把 {@code unlock} 写在 {@code @Transactional}
 * 方法的 finally 里，锁在事务提交前释放 —— 并发窗口内下一请求进入临界区却读不到
 * 未提交数据，补签路径存在重复扣补签卡风险。修复后本类只负责
 * 「加锁 → 委托 {@link CheckinTxService}（事务体内核）→ finally 解锁」：
 * <b>锁释放严格晚于内层事务的提交/回滚</b>。事务方法拆到独立 Bean 是为了避开
 * Spring 自调用不走代理导致 {@code @Transactional} 失效的坑。
 *
 * <p>锁说明：SETNX + 3s 过期（无 owner 校验、无续期）—— 同用户临界区秒级即可完成，
 * 过期时间兜底进程崩溃；写库重复仍由 sign_record 唯一键兜底，纵深防御。
 */
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
    @Autowired
    private CheckinTxService checkinTxService;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String LOCK_KEY_PREFIX = "sign:lock:";
    private static final long LOCK_EXPIRE_SECONDS = 3;

    // ========================================================================
    // 核心工具方法
    // ========================================================================

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
        Set<LocalDate> signedDates = records.stream()
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
        Map<String, Object> milestoneProgress = SignRewardUtil.milestoneProgress(displayContinuousDays);

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
        data.put("nextSpecial", SignRewardUtil.nextSpecial(displayContinuousDays));

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
     * 一次查询窗口内记录，内存回溯连续段起点，避免逐日查库
     */
    private int findPositionInSegment(Long userId, LocalDate date, LocalDate today) {
        LocalDate windowStart = date.minusDays(60);
        List<SignRecord> records = signRecordMapper.selectList(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .ge(SignRecord::getSignDate, windowStart)
                        .le(SignRecord::getSignDate, date)
        );
        Set<LocalDate> signedDates = records.stream()
                .map(r -> new java.sql.Date(r.getSignDate().getTime()).toLocalDate())
                .collect(Collectors.toSet());

        LocalDate segStart = date;
        while (signedDates.contains(segStart.minusDays(1))) {
            segStart = segStart.minusDays(1);
        }
        return (int) ChronoUnit.DAYS.between(segStart, date) + 1;
    }

    // ========================================================================
    // 2. 每日签到（锁编排：加锁 → 事务体内核 → 解锁，锁释放在事务边界之后）
    // ========================================================================

    @Override
    public ResponseResult doCheckin(Long userId) {
        // 分布式锁
        if (!tryLock(userId)) {
            return ResponseResult.errorResult(429, "操作过于频繁，请稍后再试");
        }
        try {
            // 事务在内层 CheckinTxService 提交/回滚后，才会执行到本 finally 的 unlock
            return checkinTxService.doCheckinTx(userId);
        } finally {
            unlock(userId);
        }
    }

    // ========================================================================
    // 3. 补签操作（锁编排，同 doCheckin）
    // ========================================================================

    @Override
    public ResponseResult doExtra(Long userId, String targetDateStr) {
        // 分布式锁
        if (!tryLock(userId)) {
            return ResponseResult.errorResult(429, "操作过于频繁，请稍后再试");
        }
        try {
            return checkinTxService.doExtraTx(userId, targetDateStr);
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
