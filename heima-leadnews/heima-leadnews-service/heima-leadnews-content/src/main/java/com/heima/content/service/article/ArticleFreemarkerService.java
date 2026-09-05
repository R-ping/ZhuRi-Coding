package com.heima.content.service.article;

import com.heima.model.article.pojos.ApArticle;

public interface ArticleFreemarkerService {

    /**
     * 同步文章到 ES（单延迟方案下"到点可见"动作：ES 全文索引进索引）
     *
     * @param apArticle 文章信息（ES 所需基础字段：标题/作者/时间等）
     * @param taskId    任务ID，用于同步完成后事件链标记任务状态
     */
    void buildHTMLAndSend(ApArticle apArticle, Long taskId);
}
