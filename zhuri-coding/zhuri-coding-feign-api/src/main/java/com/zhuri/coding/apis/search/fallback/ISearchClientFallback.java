package com.zhuri.coding.apis.search.fallback;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.Bm25RecallDto;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ISearchClientFallback implements ISearchClient {

    @Override
    public ResponseResult syncArticle(SearchArticleVo searchArticleVo) {
        Long articleId = searchArticleVo != null ? searchArticleVo.getId() : null;
        log.error("远程同步文章到ES索引异常, articleId={}", articleId);
        throw new RuntimeException("同步文章到ES索引异常, articleId=" + articleId);
    }

    /**
     * 关键词召回降级：返回空列表而非抛异常 —— 调用方（RAG 混合检索）据此自动退化为纯向量召回，
     * 保证 search 服务不可用时问答链路不受影响。
     */
    @Override
    public ResponseResult bm25Recall(Bm25RecallDto dto) {
        log.warn("[Bm25Recall] search 服务不可用，关键词召回降级为空（本次问答退化为纯向量召回）");
        return ResponseResult.okResult(new ArrayList<>());
    }

    /**
     * 对账**不做降级**：返回"没有缺失"会把 search 故障伪装成索引完全一致，
     * 巡检会安静地什么都不做。抛异常让调用方跳过本轮并留下 WARN。
     */
    @Override
    public ResponseResult missingArticleIds(List<Long> candidateIds) {
        log.warn("[IndexReconcile] search 服务不可用，跳过本轮索引对账");
        throw new RuntimeException("search 服务不可用，索引对账跳过本轮");
    }

    /**
     * 索引移除**不做降级**：与 syncArticle 同理，但后果更严重 ——
     * 返回成功会让调用方（本地消息表）把这次下架副作用标记为"已完成"，
     * 于是被下架的内容**永远留在检索结果里**且再也不会重试。
     * 抛异常让消息表按普通失败计次重试。
     */
    @Override
    public ResponseResult removeArticleIndex(Long articleId) {
        log.error("远程移除ES索引异常, articleId={}", articleId);
        throw new RuntimeException("移除ES索引异常, articleId=" + articleId);
    }
}