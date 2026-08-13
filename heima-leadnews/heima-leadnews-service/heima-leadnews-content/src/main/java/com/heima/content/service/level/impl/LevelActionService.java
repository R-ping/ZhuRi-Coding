package com.heima.content.service.level.impl;

import static com.heima.content.constants.LevelScoreConstants.ACTION_SCORE_MAP;
import static com.heima.content.constants.LevelScoreConstants.DAILY_ACTION_LIMIT;
import static com.heima.content.constants.LevelScoreConstants.DAILY_SCORE_LIMIT;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.level.ApBehaviorConfigMapper;
import com.heima.content.mapper.level.ApUserDailyProgressMapper;
import com.heima.content.mapper.level.ApUserLevelMapper;
import com.heima.content.mapper.pins.ApUserActionLogMapper;
import com.heima.content.service.level.LevelPermissionService;
import com.heima.model.level.pojos.ApBehaviorConfig;
import com.heima.model.level.pojos.ApUserDailyProgress;
import com.heima.model.level.pojos.ApUserLevel;
import com.heima.model.user.pojos.ApUserActionLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 行为记录与积分服务 — 负责用户行为记录、逐日分计算、签到
 */
@Slf4j
@Service
public class LevelActionService {

    @Autowired
    private ApUserActionLogMapper actionLogMapper;

    @Autowired
    private ApUserLevelMapper userLevelMapper;

    @Autowired
    private LevelQueryService levelQueryService;

    @Autowired
    private LevelPermissionService permissionService;

    @Autowired
    private LevelDiamondService diamondService;

    @Autowired
    private LevelTaskProgressBuilder taskProgressBuilder;

    @Autowired
    private ApBehaviorConfigMapper behaviorConfigMapper;

    @Autowired
    private ApUserDailyProgressMapper userDailyProgressMapper;

    /**
     * 记录行为（默认行为，无限制校验）
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordAction(Long userId, String actionType, String actionDetail) {
        Integer score = ACTION_SCORE_MAP.getOrDefault(actionType, 0);
        if (score == null || score == 0) {
            return;
        }

        ApUserLevel userLevel = levelQueryService.getUserLevel(userId);
        grantScore(userLevel, userId, actionType, BigDecimal.valueOf(score), actionDetail);
    }

    /**
     * 记录行为（含限制校验，返回结果）— 分值来自行为配置表
     */
    public Map<String, Object> recordActionWithLimit(Long userId, String actionType, String actionDetail) {
        Integer score = ACTION_SCORE_MAP.getOrDefault(actionType, 0);
        if (score == null || score == 0) {
            return buildFailResult("无效的行为类型");
        }
        return recordActionWithLimit(userId, actionType, BigDecimal.valueOf(score), actionDetail);
    }

