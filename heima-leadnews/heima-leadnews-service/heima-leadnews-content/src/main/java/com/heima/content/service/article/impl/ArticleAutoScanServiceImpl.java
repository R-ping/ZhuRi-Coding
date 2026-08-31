package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ArticleAutoScanService;
import com.heima.content.service.article.ArticleTaskService;
import com.heima.content.service.article.processor.AIViolationProcessor;
import com.heima.content.service.article.processor.AuditFailProcessor;
import com.heima.content.service.article.processor.AuditProcessorContext;
import com.heima.content.service.article.processor.AuditRetryableException;
import com.heima.content.service.article.processor.BehaviorEventProcessor;
import com.heima.content.service.article.processor.ImageScanProcessor;
import com.heima.content.service.article.processor.PowerBonusProcessor;
import com.heima.content.service.article.processor.SimilarityProcessor;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleContent;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 文章自动审核服务实现
 *
 * 采用责任链模式编排审核流程，每个审核环节由独立的 Processor 处理：
 * 1. AIViolationProcessor - AI违规内容检测 + 内容质量分析
 * 2. ImageScanProcessor - 图片审核
 * 3. SimilarityProcessor - RAG相似度检验 + 推荐状态更新
 * 4. PowerBonusProcessor - 逐力值加成 + 自动推荐
 * 5. BehaviorEventProcessor - 发布行为事件
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleAutoScanServiceImpl implements ArticleAutoScanService {

    private final ApArticleMapper apArticleMapper;
    private final ApArticleContentMapper apArticleContentMapper;
    private final AIViolationProcessor aiViolationProcessor;
    private final ImageScanProcessor imageScanProcessor;
    private final SimilarityProcessor similarityProcessor;
    private final PowerBonusProcessor powerBonusProcessor;
    private final BehaviorEventProcessor behaviorEventProcessor;
    private final AuditFailProcessor auditFailProcessor;
    private final ArticleTaskService articleTaskService;

    /** 辅助环节单阶段最大重试次数（默认3次） */
    @Value("${app.audit.aux-retry-attempts:3}")
    private int auxRetryAttempts;

    /** 辅助环节重试退避间隔（毫秒，默认1000） */
    @Value("${app.audit.aux-retry-backoff-ms:1000}")
    private long auxRetryBackoffMs;

    @Override
    @Async
    public CompletableFuture<Boolean> autoScanArticle(Long articleId) {
        try {
            return doAutoScan(articleId);
        } catch (Exception e) {
            // 顶层兜底：任何环节抛出的未预期异常都不能让文章无限停留在"审核中"，
            // 统一落为终态失败（status=FAIL）并通知作者，杜绝"永远审核中"。
            log.error("文章审核出现未预期异常, articleId={}", articleId, e);
            ApArticle article = apArticleMapper.selectById(articleId);
            if (article != null) {
                auditFailProcessor.handleFail(article, AuditFailProcessor.SYSTEM_ERROR_REASON);
            }
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * 审核主流程。①违规检测/②图片审核为"正常驳回"（返回false即失败）；
     * ③④⑤辅助环节通过 {@link #performWithRetry} 做有界重试，重试耗尽抛出异常，
     * 由 {@link #autoScanArticle} 顶层兜底统一转终态失败。
     */
    private CompletableFuture<Boolean> doAutoScan(Long articleId) {
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null) {
            log.error("ArticleAutoScanServiceImpl-文章不存在, articleId={}", articleId);
            return CompletableFuture.completedFuture(false);
        }

        if (!article.getStatus().equals(Status.SUBMIT.getCode())) {
            log.info("文章状态非审核中，跳过审核, articleId={}, status={}", articleId, article.getStatus());
            return CompletableFuture.completedFuture(true);
        }

        // 获取文章内容
        QueryWrapper<ApArticleContent> contentQuery = new QueryWrapper<>();
        contentQuery.eq("article_id", articleId);
        ApArticleContent articleContent = apArticleContentMapper.selectOne(contentQuery);
        String content = articleContent != null ? articleContent.getContent() : "";

        // 初始化审核上下文
        AuditProcessorContext context = new AuditProcessorContext();

        // 1. AI违规内容检测 + 内容质量分析（正常驳回）
        if (!aiViolationProcessor.process(article, content, context)) {
            String failReason = context.getExtra("failReason");
            auditFailProcessor.handleFail(article, failReason);
            log.info("AI违规检测未通过, articleId={}, reason={}", articleId, failReason);
            return CompletableFuture.completedFuture(false);
        }

        // 2. 图片审核（正常驳回）
        if (!imageScanProcessor.process(article, content, context)) {
            String failReason = context.getExtra("failReason");
            auditFailProcessor.handleFail(article, failReason);
            log.info("图片审核未通过, articleId={}, reason={}", articleId, failReason);
            return CompletableFuture.completedFuture(false);
        }

        // 3. RAG相似度检验 + 推荐状态更新（有界重试，耗尽则终态失败）
        performWithRetry("RAG相似度检验", () -> similarityProcessor.process(article, content, context));

        // 4. 逐力值加成 + 自动推荐通知（有界重试，耗尽则终态失败）
        performWithRetry("逐力值加成", () -> powerBonusProcessor.process(article, content, context));

        log.info("文章审核完成, articleId={}, isHighSimilarity={}", articleId, context.isHighSimilarity());

        // 5. 发布行为事件（有界重试，耗尽则终态失败）
        performWithRetry("发布行为事件", () -> behaviorEventProcessor.process(article, content, context));

        // 6. 添加到调度任务
        articleTaskService.addArticleToTask(article.getId(), article.getPublishTime());
        return CompletableFuture.completedFuture(true);
    }

    /**
     * 对单个辅助环节执行有界重试。
     * <p>依赖各处理器在失败时抛出 {@link AuditRetryableException}（而非静默吞掉），
     * 重试策略：最多 {@link #auxRetryAttempts} 次、指数不足则按固定退避间隔。
     * 重试耗尽后继续抛出，交由顶层处理为终态失败。</p>
     */
    private void performWithRetry(String stageName, Supplier<Boolean> stage) {
        int attempt = 0;
        while (true) {
            try {
                Boolean result = stage.get();
                if (Boolean.TRUE.equals(result)) {
                    return; // 本阶段通过
                }
                throw new AuditRetryableException(stageName + " 返回未通过");
            } catch (AuditRetryableException e) {
                attempt++;
                if (attempt >= auxRetryAttempts) {
                    // 重试耗尽，交给顶层兜底转终态失败，避免无限阻塞在"审核中"
                    throw e;
                }
                log.warn("{} 失败(第{}/{})，即将重试: {}", stageName, attempt, auxRetryAttempts, e.getMessage());
                sleep(auxRetryBackoffMs);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AuditRetryableException("审核重试线程被中断");
        }
    }
}