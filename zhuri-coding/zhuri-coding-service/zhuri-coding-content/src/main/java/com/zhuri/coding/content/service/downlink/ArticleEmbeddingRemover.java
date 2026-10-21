package com.zhuri.coding.content.service.downlink;

import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 把「清掉下线内容的向量」这件事做成可靠投递。与 {@link ArticleIndexRemover} 对称：
 * 索引清理送达 search 服务，向量清理送达向量库（PG）。
 *
 * <p><b>为什么向量清理也要走本地消息表</b>：向量库里留着一篇已下线文章的切片，
 * RAG 就可能把它召回并写进回答里 —— 内容下线了但 AI 还在引用它，是比"搜索能搜到"
 * 更难被发现的一类泄漏。而向量删除没有对账兜底（{@code cleanupOrphanEmbeddings} 是
 * 顺带跑的残留清理，不是"下线后必达"的闭环），所以失败必须能重试，而不是记条日志了事。
 *
 * <p><b>为什么一次删两张表</b>：文章级向量（{@code ap_article_embedding}）供整篇召回，
 * 分块向量（{@code ap_article_chunk}）供片段召回，检索优先走分块。只清一张，
 * 另一条召回路径仍然能命中这篇已下线的内容。
 *
 * <p>方法体可安全重试：两条 DELETE 都以 {@code article_id} 为条件，删两次与删一次结果相同。
 */
@Slf4j
@Component
public class ArticleEmbeddingRemover {

    /** 事件类型：既是 Outbox 路由键，也是指标维度 */
    public static final String EVENT_TYPE = "ARTICLE_EMBEDDING_REMOVE";

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    /**
     * 清掉一篇文章的向量与分块（下线副作用）。
     *
     * <p><b>语义</b>：与索引清理一致——事务内调用只是登记，提交后重放才真正执行；
     * 必须经由注入的引用调用，同类 {@code this.} 调用不会存档。
     *
     * @param articleId 文章ID
     */
    @LocalMessage(eventType = EVENT_TYPE, key = "'article_embedding_remove:' + #a0")
    public void remove(Long articleId) {
        embeddingService.deleteEmbedding(articleId);
        embeddingService.deleteChunks(articleId);
        log.info("[ContentOffline] 已清理文章向量与分块, articleId={}", articleId);
    }
}
