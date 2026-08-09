package com.heima.content.service.article.processor;

import com.heima.model.article.pojos.ApArticle;

/**
 * 文章审核处理器接口 - 责任链模式
 * 每个处理器负责审核流程中的一个独立环节
 */
public interface ArticleAuditProcessor {

    /**
     * 执行审核环节
     * @param article 文章实体
     * @param content 文章内容
     * @param context 审核上下文，用于在处理器间传递数据
     * @return true=继续执行后续处理器，false=终止审核流程
     */
    boolean process(ApArticle article, String content, AuditProcessorContext context);
}