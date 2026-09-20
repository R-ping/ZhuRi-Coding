package com.zhuri.coding.apis.user.fallback;

import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IUserClientFallback implements FallbackFactory<IUserClient> {
    @Override
    public IUserClient create(Throwable cause) {
        return new IUserClient() {
            @Override
            public ResponseResult getBasicInfo(Long userId) {
                log.error("IUserClient.getBasicInfo fallback, userId={}, error: {}", userId, cause.getMessage());
                return ResponseResult.errorResult(500, "用户服务不可用");
            }

            @Override
            public ResponseResult getPublicInfo(Long userId) {
                log.error("IUserClient.getPublicInfo fallback, userId={}, error: {}", userId, cause.getMessage());
                return ResponseResult.errorResult(500, "用户服务不可用");
            }
        };
    }
}