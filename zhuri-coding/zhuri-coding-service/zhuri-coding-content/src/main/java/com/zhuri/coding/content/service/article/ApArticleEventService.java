package com.zhuri.coding.content.service.article;

import com.zhuri.coding.model.article.pojos.ArticleEvent;

public interface ApArticleEventService  {

    void updateEvent(ArticleEvent event);

    /**
     * 执行文章发布主流程：置 DB 发布态（幂等条件更新）→ ES 同步 → 状态机收敛（DONE/DB_SET_FAIL/ES_SYNC_FAIL）。
     * 异步监听器（ArticlePublishEventListener）与 20s 补偿扫描共用同一套幂等执行体；
     * 异常向上抛出由调用方决策（监听器吞掉记日志、扫描逐条捕获），未完成事件由扫描兜底。
     *
     * @param articleId 文章ID（本地消息表锚点必须已落库）
     */
    void executePublish(Long articleId);
}