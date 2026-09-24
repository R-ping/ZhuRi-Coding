package com.zhuri.coding.content.schedule;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticleAutoScanService;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 文章审核滞留补偿任务单测。
 *
 * <p>守护点：文章审核没有任务表，进程崩溃后只能靠这里把滞留在 SUBMIT 的文章重新拉起；
 * 同时必须保证"没有滞留就不做任何事"，以及"单篇触发失败不影响其余文章"。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文章审核滞留补偿任务")
class ArticleAuditRecoveryTaskTest {

    @Mock
    private ApArticleMapper apArticleMapper;

    @Mock
    private ArticleAutoScanService articleAutoScanService;

    @InjectMocks
    private ArticleAuditRecoveryTask task;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "staleMinutes", 10);
        ReflectionTestUtils.setField(task, "batchSize", 50);
    }

    @Test
    @DisplayName("无滞留文章 → 不触发任何审核")
    void noStaleArticlesDoesNothing() {
        when(apArticleMapper.selectStaleSubmitArticleIds(any(Date.class), anyInt()))
            .thenReturn(Collections.emptyList());

        task.recoverStaleSubmitArticles();

        verify(articleAutoScanService, never()).autoScanArticle(any());
    }

    @Test
    @DisplayName("有滞留文章 → 逐篇重新触发审核")
    void staleArticlesAreReaudited() {
        when(apArticleMapper.selectStaleSubmitArticleIds(any(Date.class), anyInt()))
            .thenReturn(List.of(11L, 22L));

        task.recoverStaleSubmitArticles();

        verify(articleAutoScanService).autoScanArticle(11L);
        verify(articleAutoScanService).autoScanArticle(22L);
    }

    @Test
    @DisplayName("单篇触发抛异常 → 不影响其它文章继续补偿")
    void singleFailureDoesNotStopOthers() {
        when(apArticleMapper.selectStaleSubmitArticleIds(any(Date.class), anyInt()))
            .thenReturn(List.of(11L, 22L));
        when(articleAutoScanService.autoScanArticle(11L)).thenThrow(new RuntimeException("async reject"));

        assertDoesNotThrow(() -> task.recoverStaleSubmitArticles());

        verify(articleAutoScanService).autoScanArticle(22L);
    }

    @Test
    @DisplayName("查询异常被吞掉（定时任务不允许抛出去）")
    void queryFailureIsSwallowed() {
        when(apArticleMapper.selectStaleSubmitArticleIds(any(Date.class), anyInt()))
            .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> task.recoverStaleSubmitArticles());
        verify(articleAutoScanService, never()).autoScanArticle(any());
    }

    @Test
    @DisplayName("返回 null 也不抛（防御 mapper 异常返回）")
    void nullResultIsTolerated() {
        when(apArticleMapper.selectStaleSubmitArticleIds(any(Date.class), anyInt())).thenReturn(null);

        assertDoesNotThrow(() -> task.recoverStaleSubmitArticles());
        verify(articleAutoScanService, never()).autoScanArticle(any());
    }
}
