package com.heima.apis.search;

import com.heima.apis.search.fallback.IUserSearchClientFallback;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.UserSearchDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 用户服务搜索客户端。
 *
 * <p>供 search 服务统一搜索聚合时经 Feign 远程调用 user 服务内部接口。</p>
 */
@FeignClient(value = "leadnews-user", contextId = "leadnews-user-searchClient", fallback = IUserSearchClientFallback.class)
public interface IUserSearchClient {

    /** 用户搜索（仅返回正常状态用户，剔除敏感字段） */
    @PostMapping("/api/v1/search/user")
    ResponseResult searchUser(@RequestBody UserSearchDto dto);
}