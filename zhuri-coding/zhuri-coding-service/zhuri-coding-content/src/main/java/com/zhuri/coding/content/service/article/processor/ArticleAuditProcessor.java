package com.zhuri.coding.content.service.article.processor;

import com.zhuri.coding.model.article.pojos.ApArticle;

/**
 * 文章审核处理器接口 - 责任链模式
 * 每个处理器负责审核流程中的一个独立环节。
 * <p>
 * 处理器由 Spring 注入为 {@code List<ArticleAuditProcessor>} 并按 {@link #getOrder()} 升序自动排序，
 * 顺序由 {@code @Order} 注解声明而非硬编码在编排方，新增环节无需改动审核主流程。
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

    /**
     * 执行顺序（数值越小越先执行），由编排方按升序调用
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 该环节失败（返回 false 或抛异常）时是否值得重试。
     *
     * <p><b>已废弃标记位</b>：自 2026-09 起编排方不再按此方法分流，重试与否改由<b>异常类型</b>驱动——
     * 处理器在「外部服务不可用等瞬时故障」时抛 {@link AuditRetryableException}，编排方统一有界重试；
     * 返回 {@code false} 一律视为业务判定不通过（真违规），直接驳回不重试。</p>
     *
     * <p>保留此方法仅为历史兼容，新处理器无需 override。</p>
     */
    default boolean isRetryable() {
        return false;
    }
}