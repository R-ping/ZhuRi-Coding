package com.heima.content.service.article;

import com.heima.model.article.pojos.ApArticle;

public interface ArticleFreemarkerService {

    /**
     * 同步文章到 ES（同步执行，单延迟方案下"到点可见"动作：ES 全文索引进索引）。
     * <p>调用前要求：本地消息表事件已落(status=INIT)、文章 DB 可见态已置 PUBLISHED。
     * 成功后置 status=DONE，失败置 status=ES_SYNC_FAIL 交由 20s 定时扫描补偿。
     *
     * @param apArticle 文章信息（ES 所需基础字段：标题/作者/时间等；status 应已是 PUBLISHED=9）
     * @param taskId    任务ID（保留参数以兼容调度链，任务消费由调用方在方法返回后统一处理）
     */
    void buildHTMLAndSend(ApArticle apArticle, Long taskId);
}
