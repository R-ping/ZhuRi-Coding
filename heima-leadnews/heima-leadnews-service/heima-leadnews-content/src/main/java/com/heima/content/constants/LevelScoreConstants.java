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
    public static final String ACTION_PURCHASE_COURSE = LevelScoreActionCode.PURCHASE_COURSE;
    public static final String ACTION_REWARD_ARTICLE = LevelScoreActionCode.REWARD_ARTICLE;

    /** 行为类型 → 积分值（key 统一引用 LevelScoreActionCode） */
    public static final Map<String, Integer> ACTION_SCORE_MAP = new HashMap<>();
    static {
        ACTION_SCORE_MAP.put(LevelScoreActionCode.COMMENT_ARTICLE, 2);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.COMMENT_PIN, 2);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.LIKE_ARTICLE, 1);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.LIKE_PIN, 1);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.SHARE, 3);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.FOLLOW_USER, 4);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.PUBLISH_ARTICLE, 8);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.PUBLISH_PIN, 8);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.DAILY_CHECKIN, 2);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.UPLOAD_AVATAR, 1);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.COLLECT_ARTICLE, 1);
        ACTION_SCORE_MAP.put(LevelScoreActionCode.BROWSE_ARTICLE, 0);
        ACTION_SCORE_MAP.put("browse_course", 0);
    }

    /** 行为类型 → 每日次数上限（key 统一引用 LevelScoreActionCode） */
    public static final Map<String, Integer> DAILY_ACTION_LIMIT = new HashMap<>();
    static {
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.PUBLISH_ARTICLE, 2);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.PUBLISH_PIN, 2);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.COMMENT_ARTICLE, 5);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.COMMENT_PIN, 5);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.LIKE_ARTICLE, 5);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.LIKE_PIN, 5);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.FOLLOW_USER, 2);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.DAILY_CHECKIN, 1);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.UPLOAD_AVATAR, 1);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.COLLECT_ARTICLE, 2);
        DAILY_ACTION_LIMIT.put(LevelScoreActionCode.BROWSE_ARTICLE, 10);
        DAILY_ACTION_LIMIT.put("browse_course", 10);
    }

    /** 逐力变更类型 → 每日次数上限 */
    public static final Map<String, Integer> POWER_ACTION_LIMIT = new HashMap<>();
    static {
        POWER_ACTION_LIMIT.put("publish_article", 2);
    }
}