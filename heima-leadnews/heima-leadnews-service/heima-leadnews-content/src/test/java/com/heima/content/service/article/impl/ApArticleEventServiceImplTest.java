package com.heima.content.service.article.impl;

import cn.hutool.json.JSONUtil;
import com.heima.apis.search.ISearchClient;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.search.vos.SearchArticleVo;
import java.util.Arrays;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private ApArticleMapper apArticleMapper;
    @Mock
    private ISearchClient searchClient;

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

    @Test
    @DisplayName("ES_SYNC_FAIL 重试失败累计 retryCount，未达上限不删除")
    void esRetryFailureCountsRetry() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        doThrow(new RuntimeException("es down")).when(searchClient).syncArticle(any(SearchArticleVo.class));

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
        doThrow(new RuntimeException("es down")).when(searchClient).syncArticle(any(SearchArticleVo.class));

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
        verify(searchClient).syncArticle(any(SearchArticleVo.class));
    }

    @Test
    @DisplayName("DB_SET_FAIL 置位成功转 ES 同步并完成")
    void dbSetFailThenPublishedAndSync() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_DB_SET_FAIL);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(1);

        service.processEvent();

        assertEquals(ArticleConstants.EVENT_STATUS_DONE, e.getStatus().byteValue());
        verify(searchClient).syncArticle(any(SearchArticleVo.class));
    }

    @Test
    @DisplayName("DB_SET_FAIL 置位 0 行但文章已是发布态 → 续跑 ES 同步")
    void dbSetFailArticleAlreadyPublished() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_DB_SET_FAIL);
        ApArticle article = new ApArticle();
        article.setId(1L);
        article.setStatus((byte) ApArticle.Status.PUBLISHED.getCode());
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(0);
        when(apArticleMapper.selectById(1L)).thenReturn(article);

        service.processEvent();

        assertEquals(ArticleConstants.EVENT_STATUS_DONE, e.getStatus().byteValue());
    }

    @Test
    @DisplayName("DB_SET_FAIL 文章处于 FAIL 终态 → 删除事件防滞留")
    void dbSetFailArticleFailed() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_DB_SET_FAIL);
        ApArticle article = new ApArticle();
        article.setId(1L);
        article.setStatus((byte) ApArticle.Status.FAIL.getCode());
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(0);
        when(apArticleMapper.selectById(1L)).thenReturn(article);

        service.processEvent();

        verify(apArticleEventMapper).deleteByArticleId(1L);
        verify(searchClient, never()).syncArticle(any());
    }

    @Test
    @DisplayName("INIT 滞留（消费线程崩溃）→ 重放置位并同步完成")
    void staleInitReplayed() {
        ArticleEvent e = event(1L);
        e.setStatus(ArticleConstants.EVENT_STATUS_INIT);
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList(e));
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(1);

        service.processEvent();

        assertEquals(ArticleConstants.EVENT_STATUS_DONE, e.getStatus().byteValue());
        verify(searchClient).syncArticle(any(SearchArticleVo.class));
    }

    @Test
    @DisplayName("扫描结束清理已完成(status=4)事件")
    void completedCleanedAtEnd() {
        when(apArticleEventMapper.loadUnfinishedEvents()).thenReturn(Arrays.asList());

        service.processEvent();

        verify(apArticleEventMapper).deleteCompletedEvents();
    }
}
