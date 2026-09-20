package com.heima.content.service.level.impl;

import static com.heima.content.constants.LevelScoreConstants.ACTION_SCORE_MAP;
import static com.heima.content.constants.LevelScoreConstants.DAILY_ACTION_LIMIT;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.level.ApBehaviorConfigMapper;
import com.heima.content.mapper.level.ApUserDailyProgressMapper;
import com.heima.content.mapper.level.ApUserLevelMapper;
import com.heima.content.mapper.pins.ApUserActionLogMapper;
import com.heima.content.mapper.user.UserScoreDetailsMapper;
import com.heima.content.mapper.user.UserScoreSummaryMapper;
import com.heima.content.service.level.LevelPermissionService;
import com.heima.model.level.pojos.ApBehaviorConfig;
import com.heima.model.level.pojos.ApUserDailyProgress;
import com.heima.model.level.pojos.ApUserLevel;
import com.heima.model.user.pojos.ApUserActionLog;
import com.heima.model.user.pojos.UserScoreDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行为记录与积分服务 — 负责用户行为记录、逐日分计算、签到
 * <p>
 * 规则约定（与掘金一致）：
 * 1. 单行为分值/每日次数上限以 ap_behavior_config（action_code/daily_limit/score）为准，常量表仅兜底；
 * 2. 不存在"每日掘友分总量 200 上限"，行为只要未达到自身每日次数上限即正常加分；
 * 3. 加分的同时同步写掘友分明细（user_score_details）与按日汇总（user_score_summary），供明细页展示。
 */
@Slf4j
@Service
public class LevelActionService {

    /** 分组类型 → 掘友分明细分类编号（1基础 2活跃 3学习 4影响力 5专项/创作） */
    private static final Map<String, Integer> GROUP_CATEGORY_MAP = new HashMap<>();
    static {
        GROUP_CATEGORY_MAP.put("社区基础", 1);
        GROUP_CATEGORY_MAP.put("社区活跃", 2);
        GROUP_CATEGORY_MAP.put("社区学习", 3);
        GROUP_CATEGORY_MAP.put("社区影响力", 4);
        GROUP_CATEGORY_MAP.put("内容创作", 5);
    }

    /** 无行为配置兜底：actionType → 明细分类编号 */
    private static final Map<String, Integer> FALLBACK_CATEGORY_MAP = new HashMap<>();
    static {
        FALLBACK_CATEGORY_MAP.put("upload_avatar", 1);
        FALLBACK_CATEGORY_MAP.put("daily_login", 2);
        FALLBACK_CATEGORY_MAP.put("daily_checkin", 2);
        FALLBACK_CATEGORY_MAP.put("share", 2);
        FALLBACK_CATEGORY_MAP.put("reward_article", 5);
        FALLBACK_CATEGORY_MAP.put("purchase_course", 5);
    }

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

    @Autowired
    private UserScoreDetailsMapper userScoreDetailsMapper;

    @Autowired
    private UserScoreSummaryMapper userScoreSummaryMapper;

