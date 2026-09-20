package com.heima.common.exception;

import com.heima.model.common.enums.AppHttpCodeEnum;

/**
 * 限流超出异常
 * 当请求被限流组件拒绝时抛出此异常
 */
public class RateLimitExceededException extends RuntimeException {

    private final AppHttpCodeEnum appHttpCodeEnum;

    public RateLimitExceededException(String message) {
        super(message);
        this.appHttpCodeEnum = AppHttpCodeEnum.RATE_LIMIT_EXCEEDED;
    }

    public RateLimitExceededException(AppHttpCodeEnum appHttpCodeEnum) {
        super(appHttpCodeEnum.getErrorMessage());
        this.appHttpCodeEnum = appHttpCodeEnum;
    }

    public AppHttpCodeEnum getAppHttpCodeEnum() {
        return appHttpCodeEnum;
    }
}