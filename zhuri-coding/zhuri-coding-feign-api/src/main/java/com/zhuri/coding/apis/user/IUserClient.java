package com.zhuri.coding.apis.user;

import com.zhuri.coding.apis.user.fallback.IUserClientFallback;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "zhuri-coding-user", fallbackFactory = IUserClientFallback.class)
public interface IUserClient {

    /**
     * 获取用户基本信息（昵称、头像）
     */
    @GetMapping("/api/v1/user/feign/basic-info")
    ResponseResult getBasicInfo(@RequestParam("userId") Long userId);

    /**
     * 获取用户公开信息（昵称、头像、职位/公司/简介）
     */
    @GetMapping("/api/v1/user/feign/public-info")
    ResponseResult getPublicInfo(@RequestParam("userId") Long userId);
}