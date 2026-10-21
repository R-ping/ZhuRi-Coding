package com.zhuri.coding.content.schedule.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
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
 * 文章索引双向对账巡检测试。
 *
 * <p>重点覆盖"对账不能把故障伪装成正常"这条语义：search 不可用时必须整轮跳过，
 * 而不是当成"没有缺失"安静地什么都不做；以及正反两个方向互不牵连 ——
 * 反向清理失败不能推翻已经做完的补推。
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
        task.ghostLimit = 50;
        // 反向取数默认"索引里什么都没有"：不关心的用例不必逐个 stub
        lenient().when(searchClient.indexedArticleIds(any()))
                .thenReturn(ResponseResult.okResult(Collections.emptyList()));
        lenient().when(searchClient.removeArticleIndexBatch(anyList()))
                .thenReturn(ResponseResult.okResult(AppHttpCodeEnum.SUCCESS));
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

    // ==================== 反向：清理幽灵文档 ====================

    @Test
    @DisplayName("反向：索引里有、库里不可见的文档被批量清理，可见的不动")
    void removesGhostDocs() {
        when(searchClient.indexedArticleIds(any()))
                .thenReturn(ResponseResult.okResult(Arrays.asList(1L, 2L, 3L)));
        // 库里只剩 1 还可见：2、3 是幽灵（已删除 / 已下架 / 压根不存在）
        when(apArticleMapper.selectVisibleIds(anyList()))
                .thenReturn(Collections.singletonList(1L));

        task.reconcile();

        verify(searchClient, times(1)).removeArticleIndexBatch(List.of(2L, 3L));
        verify(searchClient, never()).removeArticleIndexBatch(List.of(1L));
    }

    @Test
    @DisplayName("反向：索引与库一致时不做任何清理")
    void noGhostNoRemoval() {
        when(searchClient.indexedArticleIds(any()))
                .thenReturn(ResponseResult.okResult(Arrays.asList(1L, 2L)));
        when(apArticleMapper.selectVisibleIds(anyList()))
                .thenReturn(Arrays.asList(1L, 2L));

        task.reconcile();

        verify(searchClient, never()).removeArticleIndexBatch(anyList());
    }

    @Test
    @DisplayName("反向：幽灵量超单轮上限时只清理一部分，其余留待下轮")
    void capsGhostRemovalPerRound() {
        task.ghostLimit = 1;
        when(searchClient.indexedArticleIds(any()))
                .thenReturn(ResponseResult.okResult(Arrays.asList(1L, 2L, 3L)));
        when(apArticleMapper.selectVisibleIds(anyList()))
                .thenReturn(Collections.emptyList());

        task.reconcile();

        org.mockito.ArgumentCaptor<List<Long>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(searchClient, times(1)).removeArticleIndexBatch(captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    @Test
    @DisplayName("反向：清理失败不抛异常（下轮对账会再发现），也不影响已完成的补推")
    void ghostRemovalFailureDoesNotBreakRound() {
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(List.of(10L));
        when(searchClient.missingArticleIds(anyList()))
                .thenReturn(ResponseResult.okResult(List.of(10L)));
        when(searchClient.indexedArticleIds(any()))
                .thenReturn(ResponseResult.okResult(Arrays.asList(1L, 2L)));
        when(apArticleMapper.selectVisibleIds(anyList()))
                .thenReturn(Collections.emptyList());
        when(searchClient.removeArticleIndexBatch(anyList()))
                .thenThrow(new RuntimeException("search 服务不可用"));

        assertDoesNotThrow(() -> task.reconcile());

        verify(publishExecutor, times(1)).syncToEs(10L);
    }

    @Test
    @DisplayName("反向取数失败：已完成的补推不受影响（不回头否定正向结果）")
    void reverseFailureKeepsForwardRepair() {
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(List.of(10L));
        when(searchClient.missingArticleIds(anyList()))
                .thenReturn(ResponseResult.okResult(List.of(10L)));
        when(searchClient.indexedArticleIds(any()))
                .thenThrow(new RuntimeException("search 服务不可用"));

        task.reconcile();

        verify(publishExecutor, times(1)).syncToEs(10L);
    }

    @Test
    @DisplayName("正向失败时反向也跳过（search 不可用，两个方向都做不了）")
    void forwardFailureSkipsReverse() {
        when(apArticleMapper.selectPublishedIdsSince(any(Date.class), anyInt()))
                .thenReturn(List.of(1L));
        when(searchClient.missingArticleIds(anyList()))
                .thenThrow(new RuntimeException("search 服务不可用"));

        task.reconcile();

        verify(searchClient, never()).indexedArticleIds(any());
    }
}
