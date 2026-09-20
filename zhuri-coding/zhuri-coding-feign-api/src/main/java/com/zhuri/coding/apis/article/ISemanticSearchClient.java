package com.heima.apis.article;

import com.heima.apis.article.fallback.ISemanticSearchClientFallback;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.SemanticSearchDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 语义检索客户端（向量化增强）
 *
 * <p>search 服务 BM25 关键词命中不足时调用 content 的向量检索端点做语义召回兜底。
 * 返回 data = List&lt;Map&lt;String,Object&gt;&gt;，字段与 ES 搜索结果 map 对齐：
 * id(String)/title/h_title/publishTime/authorId(String)/authorName/images/staticUrl。
 */
@FeignClient(value = "leadnews-content", contextId = "leadnews-content-semanticClient",
    fallback = ISemanticSearchClientFallback.class)
public interface ISemanticSearchClient {

    @PostMapping("/api/v1/search/semantic")
    ResponseResult semanticSearch(@RequestBody SemanticSearchDto dto);
}
