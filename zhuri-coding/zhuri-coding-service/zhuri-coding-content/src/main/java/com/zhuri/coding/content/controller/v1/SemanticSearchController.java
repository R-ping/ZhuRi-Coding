package com.heima.content.controller.v1;

import com.heima.content.service.search.SemanticSearchService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.SemanticSearchDto;
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
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 60, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult semanticSearch(@RequestBody SemanticSearchDto dto) {
        return semanticSearchService.semanticSearch(dto);
    }
}
