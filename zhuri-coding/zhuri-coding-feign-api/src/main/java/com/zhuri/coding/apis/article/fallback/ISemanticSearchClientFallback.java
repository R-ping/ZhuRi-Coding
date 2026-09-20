package com.heima.apis.article.fallback;

import com.heima.apis.article.ISemanticSearchClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.SemanticSearchDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 语义检索客户端降级：content 不可用时返回空（搜索主链路仅剩关键词结果，不阻断）
 */
@Slf4j
@Component
public class ISemanticSearchClientFallback implements ISemanticSearchClient {

    @Override
    public ResponseResult semanticSearch(SemanticSearchDto dto) {
        log.warn("[SemanticSearch] content 语义检索不可用，降级为纯关键词搜索, words={}",
            dto == null ? null : dto.getSearchWords());
        return ResponseResult.errorResult(503, "语义检索暂不可用");
    }
}
