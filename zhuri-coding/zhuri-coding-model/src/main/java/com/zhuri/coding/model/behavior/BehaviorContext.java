package com.heima.model.behavior;

import java.util.HashMap;
import java.util.Map;

/**
 * 行为上下文 - 承载一次用户行为的所有数据
 */
public class BehaviorContext {

    /** 行为类型 */
    private BehaviorType behaviorType;

    /** 操作用户ID */
    private Integer userId;

    /** 操作用户昵称 */
    private String userName;

    /** 操作用户头像 */
    private String userAvatar;

    /** 目标类型: 1-文章, 2-沸点, 3-用户, 4-课程, 5-专栏 */
    private Integer targetType;

    /** 目标ID */
    private Long targetId;

    /** 目标作者/被关注用户ID */
    private Integer targetUserId;

    /** 额外数据（用于传递特定行为所需的额外参数） */
    private Map<String, Object> extra = new HashMap<>();

    /** 扩展属性（后置处理器之间传递数据） */
    private final Map<String, Object> attributes = new HashMap<>();

    public BehaviorContext() {}

    public BehaviorContext(BehaviorType behaviorType, Integer userId) {
        this.behaviorType = behaviorType;
        this.userId = userId;
    }

    // ==================== 链式设置 ====================

    public BehaviorContext withTarget(Integer targetType, Long targetId) {
        this.targetType = targetType;
        this.targetId = targetId;
        return this;
    }

    public BehaviorContext withTargetUser(Integer targetUserId) {
        this.targetUserId = targetUserId;
        return this;
    }

    public BehaviorContext withUserInfo(String userName, String userAvatar) {
        this.userName = userName;
        this.userAvatar = userAvatar;
        return this;
    }

    public BehaviorContext withExtra(String key, Object value) {
        this.extra.put(key, value);
        return this;
    }

    public BehaviorContext withExtra(Map<String, Object> extra) {
        this.extra.putAll(extra);
        return this;
    }

    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    // ==================== Getters & Setters ====================

    public BehaviorType getBehaviorType() {
        return behaviorType;
    }

    public void setBehaviorType(BehaviorType behaviorType) {
        this.behaviorType = behaviorType;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserAvatar() {
        return userAvatar;
    }

    public void setUserAvatar(String userAvatar) {
        this.userAvatar = userAvatar;
    }

    public Integer getTargetType() {
        return targetType;
    }

    public void setTargetType(Integer targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Integer getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(Integer targetUserId) {
        this.targetUserId = targetUserId;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * 获取额外参数中的字符串值
     */
    public String getExtraString(String key) {
        Object val = extra.get(key);
        return val != null ? val.toString() : null;
    }

    /**
     * 获取额外参数中的整数值
     */
    public Integer getExtraInt(String key) {
        Object val = extra.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return null;
    }

    /**
     * 获取额外参数中的长整数值
     */
    public Long getExtraLong(String key) {
        Object val = extra.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return null;
    }

    @Override
    public String toString() {
        return "BehaviorContext{" +
            "behaviorType=" + (behaviorType != null ? behaviorType.getCode() : null) +
            ", userId=" + userId +
            ", targetType=" + targetType +
            ", targetId=" + targetId +
            ", targetUserId=" + targetUserId +
            '}';
    }
}