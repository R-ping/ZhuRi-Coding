package com.heima.content.service.article.processor;

/**
 * 审核可重试异常
 *
 * <p>标识审核责任链中「辅助环节」（相似度检测、逐力值加成、行为事件等）发生的
 * 可重试业务/系统异常（如瞬时网络抖动、RAG 服务不可用、事件总线偶发失败）。</p>
 *
 * <p>由对应处理器抛出，由 {@link com.heima.content.service.article.impl.ArticleAutoScanServiceImpl}
 * 统一捕获后按阶段做有界重试；重试耗尽仍失败则走「置为审核失败 + 通知作者」的终态兜底，
 * 保证文章永远不会无限停留在「审核中」。</p>
 */
public class AuditRetryableException extends RuntimeException {

    public AuditRetryableException(String message) {
        super(message);
    }

    public AuditRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}