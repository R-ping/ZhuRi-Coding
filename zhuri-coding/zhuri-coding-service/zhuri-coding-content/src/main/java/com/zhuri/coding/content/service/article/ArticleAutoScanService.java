package com.zhuri.coding.content.service.article;

import java.util.concurrent.CompletableFuture;

public interface ArticleAutoScanService {

    /**
     * 文章自动审核
     * @param articleId 文章ID
     * @return 审核结果
     */
    CompletableFuture<Boolean> autoScanArticle(Long articleId);

    /**
     * 已发布文章修订的自动审核：复用同一套审核责任链，审核通过则应用修订，驳回则放弃修订。
     *
     * @param articleId 文章ID
     * @return 审核结果
     */
    CompletableFuture<Boolean> autoScanRevision(Long articleId);
}