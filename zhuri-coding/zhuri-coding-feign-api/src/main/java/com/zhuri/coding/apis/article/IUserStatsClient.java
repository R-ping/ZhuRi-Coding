package com.zhuri.coding.apis.article;

import com.zhuri.coding.apis.article.fallback.IUserStatsClientFallback;
import com.zhuri.coding.model.user.vo.UserStatsVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户/作者内容统计 Feign 客户端（内容库聚合：文章/沸点/获赞/获阅读/粉丝/关注）。
 */
@FeignClient(value = "zhuri-coding-content", contextId = "zhuri-coding-contentUserStatsClient", fallback = IUserStatsClientFallback.class)
public interface IUserStatsClient {

    @GetMapping("/api/v1/user-stats/{userId}")
    UserStatsVO stats(@PathVariable("userId") Long userId);
}