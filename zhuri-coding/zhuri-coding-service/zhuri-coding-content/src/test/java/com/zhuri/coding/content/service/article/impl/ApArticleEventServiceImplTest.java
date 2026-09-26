package com.zhuri.coding.content.service.article.impl;

import cn.hutool.json.JSONUtil;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.article.ApArticleEventMapper;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor.Outcome;
import com.zhuri.coding.model.article.pojos.ArticleEvent;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import java.util.Arrays;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApArticleEventServiceImpl 单元测试（单 status 状态机）
 *
 * <p><b>测试边界（2026-09-26 重构后）</b>：业务逻辑（置位 DB 发布态 + 同步 ES）已抽到
 * {@link ArticlePublishExecutor}，故这里只测**状态机映射**：
 * 「Executor 返回的业务结果 → {@code article_event} 的状态流转」。
 *
 * <p>之所以这样切：原测试 mock 的是 {@code apArticleMapper} / {@code searchClient} 这类
 * **底层依赖**，导致"业务逻辑一挪位置，整套状态机测试就整片失败"—— 测试与实现细节强耦合。
 * 按职责拆开后，两边可以各自演进。
 *
 * <p>覆盖 20s 补偿扫描三态处理：
 * ES_SYNC_FAIL 重试成功置 DONE / 失败累计重试 / 超限死信；
 * DB_SET_FAIL 置位成功转 ES 同步 / 文章已是发布态续跑 / 不可发布终态删除；
 * INIT 滞留（消费线程崩溃）整段重放。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文章事件本地消息表单状态机补偿测试")
class ApArticleEventServiceImplTest {

    @Mock
    private ApArticleEventMapper apArticleEventMapper;
    @Mock
    private ArticlePublishExecutor publishExecutor;

    @InjectMocks
    private ApArticleEventServiceImpl service;

    private ArticleEvent event(Long articleId) {
        ArticleEvent e = new ArticleEvent();
        e.setArticleId(articleId);
        e.setRetryCount((byte) 0);
        e.setMaxRetryCount(ArticleConstants.EVENT_ES_MAX_RETRY);
        e.setRetryTime(new Date(System.currentTimeMillis() - 60_000));
        e.setUpdateTime(new Date());
        SearchArticleVo vo = new SearchArticleVo();
        vo.setId(articleId);
        e.setParameter(JSONUtil.toJsonStr(vo));
        return e;
    }

    // ==================== ES_SYNC_FAIL 分支（只重试同步，不重新置位） ====================

    @Test
    @DisplayName("ES_SYNC_FAIL 重试失败累计 retryCount，未达上限不删除")
    void esRetryFailureCountsRetry() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        doThrow(new RuntimeException("es down")).when(publishExecutor).syncToEs(1L);

        service.processEvent();

