package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.interaction.ApArticleReportMapper;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.pojos.ApArticleReport;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 举报回执与作者告知的投递单测。
 *
 * <p>这里守的不是算法而是**话有没有说对**。回执是这个功能唯一面向用户的出口，
 * 把"已下架"写成"未发现违规"、或者把结论张冠李戴，用户看到的是一次自相矛盾的答复 ——
 * 这类错误不会让任何测试变红，只会让举报的人不再举报。所以三种结论的文案各钉一条用例。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("举报回执投递（AdminReceiptDispatcher）")
class AdminReceiptDispatcherTest {

    private static final Long REPORT_ID = 6001L;
    private static final Long ARTICLE_ID = 8801L;
    private static final Integer REPORTER_ID = 3101;
    private static final Long AUTHOR_ID = 4101L;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private INotificationClient notificationClient;
    @Mock
    private ApArticleReportMapper reportMapper;
    @Mock
    private ApArticleMapper articleMapper;

    @InjectMocks
    private AdminReceiptDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // Lambda 包装器需要实体元信息（单测无 MyBatis 会话），否则回写 notify_status 时会抛
        // "can not find lambda cache for this entity"，而那条回写被 catch 吞掉 → 断言会变成
        // "update 没被调用"，把元信息问题伪装成业务缺陷。
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticleReport.class);
    }

    // ==================== 举报人回执 ====================

    @Test
    @DisplayName("回执·驳回：告诉举报人'未发现违规'，并把处置说明原样带上")
    void receiptForReject() throws Exception {
        givenReport(ApArticleReport.RESULT_REJECT, "内容属于正常观点表达");
        givenArticle();
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        dispatcher.deliverReportReceipt(REPORT_ID);

        Map<String, Object> params = captureParams();
        assertEquals(REPORTER_ID.intValue(), ((Number) params.get("userId")).intValue());
        assertEquals(ArticleConstants.NOTIFICATION_TYPE_SYSTEM, ((Number) params.get("type")).intValue());
        assertEquals(String.valueOf(REPORT_ID), params.get("sourceId"));
        String message = messageOf(params);
        assertTrue(message.contains("未发现违规"), "驳回必须说清'没违规'，不能含糊成'已处理'：" + message);
        assertTrue(message.contains("内容属于正常观点表达"), "处置说明要原样回执给举报人");
        assertFalse(message.contains("下架"), message);
    }

    @Test
    @DisplayName("回执·警告作者：告诉举报人'已警告作者'，而不是'已下架'")
    void receiptForWarnAuthor() throws Exception {
        givenReport(ApArticleReport.RESULT_WARN_AUTHOR, "存在引战表述");
        givenArticle();
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        dispatcher.deliverReportReceipt(REPORT_ID);

        String message = messageOf(captureParams());
        assertTrue(message.contains("警告"), message);
        assertFalse(message.contains("已被下架"), "只是警告就不能说成下架：" + message);
    }

    @Test
    @DisplayName("回执·下架：明确告知内容已下架")
    void receiptForTakeDown() throws Exception {
        givenReport(ApArticleReport.RESULT_TAKE_DOWN, "涉及人身攻击");
        givenArticle();
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        dispatcher.deliverReportReceipt(REPORT_ID);

        String message = messageOf(captureParams());
        assertTrue(message.contains("已被下架"), message);
        assertTrue(message.contains("涉及人身攻击"), message);
    }

    @Test
    @DisplayName("回执发送失败：先标 notify_status 再抛出异常 —— 吞掉这条回执就永远不发了")
    void receiptFailureMarksStatusAndThrows() {
        givenReport(ApArticleReport.RESULT_REJECT, "核实后未见违规");
        givenArticle();
        when(notificationClient.createNotification(any()))
            .thenThrow(new RuntimeException("通知服务不可用"));

        assertThrows(IllegalStateException.class, () -> dispatcher.deliverReportReceipt(REPORT_ID));

        // 单语句更新：把发送状态记成"失败"，便于对账时看出卡在哪一步
        verify(reportMapper).update(any(), any());
    }

    @Test
    @DisplayName("举报记录不存在：跳过而不是抛异常（重试也救不回来，不该占着重试预算）")
    void receiptSkippedWhenReportMissing() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(null);

        dispatcher.deliverReportReceipt(REPORT_ID);

        verify(notificationClient, never()).createNotification(any());
        verify(reportMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("文章已不存在：标题兜底为'无标题'，文案里不出现 null")
    void receiptUsesFallbackTitle() throws Exception {
        // 刻意不桩 articleMapper：模拟文章已被物理删除
        givenReport(ApArticleReport.RESULT_REJECT, "核实后未见违规");
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        dispatcher.deliverReportReceipt(REPORT_ID);

        Map<String, Object> params = captureParams();
        assertEquals("无标题", contentOf(params).get("articleTitle"));
        assertFalse(messageOf(params).contains("null"));
    }

    // ==================== 作者告知 ====================

    @Test
    @DisplayName("告知作者·下架：说明已下架并给出申诉出口")
    void notifyAuthorTakeDown() throws Exception {
        givenArticle();
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        dispatcher.notifyAuthorOfHandling(ARTICLE_ID, AUTHOR_ID, ApArticleReport.RESULT_TAKE_DOWN, "内容违规");

        Map<String, Object> params = captureParams();
        assertEquals(AUTHOR_ID.longValue(), ((Number) params.get("userId")).longValue());
        assertEquals(String.valueOf(ARTICLE_ID), params.get("sourceId"));
        String message = messageOf(params);
        assertTrue(message.contains("已被平台下架"), message);
        assertTrue(message.contains("申诉"), "下架是重处置，必须告诉作者可以申诉");
    }

    @Test
    @DisplayName("告知作者·警告：说清'仅警告、内容未下架'，避免作者以为文章没了")
    void notifyAuthorWarn() throws Exception {
        givenArticle();
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        dispatcher.notifyAuthorOfHandling(ARTICLE_ID, AUTHOR_ID, ApArticleReport.RESULT_WARN_AUTHOR, "标题党");

        String message = messageOf(captureParams());
        assertTrue(message.contains("仅作警告"), message);
        assertTrue(message.contains("未被下架"), message);
        assertTrue(message.contains("标题党"), message);
    }

    @Test
    @DisplayName("作者ID缺失：跳过告知，不产生一条 userId 为空的站内信")
    void notifyAuthorSkippedWithoutAuthor() {
        dispatcher.notifyAuthorOfHandling(ARTICLE_ID, null, ApArticleReport.RESULT_TAKE_DOWN, "内容违规");

        verify(notificationClient, never()).createNotification(any());
    }

    // ==================== 工具 ====================

    /** 只桩举报记录，不桩文章：需要标题的用例自己再调 {@link #givenArticle()} */
    private void givenReport(int handleResult, String handleReason) {
        ApArticleReport report = new ApArticleReport();
        report.setId(REPORT_ID);
        report.setArticleId(ARTICLE_ID);
        report.setUserId(REPORTER_ID);
        report.setAuthorId(AUTHOR_ID);
        report.setStatus(ApArticleReport.STATUS_HANDLED);
        report.setHandleResult(handleResult);
        report.setHandleReason(handleReason);
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report);
    }

    private void givenArticle() {
        when(articleMapper.selectById(ARTICLE_ID)).thenReturn(article());
    }

    private ApArticle article() {
        ApArticle article = new ApArticle();
        article.setId(ARTICLE_ID);
        article.setTitle("被举报的文章");
        article.setAuthorId(AUTHOR_ID);
        return article;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureParams() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        return captor.getValue();
    }

    /** 站内信 content 是 JSON 字符串，取出来看文案 */
    private String messageOf(Map<String, Object> params) throws Exception {
        return String.valueOf(contentOf(params).get("message"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contentOf(Map<String, Object> params) throws Exception {
        return JSON.readValue(String.valueOf(params.get("content")), Map.class);
    }
}
