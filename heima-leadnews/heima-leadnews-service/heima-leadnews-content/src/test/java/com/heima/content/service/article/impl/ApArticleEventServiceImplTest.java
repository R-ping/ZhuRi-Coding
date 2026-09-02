package com.heima.content.service.article.impl;

import cn.hutool.json.JSONUtil;
import com.heima.apis.search.ISearchClient;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.service.article.ApArticleService;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.search.vos.SearchArticleVo;
import java.util.Arrays;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApArticleEventServiceImpl 单元测试
 *
 * 覆盖本地消息表 processEvent 的重试、失败计数、死信清理与成功清理分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文章事件本地消息表消费测试")
class ApArticleEventServiceImplTest {

    @Mock
    private ApArticleEventMapper apArticleEventMapper;
    @Mock
    private ISearchClient searchClient;
    @Mock
    private ApArticleService apArticleService;

    @InjectMocks
    private ApArticleEventServiceImpl service;

    private ArticleEvent event(Long articleId) {
        ArticleEvent e = new ArticleEvent();
        e.setArticleId(articleId);
        e.setRetryCount((byte) 0);
        e.setMaxRetryCount((byte) 3);
        e.setRetryTime(new Date(System.currentTimeMillis() - 60_000));
        e.setUpdateTime(new Date());
        e.setParameter(JSONUtil.toJsonStr(new SearchArticleVo()));
        return e;
    }

    @Test
    @DisplayName("processEvent - ES 重试失败也累计 retryCount（避免无限重试）")
    void esRetryFailureCountsRetry() {
        ArticleEvent e = event(1L);
        e.setEsStatus((byte) 1);
        e.setPubStatus((byte) 2); // 发布已成功，隔离 ES 分支
        when(apArticleEventMapper.loadArticleEvent()).thenReturn(Arrays.asList(e));
        doThrow(new RuntimeException("es down")).when(searchClient).syncArticle(any(SearchArticleVo.class));

        service.processEvent();

        // 失败仅累计一次，保证达到 maxRetryCount 后进入死信清理
        assertEquals((byte) 1, e.getRetryCount());
        verify(apArticleEventMapper).updateArticleEvent(e);
        // 未达死信上限，不删除
        verify(apArticleEventMapper, never()).deleteArticleEvent(anyList());
    }

    @Test
    @DisplayName("processEvent - 超过最大重试次数清理死信（不再滞留表内）")
    void deadLetterCleaned() {
        ArticleEvent e = event(1L);
        e.setRetryCount((byte) 3);
        e.setMaxRetryCount((byte) 3);
        e.setEsStatus((byte) 1);
        e.setPubStatus((byte) 1);
        when(apArticleEventMapper.loadArticleEvent()).thenReturn(Arrays.asList(e));

        service.processEvent();

        verify(apArticleEventMapper).deleteArticleEvent(Arrays.asList(1L));
        verify(apArticleEventMapper, never()).updateArticleEvent(any());
    }

    @Test
    @DisplayName("processEvent - 全部状态成功则删除本地消息记录")
    void allSuccessCleaned() {
        ArticleEvent e = event(1L);
        e.setEsStatus((byte) 2);
        e.setPubStatus((byte) 2);
        when(apArticleEventMapper.loadArticleEvent()).thenReturn(Arrays.asList(e));

        service.processEvent();

        verify(apArticleEventMapper).deleteArticleEvent(Arrays.asList(1L));
    }

    @Test
    @DisplayName("processEvent - 发布状态重试失败也累计 retryCount")
    void pubRetryFailureCountsRetry() {
        ArticleEvent e = event(1L);
        e.setEsStatus((byte) 2);
        e.setPubStatus((byte) 1);
        when(apArticleEventMapper.loadArticleEvent()).thenReturn(Arrays.asList(e));
        doThrow(new RuntimeException("pub fail")).when(apArticleService).updateArticleStatus(1L);

        service.processEvent();

        assertEquals((byte) 1, e.getRetryCount());
        verify(apArticleEventMapper).updateArticleEvent(e);
        verify(apArticleEventMapper, never()).deleteArticleEvent(anyList());
    }
}
