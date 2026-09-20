package com.zhuri.coding.apis.search;

import com.zhuri.coding.apis.search.fallback.IContentSearchClientFallback;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.UserSearchDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 内容服务搜索客户端（课程/标签）。
 *
 * <p>供 search 服务统一搜索聚合时经 Feign 远程调用 content 服务对应内部接口。</p>
 */
@FeignClient(value = "zhuri-coding-content", contextId = "zhuri-coding-content-searchClient", fallback = IContentSearchClientFallback.class)
public interface IContentSearchClient {

    /** 课程搜索（仅返回已上架课程） */
    @PostMapping("/api/v1/search/course")
    ResponseResult searchCourse(@RequestBody UserSearchDto dto);

    /** 标签搜索（仅返回启用标签） */
    @PostMapping("/api/v1/search/tag")
    ResponseResult searchTag(@RequestBody UserSearchDto dto);
}