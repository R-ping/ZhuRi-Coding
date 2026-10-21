package com.zhuri.coding.search.interceptor;

import com.zhuri.coding.common.auth.InternalIdentityInterceptor;

/**
 * search 服务的内部身份拦截器。
 *
 * <p>实现已收敛到 {@link InternalIdentityInterceptor}（content / user / notification / search 共用），
 * 这里只保留类名与注入点，避免四个服务各维护一份安全关键代码。
 */
public class AppTokenInterceptor extends InternalIdentityInterceptor {

    public AppTokenInterceptor(String internalAuthSecret) {
        super(internalAuthSecret);
    }
}
