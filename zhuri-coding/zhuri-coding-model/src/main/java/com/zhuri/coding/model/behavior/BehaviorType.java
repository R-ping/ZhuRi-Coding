package com.heima.model.behavior;

/**
 * 用户行为类型枚举
 */
public enum BehaviorType {

    // ==================== 文章相关 ====================
    LIKE_ARTICLE("like_article", "点赞文章", 1),
    UNLIKE_ARTICLE("unlike_article", "取消点赞文章", 1),
    COLLECT_ARTICLE("collect_article", "收藏文章", 1),
    UNCOLLECT_ARTICLE("uncollect_article", "取消收藏文章", 1),
    COMMENT_ARTICLE("comment_article", "评论文章", 1),
    BROWSE_ARTICLE("browse_article", "浏览文章", 1),

    // ==================== 沸点相关 ====================
    LIKE_PIN("like_pin", "点赞沸点", 2),
    UNLIKE_PIN("unlike_pin", "取消点赞沸点", 2),
    COMMENT_PIN("comment_pin", "评论沸点", 2),
    BROWSE_PIN("browse_pin", "浏览沸点", 2),

    // ==================== 用户相关 ====================
    FOLLOW_USER("follow_user", "关注用户", 3),
    UNFOLLOW_USER("unfollow_user", "取消关注用户", 3),

    // ==================== 课程相关 ====================
    BROWSE_COURSE("browse_course", "浏览课程", 4),

    // ==================== 其他 ====================
    SHARE("share", "分享", 0),
    PUBLISH_ARTICLE("publish_article", "发布文章", 1),
    PUBLISH_PIN("publish_pins", "发布沸点", 2);

    private final String code;
    private final String description;
    private final Integer targetType; // 目标类型: 1-文章, 2-沸点, 3-用户, 4-课程, 0-其他

    BehaviorType(String code, String description, Integer targetType) {
        this.code = code;
        this.description = description;
        this.targetType = targetType;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Integer getTargetType() {
        return targetType;
    }

    public static BehaviorType fromCode(String code) {
        for (BehaviorType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }

    public boolean isLikeBehavior() {
        return this == LIKE_ARTICLE || this == LIKE_PIN;
    }

    public boolean isUnlikeBehavior() {
        return this == UNLIKE_ARTICLE || this == UNLIKE_PIN;
    }

    public boolean isCollectBehavior() {
        return this == COLLECT_ARTICLE;
    }

    public boolean isUncollectBehavior() {
        return this == UNCOLLECT_ARTICLE;
    }

    public boolean isFollowBehavior() {
        return this == FOLLOW_USER;
    }

    public boolean isUnfollowBehavior() {
        return this == UNFOLLOW_USER;
    }

    public boolean isCommentBehavior() {
        return this == COMMENT_ARTICLE || this == COMMENT_PIN;
    }

    public boolean isBrowseBehavior() {
        return this == BROWSE_ARTICLE || this == BROWSE_PIN || this == BROWSE_COURSE;
    }

    public boolean isActiveBehavior() {
        // 主动行为（产生积分、通知等）
        return this == LIKE_ARTICLE || this == COLLECT_ARTICLE || this == COMMENT_ARTICLE
            || this == FOLLOW_USER || this == SHARE || this == PUBLISH_ARTICLE || this == PUBLISH_PIN
            || this == LIKE_PIN || this == COMMENT_PIN;
    }

    public boolean isPassiveBehavior() {
        // 被动行为（不产生积分，但影响影响力）
        return this == BROWSE_ARTICLE || this == BROWSE_PIN || this == BROWSE_COURSE;
    }

    public boolean isUndoBehavior() {
        // 撤销行为
        return this == UNLIKE_ARTICLE || this == UNLIKE_PIN
            || this == UNCOLLECT_ARTICLE || this == UNFOLLOW_USER;
    }
}