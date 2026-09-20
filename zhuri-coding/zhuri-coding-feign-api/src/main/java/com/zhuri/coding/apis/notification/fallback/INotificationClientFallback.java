package com.heima.apis.notification.fallback;

import com.heima.apis.notification.INotificationClient;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class INotificationClientFallback implements FallbackFactory<INotificationClient> {
    @Override
    public INotificationClient create(Throwable cause) {
        return new INotificationClient() {
            @Override
            public ResponseResult createNotification(Map<String, Object> params) {
                log.error("NotificationClient.createNotification fallback, error: {}", cause.getMessage());
                return ResponseResult.errorResult(500, "通知服务不可用");
            }

            @Override
            public void incrUnread(Long userId) {
                log.error("NotificationClient.incrUnread fallback, error: {}", cause.getMessage());
            }

            @Override
            public ResponseResult sendActivityNotification(Map<String, Object> params) {
                log.error("NotificationClient.sendActivityNotification fallback, error: {}", cause.getMessage());
                return ResponseResult.errorResult(500, "通知服务不可用");
            }
        };
    }
}