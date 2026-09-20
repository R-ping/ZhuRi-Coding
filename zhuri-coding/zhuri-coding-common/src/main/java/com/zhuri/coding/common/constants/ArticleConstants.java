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
    /** 1小时（毫秒） */
    public static final long DELAY_1_HOUR_MS = 60 * 60 * 1000L;

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

    // ========== 文章事件状态（article_event.status 单一状态机） ==========
    /** 事件已落消息待处理（初始态） */
    public static final byte EVENT_STATUS_INIT = 1;
    /** DB 文章可见态(PUBLISHED)置位失败，待扫描重试（幂等自愈，不进死信） */
    public static final byte EVENT_STATUS_DB_SET_FAIL = 2;
    /** 文章已发布但 ES 同步失败，待扫描重试（累计重试次数，超限死信） */
    public static final byte EVENT_STATUS_ES_SYNC_FAIL = 3;
    /** 全部完成，可删除 */
    public static final byte EVENT_STATUS_DONE = 4;
    /** INIT 态滞留判定阈值（毫秒）：消费线程崩溃后由扫描重放整段流程 */
    public static final long EVENT_INIT_STALE_MS = 60_000L;
    /** ES 同步最大重试次数（原 DB 默认 2 过紧，放宽到 5） */
    public static final byte EVENT_ES_MAX_RETRY = 5;

    // ========== 审核记录 ==========
    /** 审核通过状态码 */
    public static final int AUDIT_STATUS_PASS = 1;

    /** 审核失败状态码 */
    public static final int AUDIT_STATUS_FAIL = 2;

    // ========== 通知类型 ==========
    /** 系统通知类型 */
    public static final int NOTIFICATION_TYPE_SYSTEM = 4;

    // ========== OSS 默认头像 URL ==========
    /** OSS 默认头像 URL 数组（avatar/ 目录已设置为公共读） */
    public static final String[] OSS_AVATAR_URLS = {
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_1.png",
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_2.png",
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_3.png",
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_4.png",
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_5.png",
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_6.png",
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_7.png",
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_8.png",
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_9.png",
        "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_10.png"
    };
}