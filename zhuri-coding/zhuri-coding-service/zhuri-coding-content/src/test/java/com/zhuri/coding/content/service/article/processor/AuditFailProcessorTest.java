package com.zhuri.coding.content.service.article.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.AuditRecordService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 审核失败处理器单测。
 *
 * <p>核心守护点：**“业务违规驳回”与“系统异常”共用 FAIL 终态，但面向作者的文案必须不同**。
 * 此前两者共用同一段硬编码的“因违反社区规范已被删除”，导致 AI 服务抖动时作者被误告知内容违规——
 * 本测试把该行为固化为契约。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("审核失败处理器（违规 / 系统异常文案分离）")
class AuditFailProcessorTest {

    @Mock
    private ApArticleMapper apArticleMapper;

    @Mock
    private AuditRecordService auditRecordService;

    @Mock
    private INotificationClient notificationClient;

    @InjectMocks
    private AuditFailProcessor processor;

    private ApArticle article() {
        ApArticle a = new ApArticle();
        a.setId(1001L);
        a.setAuthorId(7L);
        a.setTitle("测试标题");
        return a;
    }

    /** 抓取发给作者的通知内容（params.content 是 JSON 字符串，直接按文本断言） */
    @SuppressWarnings("rawtypes")
    private String captureNotificationContent() {
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        return String.valueOf(captor.getValue().get("content"));
    }

    @Test
    @DisplayName("业务违规驳回：文案含“违反社区规范”，不含“未被删除”")
    void violationKeepsViolationCopy() {
        ApArticle a = article();

        processor.handleFail(a, "含违规词");

        assertEquals(ApArticle.Status.FAIL.getCode(), a.getStatus());
        String content = captureNotificationContent();
        assertTrue(content.contains("违反社区规范"), "违规驳回应保留违规文案");
        assertFalse(content.contains("未被删除"), "违规驳回不应出现系统异常文案");
        assertTrue(content.contains("VIOLATION"), "应标记为违规处置类型");
    }

    @Test
    @DisplayName("系统异常：文案不得出现“违规/已被删除”，且明确告知文章未被删除")
    void systemErrorAvoidsViolationCopy() {
        ApArticle a = article();

        processor.handleSystemErrorFail(a, AuditFailProcessor.SYSTEM_ERROR_REASON);

        assertEquals(ApArticle.Status.FAIL.getCode(), a.getStatus());
        String content = captureNotificationContent();
        assertTrue(content.contains("未被删除"), "系统异常必须明确文章未被删除");
        assertTrue(content.contains("请稍后重新提交"), "系统异常应引导重新提交");
        assertFalse(content.contains("违反社区规范"), "系统异常绝不能出现违规措辞（本次修复的核心）");
        assertFalse(content.contains("已被删除"), "系统异常绝不能出现已删除措辞");
        assertTrue(content.contains("SYSTEM_ERROR"), "应标记为系统异常类型，供前端区分展示");
    }

    @Test
    @DisplayName("failReason 为空：退化为兜底原因并仍落终态（不把文章留在审核中）")
    void blankReasonStillRecordsFail() {
        ApArticle a = article();

        processor.handleFail(a, null);

        assertEquals(ApArticle.Status.FAIL.getCode(), a.getStatus(),
            "缺失败原因时也必须落终态，否则文章会永久停在 SUBMIT");
        assertTrue(a.getReason() != null && !a.getReason().isBlank());
        verify(apArticleMapper).updateById(any(ApArticle.class));
    }

    @Test
    @DisplayName("article 为 null：直接返回，不抛异常")
    void nullArticleIsNoop() {
        processor.handleFail(null, "任意原因");
        processor.handleSystemErrorFail(null, AuditFailProcessor.SYSTEM_ERROR_REASON);
    }
}
