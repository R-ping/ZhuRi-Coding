package com.heima.apis.article.fallback;

import com.heima.apis.article.ILevelClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class ILevelClientFallback implements ILevelClient {

    @Override
    public Map<String, Object> getUserLevelInfo(Long userId) {
        log.error("等级服务不可用，userId={}", userId);
        throw new RuntimeException("等级服务不可用, userId=" + userId);
    }

    @Override
    public Map<String, Object> getUserLevelData(Long userId) {
        log.error("等级服务不可用，userId={}", userId);
        return new HashMap<>();
    }
}