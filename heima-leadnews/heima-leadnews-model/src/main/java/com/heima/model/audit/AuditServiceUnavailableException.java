package com.heima.model.audit;

/**
 * 审核服务不可用异常（fail-closed）
 *
 * AI 违规检测等审核依赖不可用时抛出，由调用方决定重试/标记待审/降级，严禁将"检测异常"降级为"通过"。
 */
public class AuditServiceUnavailableException extends RuntimeException {

    public AuditServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuditServiceUnavailableException(String message) {
        super(message);
    }
}