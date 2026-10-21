package com.zhuri.coding.content.schedule.task;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.search.dtos.IndexedArticleQueryDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文章索引双向对账巡检 —— 索引与库对不上的两个方向都兜住。
 *
 * <p><b>正向（库里有、索引没有：补推）</b>：发布事件的失败重试只能覆盖"这次同步失败了"。
 * 它覆盖不了「同步调用返回成功、文档实际没落库」和「索引被误删 / 清空 / 重建中断」——
 * 这两类事后没有任何信号：文章在库里已发布、在索引里不存在，用户搜不到，系统却认为一切正常。
 *
 * <p><b>反向（索引里有、库里不该有：清理）</b>：下线事件重试耗尽进死信后没人再管，
 * 索引里就会留下一篇已删除 / 已下架的文档。检索侧虽然会按可见性字段过滤，
 * 但那要求墓碑已经写进去；如果墓碑本身也没送达，过滤就无从生效 —— 这条兜底是最后一道。
 *
 * <p><b>与 Outbox 的分工</b>：不是替代关系。Outbox 负责"事件不丢 + 重试收敛"，
 * 本任务负责"结果对不上时兜底"。补推复用同一条 ES 同步实现
 * （{@link ArticlePublishExecutor#syncToEs(Long)}），清理复用同一个索引移除实现，不另写补救代码。
 *
 * <p><b>失败处理</b>：补推和清理失败都**不在这里重试**。本任务本身就是兜底，下一轮还会发现它 ——
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

    /** 单轮清理上限（索引被污染时，避免一次性打垮下游） */
    @Value("${app.article.index-reconcile-ghost-limit:50}")
    int ghostLimit;

    @Autowired
    ApArticleMapper apArticleMapper;

    @Autowired
    ISearchClient searchClient;

    @Autowired
    ArticlePublishExecutor publishExecutor;

    private final Counter missingCounter;
    private final Counter repairedCounter;
    private final Counter failedCounter;
    private final Counter ghostCounter;
    private final Counter ghostRemovedCounter;
    private final Counter errorCounter;

    @Autowired
    public ArticleIndexReconcileTask(MeterRegistry meterRegistry) {
        this.missingCounter = counter(meterRegistry, "article.index.reconcile.missing", "正向对账发现的索引缺失文章数");
        this.repairedCounter = counter(meterRegistry, "article.index.reconcile.repaired", "正向对账补推成功的文章数");
        this.failedCounter = counter(meterRegistry, "article.index.reconcile.failed", "正向对账补推失败的文章数");
        this.ghostCounter = counter(meterRegistry, "article.index.reconcile.ghost", "反向对账发现的幽灵文档数");
        this.ghostRemovedCounter = counter(meterRegistry, "article.index.reconcile.ghost.removed", "反向对账清理成功的幽灵文档数");
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
        Date since = new Date(System.currentTimeMillis() - windowDays * 24L * 3600_000L);

        // 正向失败说明 search 不可用，反向也做不了，整轮跳过
        try {
            repairMissing(since);
        } catch (Exception e) {
            errorCounter.increment();
            log.error("[INDEX-RECONCILE] 正向对账本轮异常，已跳过", e);
            return;
        }

        // 反向失败不回头否定正向结果：该补的已经补完了，本轮只是少做了一半
        try {
            sweepGhosts(since);
        } catch (Exception e) {
            log.warn("[INDEX-RECONCILE] 反向对账本轮跳过", e);
        }
    }

    /**
     * 正向：已发布但不在索引里的，补推。
     *
     * <p>「不在索引里」由 search 侧判定（它查询时也带了可见性过滤），所以「文档还在、
     * 但已被标记删除」同样算缺失，会被重新推一遍 —— 那正是删文档那步失败留下的残留。
     */
    private void repairMissing(Date since) {
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

        log.warn("[INDEX-RECONCILE] 正向对账完成：候选 {} 篇 / 缺失 {} 篇 / 补推成功 {} 篇 / 失败 {} 篇",
                published.size(), missing.size(), repaired, failed);
    }

    /**
     * 反向：索引里存在、但库里已经不是可见态的，清理掉。
     *
     * <p>判据是最朴素的一条：把索引报上来的 id 拿去库里问「这些里面哪些还可见」，
     * 没被认领的就是幽灵 —— 已删除、已下架、甚至库里压根不存在的 id 都落在这一类。
     * 不猜原因，只看两边对不对得上。
     */
    private void sweepGhosts(Date since) {
        IndexedArticleQueryDto query = new IndexedArticleQueryDto();
        query.setSinceMillis(since.getTime());
        query.setLimit(scanLimit);

        List<Long> indexed = parseIds(searchClient.indexedArticleIds(query), "索引内文章");
        if (indexed.isEmpty()) {
            return;
        }

        List<Long> stillVisible = apArticleMapper.selectVisibleIds(indexed);
        Set<Long> keep = stillVisible == null ? Collections.emptySet() : new HashSet<>(stillVisible);
        List<Long> ghosts = new ArrayList<>();
        for (Long articleId : indexed) {
            if (!keep.contains(articleId)) {
                ghosts.add(articleId);
            }
        }
        if (ghosts.isEmpty()) {
            return;
        }
        ghostCounter.increment(ghosts.size());

        List<Long> toRemove = ghosts.size() > ghostLimit ? ghosts.subList(0, ghostLimit) : ghosts;
        if (toRemove.size() < ghosts.size()) {
            log.warn("[INDEX-RECONCILE] 本轮幽灵文档 {} 篇，按上限只清理 {} 篇，其余下轮继续",
                    ghosts.size(), toRemove.size());
        }

        // 一次批量下线：这批要么整批生效，要么整批留到下轮 —— 半途失败时逐篇判断没有意义，
        // 下一轮对账会重新把没清掉的算成幽灵
        int removed = 0;
        try {
            ResponseResult<?> result = searchClient.removeArticleIndexBatch(toRemove);
            if (result == null || result.getCode() != AppHttpCodeEnum.SUCCESS.getCode()) {
                log.error("[INDEX-RECONCILE] 批量清理返回非成功（下轮会再发现）, count={}, code={}",
                        toRemove.size(), result == null ? "null" : result.getCode());
            } else {
                removed = toRemove.size();
            }
        } catch (Exception e) {
            log.error("[INDEX-RECONCILE] 批量清理幽灵文档失败（下轮会再发现）, count={}", toRemove.size(), e);
        }
        ghostRemovedCounter.increment(removed);

        log.warn("[INDEX-RECONCILE] 反向对账完成：索引 {} 篇 / 幽灵 {} 篇 / 清理成功 {} 篇",
                indexed.size(), ghosts.size(), removed);
    }

    /**
     * 问 search 侧要"这批 id 里哪些不在索引里"。
     *
     * <p>解析刻意写得啰嗦：Feign 反序列化到裸 {@code ResponseResult} 时，
     * 数字会被还原成 {@code Integer} 而非 {@code Long}，直接强转 {@code List<Long>}
     * 会在取元素时才炸。这里按 {@link Number} 取 long，避免埋一个只在有缺失时才触发的雷。
     */
    private List<Long> resolveMissing(List<Long> publishedIds) {
        return parseIds(searchClient.missingArticleIds(publishedIds), "对账");
    }

    /** 统一的 id 清单解析：null / 非集合一律视为接口异常，不能当成"没有数据" */
    private List<Long> parseIds(ResponseResult<?> result, String what) {
        if (result == null || result.getData() == null) {
            throw new IllegalStateException(what + "接口未返回数据");
        }
        if (!(result.getData() instanceof Collection<?> items)) {
            throw new IllegalStateException(what + "接口返回的数据格式异常: " + result.getData().getClass());
        }
        List<Long> ids = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Number number) {
                ids.add(number.longValue());
            } else if (item != null) {
                ids.add(Long.valueOf(String.valueOf(item)));
            }
        }
        return ids;
    }
}
