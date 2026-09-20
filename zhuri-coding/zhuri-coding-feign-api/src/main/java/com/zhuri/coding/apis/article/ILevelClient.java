package com.zhuri.coding.apis.article;

import com.zhuri.coding.apis.article.fallback.ILevelClientFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(value = "zhuri-coding-content", contextId = "zhuri-coding-content-levelClient", fallback = ILevelClientFallback.class)
public interface ILevelClient {

    @GetMapping("/api/v1/level/user/{userId}/info")
    Map<String, Object> getUserLevelInfo(@PathVariable("userId") Long userId);

    @GetMapping("/api/v1/level/user/{userId}/data")
    Map<String, Object> getUserLevelData(@PathVariable("userId") Long userId);
}