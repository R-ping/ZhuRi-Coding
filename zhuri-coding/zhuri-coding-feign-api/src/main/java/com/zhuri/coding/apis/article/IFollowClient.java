package com.zhuri.coding.apis.article;

import com.zhuri.coding.apis.article.fallback.IFollowClientFallback;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(value = "zhuri-coding-content", contextId = "zhuri-coding-content-followClient", fallback = IFollowClientFallback.class)
public interface IFollowClient {

    @PostMapping("/api/v1/follow/do")
    public ResponseResult follow(@RequestParam("userId") Long userId, @RequestParam("followUserId") Long followUserId);

    /**
     * 查询用户 userId 是否已关注 followUserId
     */
    @GetMapping("/api/v1/follow/isFollowing")
    public ResponseResult isFollowing(@RequestParam("userId") Long userId, @RequestParam("followUserId") Long followUserId);

    /**
     * 批量查询：这批 userIds 里，哪些人关注了 followUserId。
     *
     * <p>与 {@link #isFollowing} 的语义一致（"userId 是否已关注 followUserId"），
     * 只是把 N 次调用合成 1 次，供列表场景消除 N+1。
     *
     * @param followUserId 被关注方（固定一个）
     * @param userIds      待判定的一批用户
     * @return data 为 {@code Map<userId, Boolean>}；入参里每个 id 都有结果，查不到即 false。
     *         降级时返回错误码，调用方需按"不可用即 false"处理
     */
    @GetMapping("/api/v1/follow/isFollowing/batch")
    public ResponseResult isFollowingBatch(@RequestParam("followUserId") Long followUserId,
                                          @RequestParam("userIds") List<Long> userIds);
}