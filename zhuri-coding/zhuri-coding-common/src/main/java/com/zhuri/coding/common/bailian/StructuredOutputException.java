package com.zhuri.coding.common.bailian;

import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.Getter;

/**
 * LLM 结构化输出失败异常
 *
 * 由 {@link StructuredOutputInvoker} 在达到最大重试次数后统一抛出，
 * 携带业务错误码与可读错误信息。业务层捕获后按 fail-closed 处理（审核场景不放行）。
 */
@Getter
public class StructuredOutputException extends RuntimeException {

    private final AppHttpCodeEnum errorCode;

    public StructuredOutputException(AppHttpCodeEnum errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public StructuredOutputException(AppHttpCodeEnum errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}