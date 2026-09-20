package com.zhuri.coding.content.controller.v1;

import com.zhuri.coding.content.service.search.SemanticSearchService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.SemanticSearchDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语义搜索端点（向量化增强，内部服务调用）
 *
 * <p>供 search 服务经 ISemanticSearchClient Feign 调用：BM25 关键词命中不足时，
 * 由 content 向量库（pgvector）做语义召回兜底。公开只读，IP 限频防刷向量成本。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
public class SemanticSearchController {

    @Autowired
    private SemanticSearchService semanticSearchService;

    @PostMapping("/semantic")
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.IP,
        count = 60, interval = 1, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult semanticSearch(@RequestBody SemanticSearchDto dto) {
        return semanticSearchService.semanticSearch(dto);
    }
}
