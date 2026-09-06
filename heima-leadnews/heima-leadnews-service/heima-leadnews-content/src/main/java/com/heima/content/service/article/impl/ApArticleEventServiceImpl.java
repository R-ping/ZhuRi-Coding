package com.heima.content.service.article.impl;

import cn.hutool.json.JSONUtil;
import com.heima.apis.search.ISearchClient;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ApArticleEventService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.search.vos.SearchArticleVo;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 文章本地消息表补偿扫描（单 status 状态机）
 *
 * <p>主流程（延迟任务消费线程）只负责一次执行：落消息(INIT) → 置 DB 发布态 → 同步 ES → 置 DONE；
 * 本类兜底"主流程执行中断/失败"的收敛：每 20s 扫描未完成事件：
 * <ul>
 *   <li>status=INIT 且滞留超 60s：消费线程崩溃，重放「置位 + ES 同步」</li>
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

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ISearchClient searchClient;

    @Override
    public void updateEvent(ArticleEvent event) {
        apArticleEventMapper.updateArticleEvent(event);
    }

    @Scheduled(fixedRate = 20000)
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
                // INIT 滞留：消费线程可能已崩溃，重放「置位 + 同步」
                replayFromInit(event);
        }
    }

    /** 重试 DB 置位（status=2，幂等自愈）。置位成功或已是发布态 → 转 ES 同步；FAIL 等终态 → 死信删除 */
    private void retryDbSet(ArticleEvent event) {
        if (apArticleMapper.markPublishedIfPending(event.getArticleId()) == 1) {
            esSyncOrMarkFail(event);
            return;
        }
        Byte articleStatus = resolveArticleStatus(event.getArticleId());
        if (articleStatus == null) {
            log.error("文章不存在，删除事件, articleId={}", event.getArticleId());
            apArticleEventMapper.deleteByArticleId(event.getArticleId());
        } else if (articleStatus == Status.PUBLISHED.getCode()) {
            esSyncOrMarkFail(event);
        } else if (articleStatus == Status.SUBMIT.getCode()) {
            postpone(event); // 仍处于审核态（罕见竞态窗口），顺延下一轮再试
        } else {
            log.error("文章处于不可发布终态(status={})，删除事件, articleId={}", articleStatus,
                event.getArticleId());
            apArticleEventMapper.deleteByArticleId(event.getArticleId());
        }
    }

    /** INIT 滞留重放：先置位再同步（与主流程一致，幂等） */
    private void replayFromInit(ArticleEvent event) {
        if (apArticleMapper.markPublishedIfPending(event.getArticleId()) == 1) {
            esSyncOrMarkFail(event);
            return;
        }
        Byte articleStatus = resolveArticleStatus(event.getArticleId());
        if (articleStatus == null) {
            log.error("重放失败：文章不存在，删除事件, articleId={}", event.getArticleId());
            apArticleEventMapper.deleteByArticleId(event.getArticleId());
        } else if (articleStatus == Status.PUBLISHED.getCode()) {
            esSyncOrMarkFail(event);
        } else if (articleStatus == Status.SUBMIT.getCode()) {
            // 置位仍失败：转 DB_SET_FAIL 交给重试分支持续补偿
            markStatus(event, ArticleConstants.EVENT_STATUS_DB_SET_FAIL, null);
        } else {
            log.error("重放失败：文章不可发布(status={})，删除事件, articleId={}", articleStatus,
                event.getArticleId());
            apArticleEventMapper.deleteByArticleId(event.getArticleId());
        }
    }

    /** 重试 ES 同步（status=3）：成功置 DONE 并清零重试次数；失败累计次数，超限死信 */
    private void retryEsSync(ArticleEvent event) {
        try {
            doEsSync(event);
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

    /** 置位成功后执行 ES 同步；失败落 ES_SYNC_FAIL 等待下一轮 */
    private void esSyncOrMarkFail(ArticleEvent event) {
        try {
            doEsSync(event);
            markStatus(event, ArticleConstants.EVENT_STATUS_DONE, null);
        } catch (Exception e) {
            log.error("置位成功但ES同步失败, articleId={}", event.getArticleId(), e);
            markStatus(event, ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL,
                new Date(System.currentTimeMillis() + ArticleConstants.RETRY_INTERVAL_MS));
        }
    }

    /** 实际 ES 同步：从 parameter 还原 vo 调 search 服务（正文由 search 端反向拉取） */
    private void doEsSync(ArticleEvent event) {
        SearchArticleVo vo = JSONUtil.toBean(event.getParameter(), SearchArticleVo.class);
        if (vo == null || vo.getId() == null) {
            throw new IllegalStateException("事件参数缺失 articleId=" + event.getArticleId());
        }
        searchClient.syncArticle(vo);
    }

    private Byte resolveArticleStatus(Long articleId) {
        ApArticle article = apArticleMapper.selectById(articleId);
        return article == null || article.getStatus() == null ? null : article.getStatus().byteValue();
    }

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
