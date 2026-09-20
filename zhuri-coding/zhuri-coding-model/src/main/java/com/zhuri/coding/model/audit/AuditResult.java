package com.heima.model.audit;

/**
 * 审核结果
 */
public class AuditResult {

    /** 是否通过 */
    private boolean passed;

    /** 是否违规 */
    private boolean isViolation;

    /** 违规类型 */
    private String violationType;

    /** 违规原因 */
    private String reason;

    /** 审核详情 */
    private String detail;

    public AuditResult() {}

    public static AuditResult passed() {
        AuditResult result = new AuditResult();
        result.passed = true;
        result.isViolation = false;
        return result;
    }

    public static AuditResult passed(String detail) {
        AuditResult result = passed();
        result.detail = detail;
        return result;
    }

    public static AuditResult failed(String reason) {
        AuditResult result = new AuditResult();
        result.passed = false;
        result.isViolation = true;
        result.reason = reason;
        return result;
    }

    public static AuditResult failed(String violationType, String reason) {
        AuditResult result = failed(reason);
        result.violationType = violationType;
        return result;
    }

    // ==================== Getters & Setters ====================

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public boolean isViolation() {
        return isViolation;
    }

    public void setViolation(boolean violation) {
        isViolation = violation;
    }

    public String getViolationType() {
        return violationType;
    }

    public void setViolationType(String violationType) {
        this.violationType = violationType;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}