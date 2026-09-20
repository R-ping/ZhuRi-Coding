package com.zhuri.coding.content.feign;

import com.zhuri.coding.apis.article.IUserStatsClient;
import com.zhuri.coding.content.service.stats.UserContentStatsService;
import com.zhuri.coding.model.user.vo.UserStatsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户内容统计 Feign 实现（内容服务侧，供用户服务等跨服务聚合调用）。
 */
@RestController
public class UserStatsFeignClient implements IUserStatsClient {

    @Autowired
    private UserContentStatsService userContentStatsService;

    @GetMapping("/api/v1/user-stats/{userId}")
    @Override
    public UserStatsVO stats(@PathVariable("userId") Long userId) {
        return userContentStatsService.stats(userId);
    }
}