        assertEquals((byte) 1, e.getRetryCount());
        verify(apArticleEventMapper).updateArticleEvent(e);
        verify(apArticleEventMapper, never()).deleteByArticleId(anyLong());
    }

    @Test
    @DisplayName("ES_SYNC_FAIL 连续失败达上限后清理死信")
    void deadLetterCleaned() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL);
        e.setRetryCount((byte) (ArticleConstants.EVENT_ES_MAX_RETRY - 1));
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        doThrow(new RuntimeException("es down")).when(publishExecutor).syncToEs(1L);

        service.processEvent();

        verify(apArticleEventMapper).deleteByArticleId(1L);
        verify(apArticleEventMapper, never()).updateArticleEvent(any());
    }

    @Test
    @DisplayName("ES_SYNC_FAIL 重试成功置 DONE 并清零重试次数")
    void esRetrySuccessMarkDone() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL);
        e.setRetryCount((byte) 3);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));

        service.processEvent();

        assertEquals(ArticleConstants.EVENT_STATUS_DONE, e.getStatus().byteValue());
        assertEquals((byte) 0, e.getRetryCount());
        verify(publishExecutor).syncToEs(1L);
    }

    // ==================== DB_SET_FAIL 分支（重新走一遍置位 + 同步） ====================

    @Test
    @DisplayName("DB_SET_FAIL 置位成功转 ES 同步并完成")
    void dbSetFailThenPublishedAndSync() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_DB_SET_FAIL);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(publishExecutor.publish(1L)).thenReturn(Outcome.DONE);

        service.processEvent();

        assertEquals(ArticleConstants.EVENT_STATUS_DONE, e.getStatus().byteValue());
        verify(publishExecutor).publish(1L);
    }

    @Test
    @DisplayName("DB_SET_FAIL 置位 0 行但文章已是发布态 → 续跑并完成")
    void dbSetFailArticleAlreadyPublished() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_DB_SET_FAIL);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(publishExecutor.publish(1L)).thenReturn(Outcome.DONE);

        service.processEvent();

        assertEquals(ArticleConstants.EVENT_STATUS_DONE, e.getStatus().byteValue());
    }

    @Test
    @DisplayName("DB_SET_FAIL 文章处于 FAIL 终态 → 删除事件防滞留")
    void dbSetFailArticleFailed() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_DB_SET_FAIL);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(publishExecutor.publish(1L)).thenReturn(Outcome.ARTICLE_NOT_PUBLISHABLE);

        service.processEvent();

        verify(apArticleEventMapper).deleteByArticleId(1L);
    }

    @Test
    @DisplayName("DB_SET_FAIL 文章仍待审 → 顺延重试时间，不删除")
    void dbSetFailStillPendingPostpones() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_DB_SET_FAIL);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(publishExecutor.publish(1L)).thenReturn(Outcome.STILL_PENDING);

        service.processEvent();

        verify(apArticleEventMapper, never()).deleteByArticleId(anyLong());
        verify(apArticleEventMapper).updateArticleEvent(any());
    }

    @Test
    @DisplayName("INIT 滞留（消费线程崩溃）→ 重放置位并同步完成")
    void staleInitReplayed() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_INIT);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(publishExecutor.publish(1L)).thenReturn(Outcome.DONE);

        service.processEvent();

        assertEquals(ArticleConstants.EVENT_STATUS_DONE, e.getStatus().byteValue());
        verify(publishExecutor).publish(1L);
    }

    @Test
    @DisplayName("置位成功但 ES 同步失败 → 落 ES_SYNC_FAIL 等下一轮")
    void esSyncFailureMarksEsSyncFail() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_DB_SET_FAIL);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(publishExecutor.publish(1L)).thenThrow(new RuntimeException("es down"));

        service.processEvent();

        assertEquals(ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL, e.getStatus().byteValue());
    }

    @Test
    @DisplayName("扫描结束清理已完成(status=4)事件")
    void completedCleanedAtEnd() {
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList());

        service.processEvent();

        verify(apArticleEventMapper).deleteCompletedEvents();
    }

    // ==================== executePublish（异步监听器主路径） ====================

    @Test
    @DisplayName("executePublish - articleId 为空直接跳过，不触碰任何表")
    void executePublishNullIdSkipped() {
        service.executePublish(null);

        verify(publishExecutor, never()).publish(anyLong());
        verify(apArticleEventMapper, never()).updateArticleEvent(any());
    }

    @Test
    @DisplayName("executePublish - 执行成功 → DONE（内存构造事件，不回查锚点行）")
    void executePublishHappyPath() {
        when(publishExecutor.publish(1L)).thenReturn(Outcome.DONE);

        service.executePublish(1L);

        ArgumentCaptor<ArticleEvent> captor = ArgumentCaptor.forClass(ArticleEvent.class);
        verify(apArticleEventMapper).updateArticleEvent(captor.capture());
        assertEquals(ArticleConstants.EVENT_STATUS_DONE, captor.getValue().getStatus().byteValue());
        verify(apArticleEventMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("executePublish - 文章仍 SUBMIT → 落 DB_SET_FAIL 交扫描补偿")
    void executePublishStillSubmitMarksDbSetFail() {
        when(publishExecutor.publish(1L)).thenReturn(Outcome.STILL_PENDING);

        service.executePublish(1L);

        ArgumentCaptor<ArticleEvent> captor = ArgumentCaptor.forClass(ArticleEvent.class);
        verify(apArticleEventMapper).updateArticleEvent(captor.capture());
        assertEquals(ArticleConstants.EVENT_STATUS_DB_SET_FAIL, captor.getValue().getStatus().byteValue());
    }

    @Test
    @DisplayName("executePublish - 文章已是发布态 → 幂等续跑并完成")
    void executePublishAlreadyPublishedContinues() {
        when(publishExecutor.publish(1L)).thenReturn(Outcome.DONE);

        service.executePublish(1L);

        ArgumentCaptor<ArticleEvent> captor = ArgumentCaptor.forClass(ArticleEvent.class);
        verify(apArticleEventMapper).updateArticleEvent(captor.capture());
        assertEquals(ArticleConstants.EVENT_STATUS_DONE, captor.getValue().getStatus().byteValue());
    }

    @Test
    @DisplayName("executePublish - 文章处于 FAIL 终态 → 删除事件防滞留")
    void executePublishFailedArticleDeletesEvent() {
        when(publishExecutor.publish(1L)).thenReturn(Outcome.ARTICLE_NOT_PUBLISHABLE);

        service.executePublish(1L);

        verify(apArticleEventMapper).deleteByArticleId(1L);
    }
}
