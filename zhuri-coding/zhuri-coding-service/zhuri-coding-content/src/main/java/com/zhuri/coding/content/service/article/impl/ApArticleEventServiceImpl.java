package com.zhuri.coding.content.service.article.impl;

import cn.hutool.json.JSONUtil;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.article.ApArticleEventMapper;
import com.zhuri.coding.content.service.article.ApArticleEventService;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor;
import com.zhuri.coding.model.article.pojos.ArticleEvent;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 文章发布执行 + 本地消息表补偿扫描（单 status 状态机）
 *
 * <p>发布主流程与补偿扫描共用同一套幂等执行体 {@link #publishFromInit}：
 * 置 DB 发布态（SUBMIT→PUBLISHED 条件更新）→ 同步 ES → 收敛状态机。
 * 主路径：延迟任务落锚后发布 ArticlePublishEvent，由 @Async 监听器调用 {@link #executePublish}；
 * 兜底路径：每 20s 扫描未完成事件——
 * <ul>
 *   <li>status=INIT 且滞留超 60s：异步监听器崩溃/重启，重放整段流程</li>
 *   <li>status=DB_SET_FAIL：DB 置位失败，重试置位（幂等自愈，不计重试次数、不进死信）</li>
 *   <li>status=ES_SYNC_FAIL：ES 同步失败，重试 syncArticle，累计重试次数，超限进死信清理</li>
 * </ul>
 * 文章处于 FAIL 等不可发布终态时删除事件并告警，避免永久滞留。
 */
@Service
@Slf4j
public class ApArticleEventServiceImpl implements ApArticleEventService {

    @Autowired
    private ApArticleEventMapper apArticleEventMapper;

    /**
     * 发布业务逻辑（置位 + ES 同步）。
     *
     * <p>与本类的 {@code article_event} 状态机**刻意分开**：业务逻辑归 Executor，
     * 状态记录仍归本类 —— 这样 Outbox 侧的 Handler 能复用同一份业务逻辑，
     * 而两条链路各写自己的消息表，不会互相覆盖状态。
     */
    @Autowired
    private ArticlePublishExecutor publishExecutor;

    @Override
    public void updateEvent(ArticleEvent event) {
        apArticleEventMapper.updateArticleEvent(event);
    }

    @Override
    public void executePublish(Long articleId) {
        if (articleId == null) {
            log.warn("文章发布执行事件缺少 articleId，跳过");
            return;
        }
        // 内存构造事件对象：updateArticleEvent 按 article_id 定位，无需回查锚点行，
        // 也避免了「锚点事务未提交 + @Async 抢跑」的可见性竞争；
        // 同步失败落 ES_SYNC_FAIL 后，重试所需计数由扫描从库中加载完整行继续累计。
        ArticleEvent event = new ArticleEvent();
        event.setArticleId(articleId);
        event.setStatus(ArticleConstants.EVENT_STATUS_INIT);
        event.setRetryCount((byte) 0);
        event.setMaxRetryCount(ArticleConstants.EVENT_ES_MAX_RETRY);
        SearchArticleVo vo = new SearchArticleVo();
        vo.setId(articleId);
        event.setParameter(JSONUtil.toJsonStr(vo));
        publishFromInit(event);
    }

    /**
     * 补偿扫描入口。
     *
     * <p><b>迁移阶段 2（切读）起已移除 {@code @Scheduled} 定时触发</b>：
     * {@code createArticleEvent} 不再写 {@code article_event}，本扫描已无数据可扫；
     * 全部补偿职责由 Outbox 接管（5s 轮询 + 指数退避 + 重试耗尽终态策略 + 生命周期护栏）。
     *
     * <p>之所以连定时器也一并摘掉，而不是"留着空转也无害"：
     * 一个还在运行的旧链路扫描器是<b>沉默的隐患</b> —— 一旦将来有人往
     * {@code article_event} 写入数据，旧链路就会在不被告知的情况下复活，
     * 与 Outbox 争夺同一条发布链路。保留方法体仅为阶段 3 清理前的可回退形态。
     */
    public void processEvent() {
        for (ArticleEvent event : apArticleEventMapper.loadUnfinishedEvents()) {
            try {
                handle(event);
            } catch (Exception e) {
                log.error("文章事件补偿处理异常, articleId={}, status={}", event.getArticleId(),
                    event.getStatus(), e);
            }
        }
        // 清理已完成(status=4)事件
        apArticleEventMapper.deleteCompletedEvents();
    }

    /** 按状态分发：INIT(滞留重放) / DB_SET_FAIL(重试置位) / ES_SYNC_FAIL(重试同步) */
    private void handle(ArticleEvent event) {
        byte status = event.getStatus() == null ? ArticleConstants.EVENT_STATUS_INIT : event.getStatus();
        switch (status) {
            case ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL:
                retryEsSync(event);
                break;
            case ArticleConstants.EVENT_STATUS_DB_SET_FAIL:
                retryDbSet(event);
                break;
            default:
                // INIT 滞留：异步监听器可能已崩溃，重放「置位 + 同步」
                publishFromInit(event);
        }
    }

    /** 重试 DB 置位（status=2，幂等自愈）。置位成功或已是发布态 → 转 ES 同步；FAIL 等终态 → 死信删除 */
    private void retryDbSet(ArticleEvent event) {
        try {
            ArticlePublishExecutor.Outcome outcome = publishExecutor.publish(event.getArticleId());
            switch (outcome) {
                case DONE -> markStatus(event, ArticleConstants.EVENT_STATUS_DONE, null);
                // 仍处于审核态（罕见竞态窗口）：顺延下一轮再试
                case STILL_PENDING -> postpone(event);
                case ARTICLE_MISSING, ARTICLE_NOT_PUBLISHABLE ->
                    apArticleEventMapper.deleteByArticleId(event.getArticleId());
            }
        } catch (Exception e) {
            // 置位成功但 ES 同步失败 → 落 ES_SYNC_FAIL 等下一轮（等价于原 esSyncOrMarkFail 的失败分支）
            log.error("置位成功但ES同步失败, articleId={}", event.getArticleId(), e);
            markStatus(event, ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL,
                new Date(System.currentTimeMillis() + ArticleConstants.RETRY_INTERVAL_MS));
        }
    }

    /** 从 INIT 推进发布：先置位再同步（幂等）。异步监听器主路径与扫描 INIT 滞留重放共用 */
    private void publishFromInit(ArticleEvent event) {
        try {
            ArticlePublishExecutor.Outcome outcome = publishExecutor.publish(event.getArticleId());
            switch (outcome) {
                case DONE -> markStatus(event, ArticleConstants.EVENT_STATUS_DONE, null);
                // 置位仍失败：转 DB_SET_FAIL 交给重试分支持续补偿
                case STILL_PENDING -> markStatus(event, ArticleConstants.EVENT_STATUS_DB_SET_FAIL, null);
                case ARTICLE_MISSING, ARTICLE_NOT_PUBLISHABLE ->
                    apArticleEventMapper.deleteByArticleId(event.getArticleId());
            }
        } catch (Exception e) {
            log.error("置位成功但ES同步失败, articleId={}", event.getArticleId(), e);
            markStatus(event, ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL,
                new Date(System.currentTimeMillis() + ArticleConstants.RETRY_INTERVAL_MS));
        }
    }

    /** 重试 ES 同步（status=3）：成功置 DONE 并清零重试次数；失败累计次数，超限死信 */
    private void retryEsSync(ArticleEvent event) {
        try {
            publishExecutor.syncToEs(event.getArticleId());
            markStatus(event, ArticleConstants.EVENT_STATUS_DONE, null);
            log.info("ES同步重试成功, articleId={}", event.getArticleId());
        } catch (Exception e) {
            int retryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
            int maxRetry = event.getMaxRetryCount() == null
                ? ArticleConstants.EVENT_ES_MAX_RETRY : event.getMaxRetryCount();
            if (retryCount >= maxRetry) {
                log.error("文章ES同步超过最大重试次数({})，清理死信, articleId={}", maxRetry,
                    event.getArticleId(), e);
                apArticleEventMapper.deleteByArticleId(event.getArticleId());
            } else {
                event.setRetryCount((byte) retryCount);
                event.setRetryTime(new Date(System.currentTimeMillis() + ArticleConstants.RETRY_INTERVAL_MS));
                event.setUpdateTime(new Date());
                apArticleEventMapper.updateArticleEvent(event);
                log.warn("ES同步重试失败({}/{}), articleId={}", retryCount, maxRetry,
                    event.getArticleId(), e);
            }
        }
    }

    // 【2026-09-26 迁移】原 esSyncOrMarkFail / doEsSync / resolveArticleStatus 三个方法已移入
    // ArticlePublishExecutor —— 目的是让 Outbox 侧的 ArticlePublishHandler 复用同一份发布逻辑，
    // 避免"同一件事两份代码"的漂移（改了一处忘另一处，日志与报错栈也会分裂）。
    // 注意边界：状态记录（markStatus / deleteByArticleId）仍留在本类 ——
    // 业务逻辑共用、状态各管各的，两条链路各写自己的消息表，不会互相覆盖。

    /** 顺延重试时间（DB 置位仍为 SUBMIT 的罕见竞态窗口） */
    private void postpone(ArticleEvent event) {
        event.setRetryTime(new Date(System.currentTimeMillis() + ArticleConstants.RETRY_INTERVAL_MS));
        event.setUpdateTime(new Date());
        apArticleEventMapper.updateArticleEvent(event);
    }

    private void markStatus(ArticleEvent event, byte status, Date retryTime) {
        event.setStatus(status);
        if (status == ArticleConstants.EVENT_STATUS_DONE) {
            event.setRetryCount((byte) 0); // 成功清零，避免残留计数误判死信
            event.setRetryTime(null);
        } else if (retryTime != null) {
            event.setRetryTime(retryTime);
        }
        event.setUpdateTime(new Date());
        apArticleEventMapper.updateArticleEvent(event);
    }
}
