package com.zhuri.coding.apis.user;

import com.zhuri.coding.apis.user.fallback.IUserClientFallback;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

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

    /**
     * 批量获取用户基础信息（昵称、头像）。
     *
     * <p>给"一次要展示很多人"的列表场景用（私信会话列表、评论列表这类），
     * 避免对每个 id 各调一次形成 N+1：原来 20 个会话要打 20 次，现在 1 次。
     *
     * @param userIds 用户 ID 列表；服务端会去重并限量，超出的忽略
     * @return data 为 {@code Map<userId, {nickname, avatar}>}；查不到的用户不出现在结果里，
     *         调用方需按"缺失即空串"处理
     */
    @GetMapping("/api/v1/user/feign/basic-info/batch")
    ResponseResult getBasicInfoBatch(@RequestParam("userIds") List<Long> userIds);
}