    /**
     * 记录行为（默认行为，无限制校验）
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordAction(Long userId, String actionType, String actionDetail) {
        BigDecimal score = resolveScore(actionType);
        if (score == null || score.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        ApUserLevel userLevel = levelQueryService.getUserLevel(userId);
        userLevel = lockUserLevel(userId, userLevel);
        grantScore(userLevel, userId, actionType, score, actionDetail);
    }

    /**
     * 记录行为（含限制校验，返回结果）— 分值来自 ap_behavior_config（缺失时用常量兜底）
     * <p>
     * 事务说明：@Transactional 必须放在本 public 入口（外部 Bean 唯一可见方法），
     * 保证「次数上限校验 + 悲观行锁 + 多表加分落库」在同一事务内串行执行；
     * 不可放在下方 4 参 protected 重载上 —— 同 Class 内 this 自调用不经过 Spring AOP 代理，
     * 注解不生效会导致 FOR UPDATE 行锁在 autocommit 下立即释放、多表写入无原子性（TOCTOU 刷分）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> recordActionWithLimit(Long userId, String actionType, String actionDetail) {
        BigDecimal score = resolveScore(actionType);
        if (score == null || score.compareTo(BigDecimal.ZERO) <= 0) {
            return buildFailResult("无效的行为类型");
        }
        return recordActionWithLimitInternal(userId, actionType, score, actionDetail);
    }

    /**
     * 回退一次已累计的用户主动行为（取消点赞/收藏/关注等切换式互动）：
     * 扣减今日 ap_user_daily_progress 计数与当日逐日分，并在分值回退后重算逐日等级。
     * <p>
     * 语义：允许用户重复点赞；每次取消点赞时把该次获得的进度与积分一并回退，
     * 从而反复"点赞→取消"不会虚增今日进度（每次净贡献为 0）。
     * 事务与悲观行锁同 recordActionWithLimit，保证"回退校验 + 扣减+落库"原子串行。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rollbackActionWithLimit(Long userId, String actionType, String actionDetail) {
        BigDecimal score = resolveScore(actionType);
        if (score == null || score.compareTo(BigDecimal.ZERO) <= 0) {
            return buildFailResult("无效的行为类型");
        }
        Map<String, Object> result = new HashMap<>();

        ApUserLevel userLevel = levelQueryService.getUserLevel(userId);
        userLevel = lockUserLevel(userId, userLevel);

        // 1. 扣减今日该行为进度（ap_user_daily_progress）
        decrementDailyProgress(userId, actionType);

        // 2. 回退今日逐日分（当日与累计均不低于 0）
        BigDecimal curToday = userLevel.getDailyScoreToday() != null ? userLevel.getDailyScoreToday() : BigDecimal.ZERO;
        BigDecimal curTotal = userLevel.getDailyScore() != null ? userLevel.getDailyScore() : BigDecimal.ZERO;
        BigDecimal newToday = curToday.subtract(score).max(BigDecimal.ZERO);
        BigDecimal newTotal = curTotal.subtract(score).max(BigDecimal.ZERO);
        userLevel.setDailyScoreToday(newToday);
        userLevel.setDailyScore(newTotal);

        // 3. 回退后重算逐日等级（仅调整等级字段；权益/钻石只增不退，属轻量降级）
        int newDailyLevel = levelQueryService.calculateLevel(1, newTotal);
        if (newDailyLevel != userLevel.getDailyLevel()) {
            log.info("回退行为{}后用户{}逐日等级由{}调整为{}", actionType, userId,
                userLevel.getDailyLevel(), newDailyLevel);
            userLevel.setDailyLevel(newDailyLevel);
        }

        userLevelMapper.updateById(userLevel);

        result.put("success", true);
        result.put("message", "取消行为成功，已回退进度与积分");
        result.put("score", score);
        return result;
    }

    /**
     * 支付行为：按实际支付金额加逐日经验（金额即经验值，支持小数），仅受支付行为每日次数上限约束
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> recordPaymentAction(Long userId, String actionType, BigDecimal amount,
        String actionDetail) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return buildFailResult("无效的支付金额");
        }
        return recordActionWithLimitInternal(userId, actionType, amount, actionDetail);
    }

    /**
     * 记录行为（含限制校验，返回结果）— score 为本次期望获得的经验值。
     * 私有内部实现：事务由外部 public 入口方法开启（同类自调用无代理，此处不再标注 @Transactional）。
     */
    private Map<String, Object> recordActionWithLimitInternal(Long userId, String actionType, BigDecimal score,
        String actionDetail) {
        Map<String, Object> result = new HashMap<>();

        ApUserLevel userLevel = levelQueryService.getUserLevel(userId);
        userLevel = lockUserLevel(userId, userLevel);

        String today = new java.sql.Date(System.currentTimeMillis()).toString();

        // 单行为每日次数上限（以 ap_behavior_config.daily_limit 为准，-1/缺失表示不限）
        Integer dailyLimit = resolveDailyLimit(actionType);
        if (dailyLimit != null && dailyLimit > 0
            && getTodayActionCount(userId, actionType, today) >= dailyLimit) {
            return buildFailResult("今日该行为已达上限");
        }

        grantScore(userLevel, userId, actionType, score, actionDetail);

        result.put("success", true);
        result.put("message", "行为记录成功");
        result.put("score", score);
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
     * 获取今日任务进度
     */
    public Map<String, Object> getTodayTaskProgress(Long userId) {
        return taskProgressBuilder.buildTaskProgress(userId);
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
     * 记录被动行为每日进度（不发积分、不写行为日志、不写掘友分明细）
     * 用于"社区影响力"被动行为（be_followed/pin_liked/article_liked）的进度统计
     */
    public void recordPassiveAction(Long userId, String actionType) {
        upsertDailyProgress(userId, actionType);
    }

    /**
     * 悲观行锁：按用户串行化"上限校验 + 加分落库"，防止并发下 TOCTOU 超上限刷分与重复签到。
     * <p>
     * 必须在事务内调用（三个入口方法均已加 @Transactional）。调用前先 getUserLevel 保证记录已存在，
     * 因此本方法返回的非空锁定实例覆盖原实例继续使用。
     * </p>
     */
    private ApUserLevel lockUserLevel(Long userId, ApUserLevel userLevel) {
        ApUserLevel locked = userLevelMapper.selectByUserIdForUpdate(userId);
        return locked != null ? locked : userLevel;
    }

    /**
     * 核心加分：写行为日志、写掘友分明细/汇总、更新逐日经验与等级，升级时发权限与钻石
     *
     * @param userLevel    用户等级记录（可变，内部累加后落库）
     * @param userId       用户ID
     * @param actionType   行为类型
     * @param actualScore  实际获得的经验值
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

        // 掘友分明细 + 按日汇总（辅助表写入失败仅告警，不影响主流程）
        try {
            writeJScoreRecord(userId, actionType, actualScore, actionDetail);
        } catch (Exception e) {
            log.warn("写入掘友分明细/汇总失败: userId={}, actionType={}, score={}", userId, actionType, actualScore, e);
        }

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
     * 加分同步写入掘友分明细表（user_score_details）与按日汇总表（user_score_summary）
     */
    private void writeJScoreRecord(Long userId, String actionType, BigDecimal score, String actionDetail) {
        Integer category = resolveCategory(actionType);
        if (category == null) {
            return;
        }
        String actionCode = normalizeActionCode(actionType);
        if (actionCode == null) {
            actionCode = actionType;
        }

        UserScoreDetails detail = new UserScoreDetails();
        detail.setUserId(userId);
        detail.setCategory(category);
        detail.setActionCode(actionCode);
        detail.setActionDesc(actionDetail);
        detail.setScore(score);
        detail.setCreatedAt(new Date());
        userScoreDetailsMapper.insert(detail);

        // 汇总：当日不存在则插入，存在则累加（原子 ON DUPLICATE KEY UPDATE）
        java.sql.Date statDate = new java.sql.Date(System.currentTimeMillis());
        BigDecimal basic = BigDecimal.ZERO, active = BigDecimal.ZERO, learn = BigDecimal.ZERO,
            effect = BigDecimal.ZERO, spec = BigDecimal.ZERO;
        switch (category) {
            case 1: basic = score; break;
            case 2: active = score; break;
            case 3: learn = score; break;
            case 4: effect = score; break;
            case 5: spec = score; break;
            default: break;
        }
        userScoreSummaryMapper.upsertDailyScore(userId, statDate, score, basic, active, learn, effect, spec);
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
     * 扣减今日某行为进度（ap_user_daily_progress）1 次，用于取消行为时的进度回退。
     * 当日无记录或计数已为 0 时静默跳过，失败不影响主流程。
     */
    private void decrementDailyProgress(Long userId, String actionType) {
        try {
            String actionCode = normalizeActionCode(actionType);
            if (actionCode == null) {
                return;
            }
            java.sql.Date today = new java.sql.Date(System.currentTimeMillis());
            LambdaQueryWrapper<ApUserDailyProgress> progressQuery = new LambdaQueryWrapper<>();
            progressQuery.eq(ApUserDailyProgress::getUserId, userId);
            progressQuery.eq(ApUserDailyProgress::getStatDate, today);
            progressQuery.eq(ApUserDailyProgress::getActionCode, actionCode);
            ApUserDailyProgress progress = userDailyProgressMapper.selectOne(progressQuery);
            if (progress != null && progress.getCount() != null && progress.getCount() > 0) {
                progress.setCount(progress.getCount() - 1);
                progress.setUpdatedTime(new Date());
                userDailyProgressMapper.updateById(progress);
                log.debug("用户{}行为{}今日进度-1（当前{}）", userId, actionCode, progress.getCount());
            }
        } catch (Exception e) {
            log.warn("扣减用户每日行为进度失败: userId={}, actionType={}", userId, actionType, e);
        }
    }

    /**
     * 解析行为分值：优先 ap_behavior_config.score，缺失时用常量表兜底。
     * browse_course 等先做编码归一化再查配置，保证浏览课程与浏览文章同一任务。
     */
    private BigDecimal resolveScore(String actionType) {
        ApBehaviorConfig config = loadBehaviorConfig(actionType);
        if (config != null && config.getScore() != null && config.getScore().compareTo(BigDecimal.ZERO) > 0) {
            return config.getScore();
        }
        Integer fallback = ACTION_SCORE_MAP.get(actionType);
        return fallback == null ? BigDecimal.ZERO : BigDecimal.valueOf(fallback);
    }

    /**
     * 解析单行为每日次数上限：优先 ap_behavior_config.daily_limit（-1/空=不限），缺失时用常量表兜底。
     */
    private Integer resolveDailyLimit(String actionType) {
        ApBehaviorConfig config = loadBehaviorConfig(actionType);
        if (config != null && config.getDailyLimit() != null) {
            return config.getDailyLimit() < 0 ? null : config.getDailyLimit();
        }
        return DAILY_ACTION_LIMIT.get(actionType);
    }

    /**
     * 加载行为配置（按归一化后的 action_code、启用状态）。
     * <p>
     * 行为配置表 ap_behavior_config 量小且变更低频，用本地缓存（TTL 5 分钟，与 LevelQueryService 等级配置缓存同款模式）
     * 避免单次加分行为内 resolveScore / resolveDailyLimit / resolveCategory 三处重复查库。
     */
    private static final long BEHAVIOR_CONFIG_CACHE_TTL_MS = 5 * 60 * 1000L;
    private final Map<String, BehaviorConfigCacheEntry> behaviorConfigCache = new ConcurrentHashMap<>();

    private static class BehaviorConfigCacheEntry {
        final ApBehaviorConfig config;
        final long expireAt;
        BehaviorConfigCacheEntry(ApBehaviorConfig config) {
            this.config = config;
            this.expireAt = System.currentTimeMillis() + BEHAVIOR_CONFIG_CACHE_TTL_MS;
        }
    }

    private ApBehaviorConfig loadBehaviorConfig(String actionType) {
        String actionCode = normalizeActionCode(actionType);
        if (actionCode == null) {
            return null;
        }
        // 命中且未过期 → 直接返回（含"已确认不存在"的 null 缓存，防穿透）
        BehaviorConfigCacheEntry entry = behaviorConfigCache.get(actionCode);
        if (entry != null && entry.expireAt > System.currentTimeMillis()) {
            return entry.config;
        }
        ApBehaviorConfig config = null;
        try {
            LambdaQueryWrapper<ApBehaviorConfig> configQuery = new LambdaQueryWrapper<>();
            configQuery.eq(ApBehaviorConfig::getActionCode, actionCode);
            configQuery.eq(ApBehaviorConfig::getIsActive, 1);
            config = behaviorConfigMapper.selectOne(configQuery);
        } catch (Exception e) {
            log.warn("查询行为配置失败: actionType={}", actionType, e);
        }
        // 无论命中与否都缓存（过期/未命中均重查），防热点行为重复打库
        behaviorConfigCache.put(actionCode, new BehaviorConfigCacheEntry(config));
        return config;
    }

    /**
     * 行为 → 掘友分明细分类编号：先按配置分组映射，未配置行为走兜底表，仍无则归为"活跃"
     */
    private Integer resolveCategory(String actionType) {
        ApBehaviorConfig config = loadBehaviorConfig(actionType);
        if (config != null && config.getGroupType() != null && GROUP_CATEGORY_MAP.containsKey(config.getGroupType())) {
            return GROUP_CATEGORY_MAP.get(config.getGroupType());
        }
        Integer fallback = FALLBACK_CATEGORY_MAP.get(actionType);
        if (fallback != null) {
            return fallback;
        }
        return 2;
    }

    /**
     * 将行为编码归一化为 ap_behavior_config.action_code
     * <p>主码（like_article/follow_user/publish_pin 等）已统一走 {@code LevelScoreActionCode}，此处仅保留
     * 跨任务的归一（浏览课程并入浏览文章）。</p>
     */
    private String normalizeActionCode(String actionType) {
        if (actionType == null) {
            return null;
        }
        switch (actionType) {
            case "browse_course": return "browse_article";
            default: return actionType;
        }
    }
}
