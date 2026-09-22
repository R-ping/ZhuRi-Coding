package com.zhuri.coding.content.schedule;

import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticleAutoScanService;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文章审核滞留补偿任务。
 *
 * <p><b>为什么需要</b>：文章审核是「发布提交时点触发的 {@code @Async}」调用，**没有落任务表**。
 * 顶层 try-catch 只能覆盖"进程还活着"的异常；一旦进程在审核中途崩溃 / 重启 / OOM，
 * 该文章会**永久停留在 SUBMIT（审核中）**——既不会被重试，也没有任何扫描会重新拉起它。
 * 这是与评论 / 沸点审核的主要不对称（那两者有任务表 + 各自的 recovery task 兜底），本类补齐这一环。
 *
 * <p><b>幂等性</b>：直接复用 {@link ArticleAutoScanService#autoScanArticle(Long)}，而它内部的
 * {@code doAutoScan} 开头就有"status != SUBMIT 直接跳过"的判断——因此重复触发、以及与本进程内
 * 正常审核并发触发都是安全的，不会对已推进的文章重复审核。
 *
 * <p><b>并发残留风险（已知取舍，非缺陷）</b>：进程内正常审核与扫描触发之间的"读后写"检查窗口
 * 理论上仍可能并发通过。这里用「只捞明显超时的文章（默认 SUBMIT 超过 10 分钟）」把该窗口压到可忽略：
 * 正常审核耗时是秒级（单环节最坏 3 次尝试 ≈ 3 秒退避 + 若干次 AI 调用），10 分钟量级的阈值下
 * 并发概率极低。若要彻底消除，需要给 {@code ap_article} 增加"审核中"抢占标记，改动面较大，暂不做。
 */
@Slf4j
@Component
public class ArticleAuditRecoveryTask {

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ArticleAutoScanService articleAutoScanService;

    /** 滞留判定阈值（分钟）：SUBMIT 超过该时长仍未推进 → 视为滞留并重新触发审核 */
    @Value("${app.audit.recovery-stale-minutes:10}")
    private int staleMinutes;

    /** 单批扫描上限（避免一次拉起过多 AI 审核） */
    @Value("${app.audit.recovery-batch:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.audit.recovery-interval-ms:300000}",
        initialDelayString = "${app.audit.recovery-initial-delay-ms:120000}")
    public void recoverStaleSubmitArticles() {
        try {
            Date before = new Date(System.currentTimeMillis() - staleMinutes * 60_000L);
            List<Long> articleIds = apArticleMapper.selectStaleSubmitArticleIds(before, batchSize);
            if (articleIds == null || articleIds.isEmpty()) {
                return;
            }
            log.warn("文章审核滞留补偿：扫描到 {} 篇仍处于审核中且已超过 {} 分钟的文章，重新触发审核",
                articleIds.size(), staleMinutes);
            for (Long articleId : articleIds) {
                try {
                    // 内部 status != SUBMIT 会直接跳过 → 与本进程正常审核并发也安全
                    articleAutoScanService.autoScanArticle(articleId);
                } catch (Exception e) {
                    log.error("文章审核滞留补偿触发异常, articleId={}", articleId, e);
                }
            }
        } catch (Exception e) {
            log.error("文章审核滞留补偿扫描异常", e);
        }
    }
}
