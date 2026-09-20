package com.heima.content.event;

import com.heima.content.service.article.ApArticleEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * ArticlePublishEventListener 单元测试
 *
 * <p>验证异步监听器把发布事件转交 ApArticleEventService#executePublish 执行，
 * 且执行异常不外抛（由 20s 扫描补偿兜底）。
 * 注：@Async/@EventListener 在无 Spring 上下文的单测中退化为普通方法调用，只验证委托与异常边界。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文章发布异步监听器测试")
class ArticlePublishEventListenerTest {

    @Mock
    private ApArticleEventService apArticleEventService;

    @InjectMocks
    private ArticlePublishEventListener listener;

    @Test
    @DisplayName("收到发布事件 → 委托 executePublish 执行")
    void delegatesToExecutePublish() {
        listener.onArticlePublish(new ArticlePublishEvent(1L));

        verify(apArticleEventService).executePublish(1L);
    }

    @Test
    @DisplayName("executePublish 异常 → 吞掉不外抛（扫描补偿兜底）")
    void exceptionSwallowed() {
        doThrow(new RuntimeException("mark published failed"))
                .when(apArticleEventService).executePublish(1L);

        assertDoesNotThrow(() -> listener.onArticlePublish(new ArticlePublishEvent(1L)));
    }
}
