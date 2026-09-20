package com.zhuri.coding.content.model.ai;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 通用违规内容检测结果（强类型 DTO）
 *
 * 对应违规检测单次调用的 JSON 输出结构，通过 @JSONField 映射 snake_case 字段，
 * 保证可被直接反序列化为 Java 对象。
 */
@Data
public class ViolationCheckResult {

    /** 是否违规 */
    @JSONField(name = "is_violation")
    private Boolean isViolation;

    /** 违规类型，无违规时为空字符串 */
    @JSONField(name = "violation_type")
    private String violationType;

    /** 违规原因，100 字以内，无违规时为空字符串 */
    @JSONField(name = "violation_reason")
    private String violationReason;
}