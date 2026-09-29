package com.zhuri.coding.content.schedule.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 文章索引对账巡检测试。
 *
 * <p>重点覆盖"对账不能把故障伪装成正常"这条语义：search 不可用时必须整轮跳过，
 * 而不是当成"没有缺失"安静地什么都不做。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文章索引对账巡检")
class ArticleIndexReconcileTaskTest {

    @Mock
    private ApArticleMapper apArticleMapper;

    @Mock
    private ISearchClient searchClient;

    @Mock
    private ArticlePublishExecutor publishExecutor;

    private ArticleIndexReconcileTask task;

    @BeforeEach
    void setUp() {
        task = new ArticleIndexReconcileTask(new SimpleMeterRegistry());
        task.apArticleMapper = apArticleMapper;
        task.searchClient = searchClient;
        task.publishExecutor = publishExecutor;
        task.windowDays = 3;
        task.scanLimit = 300;
        task.repairLimit = 100;
    }

    @Test
    @DisplayName("没有候选文章：不调对账接口")
    void noCandidateSkipsSearch() {
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(Collections.emptyList());

        task.reconcile();

        verify(searchClient, never()).missingArticleIds(anyList());
        verify(publishExecutor, never()).syncToEs(any());
    }

    @Test
    @DisplayName("索引无缺失：不补推任何文章")
    void noMissingSkipsRepair() {
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(1L, 2L));
        when(searchClient.missingArticleIds(anyList()))
                .thenReturn(ResponseResult.okResult(Collections.emptyList()));

        task.reconcile();

        verify(publishExecutor, never()).syncToEs(any());
    }

    @Test
    @DisplayName("发现缺失：逐篇补推，且只补缺失的那些")
    void repairsOnlyMissingOnes() {
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(1L, 2L, 3L));
        when(searchClient.missingArticleIds(anyList()))
                .thenReturn(ResponseResult.okResult(Arrays.asList(2L, 3L)));

        task.reconcile();

        verify(publishExecutor, times(1)).syncToEs(2L);
        verify(publishExecutor, times(1)).syncToEs(3L);
        verify(publishExecutor, never()).syncToEs(1L);
    }

    @Test
    @DisplayName("Feign 把数字还原成 Integer：也要能正确解析出缺失 id（不可强转 List<Long>）")
    void parsesNumericElementsRegardlessOfBoxedType() {
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(List.of(1L));
        // 裸 ResponseResult 反序列化后元素是 Integer 而非 Long
        when(searchClient.missingArticleIds(anyList()))
                .thenReturn(ResponseResult.okResult(List.of(7)));

        task.reconcile();

        verify(publishExecutor, times(1)).syncToEs(7L);
    }

    @Test
    @DisplayName("search 不可用：整轮跳过，不误判为「没有缺失」也不补推")
    void searchUnavailableSkipsWholeRound() {
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(1L, 2L));
        when(searchClient.missingArticleIds(anyList()))
                .thenThrow(new RuntimeException("search 服务不可用"));

        task.reconcile();

        verify(publishExecutor, never()).syncToEs(any());
    }

    @Test
    @DisplayName("单篇补推失败：不影响其余文章继续补推")
    void singleRepairFailureDoesNotAbortRest() {
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(1L, 2L, 3L));
        when(searchClient.missingArticleIds(anyList()))
                .thenReturn(ResponseResult.okResult(Arrays.asList(1L, 2L, 3L)));
        org.mockito.Mockito.doThrow(new RuntimeException("ES 写失败")).when(publishExecutor).syncToEs(2L);

        task.reconcile();

        verify(publishExecutor, times(1)).syncToEs(1L);
        verify(publishExecutor, times(1)).syncToEs(3L);
    }

    @Test
    @DisplayName("缺失量超单轮上限：只补推 repairLimit 篇，其余留待下轮")
    void capsRepairPerRound() {
        task.repairLimit = 2;
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(1L, 2L, 3L, 4L));
        when(searchClient.missingArticleIds(anyList()))
                .thenReturn(ResponseResult.okResult(Arrays.asList(1L, 2L, 3L, 4L)));

        task.reconcile();

        // 上限 2：只补前 2 篇，其余留待下轮
        org.mockito.ArgumentCaptor<Long> captor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(publishExecutor, times(2)).syncToEs(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(1L, 2L), captor.getAllValues());
    }
}
