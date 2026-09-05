package com.heima.content.constants;

/**
 * 逐日等级行为（action_code）唯一编码常量。
 *
 * <p>与 {@code ap_behavior_config.action_code} 及 {@code ap_user_daily_progress.action_code}
 * 保持一一对应，作为全服务唯一的 action-code 单一来源（Single Source of Truth）。
 * 所有要在等级体系中记分 / 累计进度的行为，统一引用本类常量，禁止直写字符串字面量，
 * 避免同名不同词形（如 publish_pin / publish_pins）导致的归一化补丁与进度统计漂移。</p>
 */
public final class LevelScoreActionCode {

    private LevelScoreActionCode() {
    }

    /** 每日登录 */
    public static final String DAILY_LOGIN = "daily_login";
    /** 每日签到 */
    public static final String DAILY_CHECKIN = "daily_checkin";
    /** 阅读文章 */
    public static final String READ_ARTICLE = "article_read";
    /** 评论文章 */
    public static final String COMMENT_ARTICLE = "comment_article";
    /** 评论沸点 */
    public static final String COMMENT_PIN = "comment_pin";
    /** 点赞文章 */
    public static final String LIKE_ARTICLE = "like_article";
    /** 点赞沸点 */
    public static final String LIKE_PIN = "like_pin";
    /** 收藏文章 */
    public static final String COLLECT_ARTICLE = "collect_article";
    /** 关注用户 */
    public static final String FOLLOW_USER = "follow_user";
    /** 分享 */
    public static final String SHARE = "share";
    /** 发布文章 */
    public static final String PUBLISH_ARTICLE = "publish_article";
    /** 发布沸点 */
    public static final String PUBLISH_PIN = "publish_pin";
    /** 上传头像 */
    public static final String UPLOAD_AVATAR = "upload_avatar";
    /** 浏览文章（含浏览课程归一） */
    public static final String BROWSE_ARTICLE = "browse_article";
    /** 购买课程 */
    public static final String PURCHASE_COURSE = "purchase_course";
    /** 打赏文章 */
    public static final String REWARD_ARTICLE = "reward_article";
}