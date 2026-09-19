package com.heima.content.service.article.processor;

import com.heima.content.service.aigc.AigcDetectService;
import com.heima.model.article.pojos.ApArticle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AigcDetectProcessor 单元测试（C 方案：AIGC 快检并入审核责任链）
 *
 * <p>核心断言：检测结果必须**回填到链上实体**——后续 {@code SimilarityProcessor}
 * 读的是同一个 {@code ApArticle} 对象，只有回填后"水文不入向量库"才在同链内生效；
 * 同时验证 fail-open（检测异常不阻断发布、不触发重试）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AIGC 诚信检测处理器（审核链内）测试")
class AigcDetectProcessorTest {

    @Mock
    private AigcDetectService aigcDetectService;

    private AigcDetectProcessor processor() {
        return new AigcDetectProcessor(aigcDetectService);
    }

    private ApArticle article(Long id) {
        ApArticle a = new ApArticle();
        a.setId(id);
        return a;
    }

    @Test
    @DisplayName("flagged → 回填实体 is_aigc=1（后续入库处理器据此跳过）且继续执行")
    void flaggedBackfillsEntity() {
        when(aigcDetectService.detectAndFlagArticle(any(ApArticle.class), anyString()))
            .thenReturn(new AigcDetectService.AigcL1Outcome(85, true));
        ApArticle a = article(1L);

        boolean result = processor().process(a, "疑似水文正文", new AuditProcessorContext());

        assertTrue(result, "诚信检测不阻断审核链");
        assertEquals(1, a.getIsAigc(), "flagged 必须回填实体，否则后续入库读旧快照");
    }

    @Test
    @DisplayName("未 flagged → 不回填（只升不降，避免误清已有标记），保持实体原值")
    void notFlaggedKeepsOriginalValue() {
        when(aigcDetectService.detectAndFlagArticle(any(ApArticle.class), anyString()))
            .thenReturn(new AigcDetectService.AigcL1Outcome(30, false));
        ApArticle fresh = article(2L);
        ApArticle alreadyFlagged = article(3L);
        alreadyFlagged.setIsAigc(1);   // 历史已标记（如 L3 确认过）→ 不应被本次低分覆盖

        assertTrue(processor().process(fresh, "正常正文", new AuditProcessorContext()));
        assertTrue(processor().process(alreadyFlagged, "正常正文", new AuditProcessorContext()));

        assertNull(fresh.getIsAigc(), "未 flagged 不应写入标记");
        assertEquals(1, alreadyFlagged.getIsAigc(), "未 flagged 不应清除已有标记");
    }

    @Test
    @DisplayName("检测返回 null（无法判定）→ 不回填、不阻断")
    void nullOutcomeSkipsBackfill() {
        when(aigcDetectService.detectAndFlagArticle(any(ApArticle.class), anyString())).thenReturn(null);
        ApArticle a = article(4L);

        assertTrue(processor().process(a, "some content", new AuditProcessorContext()));

        assertNull(a.getIsAigc());
    }

    @Test
    @DisplayName("检测异常 → fail-open：继续审核链（不抛 AuditRetryableException），实体不变")
    void detectExceptionIsFailOpen() {
        when(aigcDetectService.detectAndFlagArticle(any(ApArticle.class), anyString()))
            .thenThrow(new RuntimeException("aigc service down"));
        ApArticle a = article(5L);

        boolean result = processor().process(a, "正文", new AuditProcessorContext());

        assertTrue(result, "诚信检测属治理增强，异常不得阻断发布");
        assertNull(a.getIsAigc());
    }

    @Test
    @DisplayName("正文为空 → 跳过检测，不调用检测服务")
    void blankContentSkipsDetect() {
        ApArticle a = article(6L);

        assertTrue(processor().process(a, "  ", new AuditProcessorContext()));

        verify(aigcDetectService, never()).detectAndFlagArticle(any(ApArticle.class), anyString());
        assertNull(a.getIsAigc());
    }
}
