package com.zhuri.coding.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticleAutoScanService;
import com.zhuri.coding.content.service.article.ArticleTaskService;
import com.zhuri.coding.content.service.article.AuditRecordService;
import com.zhuri.coding.content.service.article.processor.ArticleAuditProcessor;
import com.zhuri.coding.content.service.article.processor.AuditFailProcessor;
import com.zhuri.coding.content.service.article.processor.AuditProcessorContext;
import com.zhuri.coding.content.service.article.processor.AuditRetryableException;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import java.util.List;
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
 * <p>采用责任链模式编排审核流程，每个审核环节由独立的 {@link ArticleAuditProcessor} 处理。
 * 处理器通过 Spring 注入为 List 并按 {@link ArticleAuditProcessor#getOrder()} 升序自动排序，
 * 顺序由各处理器的 @Order 注解声明，新增环节无需改动本类。</p>
 *
 * <p>异常分类策略（核心）：</p>
 * <ul>
 *   <li><b>业务判定不通过</b>（真实内容违规、图片命中 high/medium）：处理器返回 {@code false}，
 *       编排方直接正常驳回，不做重试——重试同样内容仍会违规，只会白白消耗 LLM token。</li>
 *   <li><b>非业务系统异常</b>（LLM/图片服务网络中断、连接超时、连接数打满等瞬时故障）：
 *       处理器抛出 {@link AuditRetryableException}，编排方 {@link #performWithRetry}
 *       做有界指数退避重试，重试耗尽转终态失败——避免把「服务抖动」误判成「内容违规」拒绝用户文章。</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleAutoScanServiceImpl implements ArticleAutoScanService {

    private final ApArticleMapper apArticleMapper;
    private final ApArticleContentMapper apArticleContentMapper;

    /** 审核责任链：Spring 按 @Order 升序注入 */
    private final List<ArticleAuditProcessor> auditProcessors;

    private final AuditFailProcessor auditFailProcessor;
    private final ArticleTaskService articleTaskService;
    private final AuditRecordService auditRecordService;

    /** 辅助环节单阶段最大重试次数（默认3次） */
    @Value("${app.audit.aux-retry-attempts:3}")
    private int auxRetryAttempts;

    /** 辅助环节重试退避基准间隔（毫秒，默认1000） */
    @Value("${app.audit.aux-retry-backoff-ms:1000}")
    private long auxRetryBackoffMs;

    /** 指数退避封顶（毫秒） */
    private static final long MAX_BACKOFF_MS = 30_000L;

    @Override
    @Async
    public CompletableFuture<Boolean> autoScanArticle(Long articleId) {
        try {
            return doAutoScan(articleId);
        } catch (Exception e) {
            // 顶层兜底：任何环节抛出的未预期异常都不能让文章无限停留在"审核中"，
            // 统一落为终态失败（status=FAIL）并通知作者，杜绝"永远审核中"。
            // 注意：走 handleSystemErrorFail 而非 handleFail —— 系统异常的通知文案不含"违规/删除"字样，
            // 避免 AI 服务抖动被作者感知为"内容违规被删"。
            log.error("文章审核出现未预期异常, articleId={}", articleId, e);
            ApArticle article = apArticleMapper.selectById(articleId);
            if (article != null) {
                auditFailProcessor.handleSystemErrorFail(article, AuditFailProcessor.SYSTEM_ERROR_REASON);
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

        // 按 @Order 顺序执行审核责任链：
        // - 处理器抛 AuditRetryableException（如 LLM/图片服务网络抖动、超时、连接满等非业务异常）
        //   → 由 performWithRetry 做有界指数退避重试，重试耗尽转终态失败；
        // - 处理器返回 false（真实违规/图片命中）→ 正常驳回，不重试。
        for (ArticleAuditProcessor processor : auditProcessors) {
            String stageName = processor.getClass().getSimpleName();
            boolean pass = performWithRetry(stageName, () -> processor.process(article, content, context));
            if (!pass) {
                String failReason = context.getExtra("failReason");
                auditFailProcessor.handleFail(article, failReason);
                log.info("审核环节未通过, articleId={}, stage={}, reason={}", articleId, stageName, failReason);
                return CompletableFuture.completedFuture(false);
            }
        }

        log.info("文章审核完成, articleId={}, isHighSimilarity={}", articleId, context.isHighSimilarity());

        // 审核通过：写入审计轨迹（通过记录），保证审计完整（失败由 AuditFailProcessor 记录）
        auditRecordService.record(article, content, ArticleConstants.AUDIT_STATUS_PASS, "审核通过");

        // 审核通过后：添加到定时发布调度任务
        articleTaskService.addArticleToTask(article.getId(), article.getPublishTime());
        return CompletableFuture.completedFuture(true);
    }

    /**
     * 对单个审核环节执行有界重试。
     * <p>处理器在「外部服务不可用/网络抖动/超时等非业务异常」时抛出 {@link AuditRetryableException}
     * （而非返回 false），本方法统一捕获并按指数退避重试；返回 false 一律视为业务驳回，不重试。
     * 重试策略：最多 {@link #auxRetryAttempts} 次、指数退避（base × 2^(attempt-1)，封顶 30s）。
     * 重试耗尽后继续抛出，交由顶层处理为终态失败。</p>
     */
    private boolean performWithRetry(String stageName, Supplier<Boolean> stage) {
        int attempt = 0;
        while (true) {
            try {
                // 业务判定（违规/图片命中）→ 返回 false 即正常驳回，不做重试
                Boolean result = stage.get();
                if (result != null) {
                    return result;
                }
                return false;
            } catch (AuditRetryableException e) {
                attempt++;
                if (attempt >= auxRetryAttempts) {
                    // 重试耗尽，交给顶层兜底转终态失败，避免无限阻塞在"审核中"
                    throw e;
                }
                long backoff = computeBackoff(attempt);
                log.warn("{} 失败(第{}/{})，{}ms 后重试: {}", stageName, attempt, auxRetryAttempts, backoff, e.getMessage());
                sleep(backoff);
            }
        }
    }

    /** 指数退避：base × 2^(attempt-1)，封顶 30s */
    private long computeBackoff(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 5); // 最多 2^5 = 32 倍
        return Math.min(auxRetryBackoffMs * multiplier, MAX_BACKOFF_MS);
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