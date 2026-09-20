package com.heima.apis.article;

import com.heima.apis.article.fallback.ILevelClientFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(value = "leadnews-content", contextId = "leadnews-content-levelClient", fallback = ILevelClientFallback.class)
public interface ILevelClient {

    @GetMapping("/api/v1/level/user/{userId}/info")
    Map<String, Object> getUserLevelInfo(@PathVariable("userId") Long userId);

    @GetMapping("/api/v1/level/user/{userId}/data")
    Map<String, Object> getUserLevelData(@PathVariable("userId") Long userId);
}