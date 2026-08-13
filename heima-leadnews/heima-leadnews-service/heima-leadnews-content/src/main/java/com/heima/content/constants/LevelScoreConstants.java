package com.heima.content.constants;

import java.util.HashMap;
import java.util.Map;

/**
 * 等级积分常量
 */
public final class LevelScoreConstants {

    private LevelScoreConstants() {}

    /** 每日积分上限 */
    public static final int DAILY_SCORE_LIMIT = 200;

    /** 支付行为类型（按实际支付金额加分，金额即经验值，受每日积分上限控制） */
    public static final String ACTION_PURCHASE_COURSE = "purchase_course";
    public static final String ACTION_REWARD_ARTICLE = "reward_article";

    /** 行为类型 → 积分值 */
    public static final Map<String, Integer> ACTION_SCORE_MAP = new HashMap<>();
    static {
        ACTION_SCORE_MAP.put("daily_login", 2);
        ACTION_SCORE_MAP.put("article_read", 2);
        ACTION_SCORE_MAP.put("comment_article", 2);
        ACTION_SCORE_MAP.put("comment_pin", 2);
        ACTION_SCORE_MAP.put("like_article", 1);
        ACTION_SCORE_MAP.put("like_pin", 1);
        ACTION_SCORE_MAP.put("share", 3);
        ACTION_SCORE_MAP.put("follow_user", 4);
        ACTION_SCORE_MAP.put("publish_article", 8);
        ACTION_SCORE_MAP.put("publish_pins", 8);
        ACTION_SCORE_MAP.put("daily_checkin", 2);
        ACTION_SCORE_MAP.put("upload_avatar", 1);
        ACTION_SCORE_MAP.put("collect_article", 1);
        ACTION_SCORE_MAP.put("browse_article", 0);
        ACTION_SCORE_MAP.put("browse_course", 0);
    }

    /** 行为类型 → 每日次数上限 */
    public static final Map<String, Integer> DAILY_ACTION_LIMIT = new HashMap<>();
    static {
        DAILY_ACTION_LIMIT.put("publish_article", 2);
        DAILY_ACTION_LIMIT.put("publish_pins", 2);
        DAILY_ACTION_LIMIT.put("comment_article", 2);
        DAILY_ACTION_LIMIT.put("comment_pin", 5);
        DAILY_ACTION_LIMIT.put("like_article", 2);
        DAILY_ACTION_LIMIT.put("like_pin", 2);
        DAILY_ACTION_LIMIT.put("follow_user", 2);
        DAILY_ACTION_LIMIT.put("daily_checkin", 1);
        DAILY_ACTION_LIMIT.put("upload_avatar", 1);
        DAILY_ACTION_LIMIT.put("collect_article", 2);
        DAILY_ACTION_LIMIT.put("browse_article", 10);
        DAILY_ACTION_LIMIT.put("browse_course", 10);
        DAILY_ACTION_LIMIT.put("daily_login", 2);
    }

    /** 逐力变更类型 → 每日次数上限 */
    public static final Map<String, Integer> POWER_ACTION_LIMIT = new HashMap<>();
    static {
        POWER_ACTION_LIMIT.put("publish_article", 2);
    }
}