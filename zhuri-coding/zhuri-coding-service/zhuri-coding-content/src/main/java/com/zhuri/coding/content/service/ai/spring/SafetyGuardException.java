package com.zhuri.coding.content.service.ai.spring;

/**
 * 提示词安全护栏触发异常。
 *
 * <p>当 {@link PromptSafetyAdvisor} 判定 LLM 输出疑似受注入影响（命中顺从短语）时抛出，
 * 由调用方在既有降级路径（throw → catch → 兜底直答/友好提示）中处理，接口始终保持可用。
 */
public class SafetyGuardException extends RuntimeException {

    public SafetyGuardException(String message) {
        super(message);
    }

    public SafetyGuardException(String message, Throwable cause) {
        super(message, cause);
    }
}