package com.heima.common.constants;

public class ArticleConstants {
    public static final Short LOADTYPE_LOAD_MORE = 1;
    public static final Short LOADTYPE_LOAD_NEW = 2;
    public static final String DEFAULT_TAG = "__all__";

    public static final String ARTICLE_ES_SYNC_TOPIC = "article.es.sync.topic";

    public static final short HOT_ARTICLE_LIKE_WEIGHT = 3;
    public static final short HOT_ARTICLE_COMMENT_WEIGHT = 3;
    public static final short HOT_ARTICLE_COLLECTION_WEIGHT = 6;
    /** 热度评分倍率 */
    public static final int HOT_ARTICLE_SCORE_MULTIPLIER = 3;

    // ========== 延迟发布任务时间阈值 ==========
    /** 5分钟（毫秒） */
    public static final long DELAY_5_MIN_MS = 5 * 60 * 1000L;
    /** 15分钟（毫秒） */
    public static final long DELAY_15_MIN_MS = 15 * 60 * 1000L;
    /** 2分钟（毫秒） */
    public static final long DELAY_2_MIN_MS = 2 * 60 * 1000L;
    /** 1小时（毫秒） */
    public static final long DELAY_1_HOUR_MS = 60 * 60 * 1000L;
    /** 随机延迟基础值（分钟） */
    public static final long RANDOM_DELAY_BASE_MIN = 5;
    /** 随机延迟浮动范围（分钟） */
    public static final long RANDOM_DELAY_RANGE_MIN = 5;

    // ========== AI 质量评分阈值 ==========
    /** 质量优秀阈值（含） */
    public static final int QUALITY_SCORE_EXCELLENT = 80;
    /** 质量合格阈值（含） */
    public static final int QUALITY_SCORE_PASS = 60;

    // ========== 逐力值等级 ==========
    /** 自动推荐到首页的等级阈值 */
    public static final int POWER_LEVEL_AUTO_RECOMMEND = 4;

    // ========== 逐力值加成 ==========
    /** AI质量优秀逐力值加成 */
    public static final int POWER_BONUS_EXCELLENT = 3;
    /** AI质量合格逐力值加成 */
    public static final int POWER_BONUS_PASS = 1;

    // ========== 重试间隔 ==========
    /** 任务重试间隔（毫秒） */
    public static final long RETRY_INTERVAL_MS = 5000;

    // ========== 审核记录 ==========
    /** 审核失败状态码 */
    public static final int AUDIT_STATUS_FAIL = 2;

    // ========== 通知类型 ==========
    /** 系统通知类型 */
    public static final int NOTIFICATION_TYPE_SYSTEM = 4;
}