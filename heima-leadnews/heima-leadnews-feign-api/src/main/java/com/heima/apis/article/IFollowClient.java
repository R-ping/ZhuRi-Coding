package com.heima.apis.article;

import com.heima.apis.article.fallback.IFollowClientFallback;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "leadnews-content", contextId = "leadnews-content-followClient", fallback = IFollowClientFallback.class)
public interface IFollowClient {

    @PostMapping("/api/v1/follow/do")
    public ResponseResult follow(@RequestParam("userId") Long userId, @RequestParam("followUserId") Long followUserId);

    /**
     * 查询用户 userId 是否已关注 followUserId
     */
    @GetMapping("/api/v1/follow/isFollowing")
    public ResponseResult isFollowing(@RequestParam("userId") Long userId, @RequestParam("followUserId") Long followUserId);
}