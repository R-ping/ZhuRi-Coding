package com.zhuri.coding.content.schedule.task;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文章索引对账巡检 —— 「已发布但不在 ES 索引里」的兜底。
 *
 * <p><b>为什么需要它</b>：发布事件的失败重试只能覆盖"这次同步失败了"。它覆盖不了两类情况：
 * <ol>
 *   <li>同步调用返回成功、文档实际没落库；</li>
 *   <li>索引被误删 / 清空 / 重建中断。</li>
 * </ol>
 * 这两类事后<b>没有任何信号</b> —— 文章在库里是已发布、在索引里不存在，用户搜不到，
 * 而系统认为一切正常。所以必须定期真的去问一次 ES。
 *
 * <p><b>与 Outbox 的分工</b>：不是替代关系。Outbox 负责"事件不丢 + 重试收敛"，
 * 本任务负责"结果对不上时兜底"。因此补推复用同一条 ES 同步实现
 * （{@link ArticlePublishExecutor#syncToEs(Long)}），不另写一套补救代码。
 *
 * <p><b>失败处理</b>：补推失败**不在这里重试**。本任务本身就是兜底，下一轮对账还会发现它 ——
 * 再套一层重试只会让两个收敛机制互相打架。
 */
@Slf4j
@Component
public class ArticleIndexReconcileTask {

    /** 对账窗口（天）：只看这段时间内发布的文章 */
    @Value("${app.article.index-reconcile-window-days:3}")
    int windowDays;

    /** 单轮扫描上限（控制 ES 查询量） */
    @Value("${app.article.index-reconcile-scan-limit:300}")
    int scanLimit;

    /** 单轮补推上限（索引被整体清空时，避免一次性打垮下游） */
    @Value("${app.article.index-reconcile-repair-limit:100}")
    int repairLimit;

    @Autowired
    ApArticleMapper apArticleMapper;

    @Autowired
    ISearchClient searchClient;

    @Autowired
    ArticlePublishExecutor publishExecutor;

    private final Counter missingCounter;
    private final Counter repairedCounter;
    private final Counter failedCounter;
    private final Counter errorCounter;

    @Autowired
    public ArticleIndexReconcileTask(MeterRegistry meterRegistry) {
        this.missingCounter = counter(meterRegistry, "article.index.reconcile.missing", "对账发现的索引缺失文章数");
        this.repairedCounter = counter(meterRegistry, "article.index.reconcile.repaired", "对账补推成功的文章数");
        this.failedCounter = counter(meterRegistry, "article.index.reconcile.failed", "对账补推失败的文章数");
        this.errorCounter = counter(meterRegistry, "article.index.reconcile.error", "对账本轮整体失败次数");
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    /**
     * 默认每小时对账一次，首次延迟 1 分钟避开启动高峰。
     *
     * <p><b>刻意用 fixedDelay 而不是 cron</b>：Spring 的 {@code @Scheduled} 不允许
     * {@code cron} 与 {@code initialDelay} 同时出现（启动即报 "initialDelay not supported
     * for cron triggers"）。这里要的是"别在启动瞬间就开始扫全表"，所以选 fixedDelay+initialDelay
     * —— 与本项目其它恢复类任务（审核滞留补偿、退款重试）保持一致。
     */
    @Scheduled(fixedDelayString = "${app.article.index-reconcile-interval-ms:3600000}",
            initialDelayString = "${app.article.index-reconcile-initial-delay-ms:60000}")
    public void reconcile() {
        try {
            Date since = new Date(System.currentTimeMillis() - windowDays * 24L * 3600_000L);
            List<Long> published = apArticleMapper.selectPublishedIdsSince(since, scanLimit);
            if (published.isEmpty()) {
                return;
            }

            List<Long> missing = resolveMissing(published);
            if (missing.isEmpty()) {
                return;
            }
            missingCounter.increment(missing.size());

            // 缺失量超过单轮上限：多是索引被清空/重建中，分批补，别把下游一次打垮
            List<Long> toRepair = missing.size() > repairLimit
                    ? missing.subList(0, repairLimit) : missing;
            if (toRepair.size() < missing.size()) {
                log.warn("[INDEX-RECONCILE] 本轮缺失 {} 篇，按上限只补推 {} 篇，其余下轮继续",
                        missing.size(), toRepair.size());
            }

            int repaired = 0;
            int failed = 0;
            for (Long articleId : toRepair) {
                try {
                    publishExecutor.syncToEs(articleId);
                    repaired++;
                } catch (Exception e) {
                    failed++;
                    log.error("[INDEX-RECONCILE] 补推失败（下轮对账会再发现）, articleId={}", articleId, e);
                }
            }
            repairedCounter.increment(repaired);
            failedCounter.increment(failed);

            log.warn("[INDEX-RECONCILE] 对账完成：候选 {} 篇 / 缺失 {} 篇 / 补推成功 {} 篇 / 失败 {} 篇",
                    published.size(), missing.size(), repaired, failed);
        } catch (Exception e) {
            // 调度器自身异常不能终止心跳 —— 本轮跳过，下一轮重来
            errorCounter.increment();
            log.error("[INDEX-RECONCILE] 本轮对账异常，已跳过", e);
        }
    }

    /**
     * 问 search 侧要"这批 id 里哪些不在索引里"。
     *
     * <p>解析刻意写得啰嗦：Feign 反序列化到裸 {@code ResponseResult} 时，
     * 数字会被还原成 {@code Integer} 而非 {@code Long}，直接强转 {@code List<Long>}
     * 会在取元素时才炸。这里按 {@link Number} 取 long，避免埋一个只在有缺失时才触发的雷。
     */
    private List<Long> resolveMissing(List<Long> publishedIds) {
        ResponseResult<?> result = searchClient.missingArticleIds(publishedIds);
        if (result == null || result.getData() == null) {
            throw new IllegalStateException("对账接口未返回数据");
        }
        if (!(result.getData() instanceof Collection<?> items)) {
            throw new IllegalStateException("对账接口返回的数据格式异常: " + result.getData().getClass());
        }
        List<Long> missing = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Number number) {
                missing.add(number.longValue());
            } else if (item != null) {
                missing.add(Long.valueOf(String.valueOf(item)));
            }
        }
        return missing;
    }
}
