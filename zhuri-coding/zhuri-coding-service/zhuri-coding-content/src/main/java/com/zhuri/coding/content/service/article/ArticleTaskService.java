package com.heima.content.service.article;

import java.util.Date;

public interface ArticleTaskService {

    /**
     * 添加文章到延迟发布队列
     * @param articleId 文章ID
     * @param publishTime 发布时间
     */
    void addArticleToTask(Long articleId, Date publishTime);

    /**
     * 发布文章（由调度任务调用）
     * @param articleId 文章ID
     */
    void publishArticle(Long articleId);
}