    /**
     * 支付行为：按实际支付金额加逐日经验（金额即经验值，支持小数），受每日上限控制
     */
    public Map<String, Object> recordPaymentAction(Long userId, String actionType, BigDecimal amount,
        String actionDetail) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return buildFailResult("无效的支付金额");
        }
        return recordActionWithLimit(userId, actionType, amount, actionDetail);
    }

    /**
     * 记录行为（含限制校验，返回结果）— score 为本次期望获得的经验值
     */
    @Transactional(rollbackFor = Exception.class)
    private Map<String, Object> recordActionWithLimit(Long userId, String actionType, BigDecimal score,
        String actionDetail) {
        Map<String, Object> result = new HashMap<>();

        ApUserLevel userLevel = levelQueryService.getUserLevel(userId);

        String today = new java.sql.Date(System.currentTimeMillis()).toString();

        Integer dailyLimit = DAILY_ACTION_LIMIT.get(actionType);
        if (dailyLimit != null && getTodayActionCount(userId, actionType, today) >= dailyLimit) {
            return buildFailResult("今日该行为已达上限");
        }

        BigDecimal todayScore = getTodayScore(userId, today);
        BigDecimal remain = BigDecimal.valueOf(DAILY_SCORE_LIMIT).subtract(todayScore);
        BigDecimal actualScore = score.min(remain);
        if (actualScore.compareTo(BigDecimal.ZERO) <= 0) {
            return buildFailResult("今日积分已达上限");
        }

        grantScore(userLevel, userId, actionType, actualScore, actionDetail);

        result.put("success", true);
        result.put("message", "行为记录成功");
        result.put("score", actualScore);
        return result;
    }

    /**
     * 构建失败返回
     */
    private Map<String, Object> buildFailResult(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        result.put("score", BigDecimal.ZERO);
        return result;
    }

    /**
     * 每日签到
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkIn(Long userId) {
        Map<String, Object> result = new HashMap<>();

        String today = new java.sql.Date(System.currentTimeMillis()).toString();
        LambdaQueryWrapper<ApUserActionLog> logQuery = new LambdaQueryWrapper<>();
        logQuery.eq(ApUserActionLog::getUserId, userId);
        logQuery.eq(ApUserActionLog::getActionType, "daily_checkin");
        logQuery.apply("DATE(created_time) = {0}", today);
        long todayCheckinCount = actionLogMapper.selectCount(logQuery);

        if (todayCheckinCount > 0) {
            result.put("success", false);
            result.put("hasCheckedIn", true);
            result.put("score", BigDecimal.ZERO);
            return result;
        }

        ApUserLevel userLevel = levelQueryService.getUserLevel(userId);

        Integer dailyLimit = DAILY_ACTION_LIMIT.get("daily_checkin");
        if (dailyLimit != null && todayCheckinCount >= dailyLimit) {
            result.put("success", false);
            result.put("hasCheckedIn", true);
            result.put("score", BigDecimal.ZERO);
            return result;
        }

        BigDecimal todayScore = getTodayScore(userId, today);

        BigDecimal score = BigDecimal.valueOf(ACTION_SCORE_MAP.getOrDefault("daily_checkin", 0));
        BigDecimal actualScore = score.min(BigDecimal.valueOf(DAILY_SCORE_LIMIT).subtract(todayScore));
        if (actualScore.compareTo(BigDecimal.ZERO) <= 0) {
            result.put("success", false);
            result.put("hasCheckedIn", false);
            result.put("score", BigDecimal.ZERO);
            return result;
        }

        grantScore(userLevel, userId, "daily_checkin", actualScore, "每日签到");

        result.put("success", true);
        result.put("hasCheckedIn", true);
        result.put("score", actualScore);
        return result;
    }

    /**
     * 获取今日任务进度
     */
    public Map<String, Object> getTodayTaskProgress(Long userId) {
        return taskProgressBuilder.buildTaskProgress(userId);
    }

    /**
     * 获取用户今日积分总和
     */
    private BigDecimal getTodayScore(Long userId, String today) {
        LambdaQueryWrapper<ApUserActionLog> query = new LambdaQueryWrapper<>();
        query.eq(ApUserActionLog::getUserId, userId);
        query.apply("DATE(created_time) = {0}", today);
        return actionLogMapper.selectList(query).stream()
            .map(ApUserActionLog::getScoreChange)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 获取用户今日指定行为次数
     */
    private long getTodayActionCount(Long userId, String actionType, String today) {
        LambdaQueryWrapper<ApUserActionLog> query = new LambdaQueryWrapper<>();
        query.eq(ApUserActionLog::getUserId, userId);
        query.eq(ApUserActionLog::getActionType, actionType);
        query.apply("DATE(created_time) = {0}", today);
        return actionLogMapper.selectCount(query);
    }

    /**
     * 记录被动行为每日进度（不发积分、不写行为日志）
     * 用于"社区影响力"被动行为（be_followed/pin_liked/article_liked）的进度统计
     */
    public void recordPassiveAction(Long userId, String actionType) {
        upsertDailyProgress(userId, actionType);
    }

    /**
     * 核心加分：写行为日志、更新逐日经验与等级、升级时发权限与钻石
     *
     * @param userLevel    用户等级记录（可变，内部累加后落库）
     * @param userId       用户ID
     * @param actionType   行为类型
     * @param actualScore  实际获得的经验值（已扣除每日上限）
     * @param actionDetail 行为详情
     */
    private void grantScore(ApUserLevel userLevel, Long userId, String actionType, BigDecimal actualScore,
        String actionDetail) {
        ApUserActionLog actionLog = new ApUserActionLog();
        actionLog.setUserId(userId);
        actionLog.setActionType(actionType);
        actionLog.setScoreChange(actualScore);
        actionLog.setActionDetail(actionDetail);
        actionLogMapper.insert(actionLog);

        upsertDailyProgress(userId, actionType);

        BigDecimal currentScore = userLevel.getDailyScore() != null ? userLevel.getDailyScore() : BigDecimal.ZERO;
        BigDecimal todayScore = userLevel.getDailyScoreToday() != null ? userLevel.getDailyScoreToday()
            : BigDecimal.ZERO;
        userLevel.setDailyScore(currentScore.add(actualScore));
        userLevel.setDailyScoreToday(todayScore.add(actualScore));

        int newDailyLevel = levelQueryService.calculateLevel(1, userLevel.getDailyScore());
        if (newDailyLevel != userLevel.getDailyLevel()) {
            int oldLevel = userLevel.getDailyLevel();
            userLevel.setDailyLevel(newDailyLevel);
            permissionService.updateUserPermissions(userId, 1, oldLevel, newDailyLevel);
            diamondService.grantDiamondOnLevelUp(userId, 1, newDailyLevel);
        }

        userLevelMapper.updateById(userLevel);

        log.info("用户{}执行行为{}，获得逐日分{}，当前逐日等级{}", userId, actionType, actualScore,
            userLevel.getDailyLevel());
    }

    /**
     * 写入/更新用户每日行为进度表 ap_user_daily_progress
     * 仅统计行为配置表中存在的行为，失败不影响主流程
     */
    private void upsertDailyProgress(Long userId, String actionType) {
        try {
            String actionCode = normalizeActionCode(actionType);
            if (actionCode == null) {
                return;
            }
            // 行为不在配置表中（如 daily_checkin/share）则跳过
            LambdaQueryWrapper<ApBehaviorConfig> configQuery = new LambdaQueryWrapper<>();
            configQuery.eq(ApBehaviorConfig::getActionCode, actionCode);
            configQuery.eq(ApBehaviorConfig::getIsActive, 1);
            if (behaviorConfigMapper.selectCount(configQuery) == 0) {
                return;
            }

            java.sql.Date today = new java.sql.Date(System.currentTimeMillis());
            LambdaQueryWrapper<ApUserDailyProgress> progressQuery = new LambdaQueryWrapper<>();
            progressQuery.eq(ApUserDailyProgress::getUserId, userId);
            progressQuery.eq(ApUserDailyProgress::getStatDate, today);
            progressQuery.eq(ApUserDailyProgress::getActionCode, actionCode);
            ApUserDailyProgress progress = userDailyProgressMapper.selectOne(progressQuery);

            if (progress != null) {
                progress.setCount((progress.getCount() == null ? 0 : progress.getCount()) + 1);
                progress.setUpdatedTime(new Date());
                userDailyProgressMapper.updateById(progress);
            } else {
                ApUserDailyProgress newProgress = new ApUserDailyProgress();
                newProgress.setUserId(userId);
                newProgress.setStatDate(today);
                newProgress.setActionCode(actionCode);
                newProgress.setCount(1);
                newProgress.setUpdatedTime(new Date());
                userDailyProgressMapper.insert(newProgress);
            }
        } catch (Exception e) {
            log.warn("写入用户每日行为进度失败: userId={}, actionType={}", userId, actionType, e);
        }
    }

    /**
     * 将行为编码归一化为 ap_behavior_config.action_code
     */
    private String normalizeActionCode(String actionType) {
        if (actionType == null) {
            return null;
        }
        switch (actionType) {
            case "publish_pins": return "publish_pin";
            case "browse_course": return "browse_article";
            default: return actionType;
        }
    }
}