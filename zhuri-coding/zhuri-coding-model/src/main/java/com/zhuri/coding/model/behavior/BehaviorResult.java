package com.heima.model.behavior;

import java.util.HashMap;
import java.util.Map;

/**
 * 行为处理结果
 */
public class BehaviorResult {

    /** 是否成功 */
    private boolean success;

    /** 行为类型 */
    private BehaviorType behaviorType;

    /** 处理消息 */
    private String message;

    /** 是否是新创建的（用于区分重复操作） */
    private boolean isNewRecord;

    /** 扩展数据 */
    private Map<String, Object> data = new HashMap<>();

    public BehaviorResult() {}

    public static BehaviorResult success(BehaviorType behaviorType) {
        BehaviorResult result = new BehaviorResult();
        result.success = true;
        result.behaviorType = behaviorType;
        result.message = "操作成功";
        return result;
    }

    public static BehaviorResult success(BehaviorType behaviorType, String message) {
        BehaviorResult result = new BehaviorResult();
        result.success = true;
        result.behaviorType = behaviorType;
        result.message = message;
        return result;
    }

    public static BehaviorResult failure(BehaviorType behaviorType, String message) {
        BehaviorResult result = new BehaviorResult();
        result.success = false;
        result.behaviorType = behaviorType;
        result.message = message;
        return result;
    }

    public static BehaviorResult duplicate(BehaviorType behaviorType) {
        BehaviorResult result = new BehaviorResult();
        result.success = true;
        result.behaviorType = behaviorType;
        result.isNewRecord = false;
        result.message = "已操作过，无需重复处理";
        return result;
    }

    // ==================== 链式方法 ====================

    public BehaviorResult withNewRecord(boolean isNewRecord) {
        this.isNewRecord = isNewRecord;
        return this;
    }

    public BehaviorResult withData(String key, Object value) {
        this.data.put(key, value);
        return this;
    }

    public BehaviorResult withMessage(String message) {
        this.message = message;
        return this;
    }

    // ==================== Getters ====================

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public BehaviorType getBehaviorType() {
        return behaviorType;
    }

    public void setBehaviorType(BehaviorType behaviorType) {
        this.behaviorType = behaviorType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isNewRecord() {
        return isNewRecord;
    }

    public void setNewRecord(boolean newRecord) {
        isNewRecord = newRecord;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @SuppressWarnings("unchecked")
    public <T> T getDataValue(String key) {
        return (T) data.get(key);
    }
}