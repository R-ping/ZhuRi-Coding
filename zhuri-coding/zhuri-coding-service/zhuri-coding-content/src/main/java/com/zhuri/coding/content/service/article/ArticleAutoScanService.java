package com.heima.content.service.article;

import java.util.concurrent.CompletableFuture;

public interface ArticleAutoScanService {

    /**
     * 文章自动审核
     * @param articleId 文章ID
     * @return 审核结果
     */
    CompletableFuture<Boolean> autoScanArticle(Long articleId);
}