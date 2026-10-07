package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsCommentMapper;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.pins.pojos.ApPinsComment;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 折叠告知的站内信单测。
 *
 * <p>与举报回执同样的理由：这里守的不是算法而是**话有没有说对**。
 * 折叠是一条很别扭的处置 —— 评论者的评论还在、他自己也看得见，但列表里没了。
 * 站内信如果只写"已处理"，作者会理解成限流或 bug；必须说清三件事：
 * 是折叠不是删除、别人看不到、可以申诉。恢复方向的文案则要明确"已恢复正常展示"，
 * 否则作者下次不敢再评论。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("折叠告知投递（AdminContentNotifier）")
class AdminContentNotifierTest {

    private static final Long COMMENT_ID = 7101L;
    private static final Integer AUTHOR_ID = 3301;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private INotificationClient notificationClient;
    @Mock
    private ApCommentMapper commentMapper;
    @Mock
    private ApPinsCommentMapper pinsCommentMapper;

    @InjectMocks
    private AdminContentNotifier notifier;

    @Test
    @DisplayName("折叠告知：说清'被折叠、别人看不到、可申诉'，并原样带上理由")
    void foldNoticeExplainsWhatHappened() throws Exception {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment("你懂什么，别乱说"));
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        notifier.notifyCommentAuthor("COMMENT", COMMENT_ID, true, "含人身攻击");

        Map<String, Object> params = captureParams();
        assertEquals(AUTHOR_ID.longValue(), ((Number) params.get("userId")).longValue());
        assertEquals(ArticleConstants.NOTIFICATION_TYPE_SYSTEM, ((Number) params.get("type")).intValue());
        assertEquals(String.valueOf(COMMENT_ID), params.get("sourceId"));

        String message = messageOf(params);
        assertTrue(message.contains("折叠"), message);
        assertTrue(message.contains("不会看到"), "要说清别人看不到，否则作者会以为只是限流：" + message);
        assertTrue(message.contains("含人身攻击"), "理由要原样告知，否则作者不知道改什么");
        assertTrue(message.contains("申诉"), "折叠必须给出纠正出口");
        assertFalse(message.contains("删除"), "折叠不是删除，文案不能让人以为内容没了：" + message);
    }

    @Test
    @DisplayName("恢复告知：明确'已恢复正常展示'，不要在恢复时再提申诉")
    void unfoldNoticeSaysRestored() throws Exception {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment("我觉得这个方案有问题"));
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        notifier.notifyCommentAuthor("COMMENT", COMMENT_ID, false, "复核认定属正常表达");

        String message = messageOf(captureParams());
        assertTrue(message.contains("恢复正常展示"), message);
        assertTrue(message.contains("复核认定属正常表达"), message);
        assertFalse(message.contains("提交申诉"), "已经恢复还叫人家申诉会让人以为没恢复：" + message);
    }

    @Test
    @DisplayName("正文摘录：折行压平、超长截断 —— 站内信是一行文案，换行会把它撑坏")
    void excerptIsFlattenedAndTruncated() throws Exception {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment("第一行\n第二行\t带制表符   " + "很长".repeat(40)));
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        notifier.notifyCommentAuthor("COMMENT", COMMENT_ID, true, "含人身攻击");

        Map<String, Object> content = contentOf(captureParams());
        String message = String.valueOf(content.get("message"));
        assertEquals(String.valueOf(COMMENT_ID), content.get("commentId"));
        assertFalse(message.contains("\n"), "摘要里的换行必须被压平");
        assertFalse(message.contains("\t"), "摘要里的制表符必须被压平");
        assertTrue(message.contains("…"), "超长正文要截断并留省略号，不能整段搬进站内信");
    }

    @Test
    @DisplayName("沸点评论：走本表查询，不误查文章评论表")
    void pinsCommentUsesOwnMapper() throws Exception {
        ApPinsComment c = new ApPinsComment();
        c.setId(COMMENT_ID);
        c.setUserId(AUTHOR_ID);
        c.setContent("加我微信卖课");
        when(pinsCommentMapper.selectById(COMMENT_ID)).thenReturn(c);
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult(1));

        notifier.notifyCommentAuthor("PINS_COMMENT", COMMENT_ID, true, "软广");

        verify(commentMapper, never()).selectById(any());
        Map<String, Object> content = contentOf(captureParams());
        assertEquals("PINS_COMMENT", content.get("targetType"));
        assertEquals("COMMENT_FOLD", content.get("adminHandleType"));
    }

    @Test
    @DisplayName("评论不存在：跳过而不是抛异常（重试也救不回来，不该占重试预算）")
    void skippedWhenCommentMissing() {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(null);

        notifier.notifyCommentAuthor("COMMENT", COMMENT_ID, true, "含人身攻击");

        verify(notificationClient, never()).createNotification(any());
    }

    @Test
    @DisplayName("评论者账号缺失：跳过，不产生一条 userId 为空的站内信")
    void skippedWhenAuthorMissing() {
        ApComment c = comment("匿名");
        c.setUserId(null);
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(c);

        notifier.notifyCommentAuthor("COMMENT", COMMENT_ID, true, "含人身攻击");

        verify(notificationClient, never()).createNotification(any());
    }

    @Test
    @DisplayName("类型无法识别：跳过并告警，绝不退化成'给某个默认表发通知'")
    void skippedWhenTypeUnknown() {
        notifier.notifyCommentAuthor("ARTICLE", COMMENT_ID, true, "含人身攻击");

        verify(commentMapper, never()).selectById(any());
        verify(pinsCommentMapper, never()).selectById(any());
        verify(notificationClient, never()).createNotification(any());
    }

    @Test
    @DisplayName("通知发送失败：异常外抛交给本地消息表重试（折叠告知丢了作者就永远不知道为什么）")
    void sendFailurePropagates() {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment("你懂什么"));
        when(notificationClient.createNotification(any()))
            .thenThrow(new RuntimeException("通知服务不可用"));

        assertThrows(RuntimeException.class,
            () -> notifier.notifyCommentAuthor("COMMENT", COMMENT_ID, true, "含人身攻击"));
    }

    // ==================== 工具 ====================

    private ApComment comment(String content) {
        ApComment c = new ApComment();
        c.setId(COMMENT_ID);
        c.setUserId(AUTHOR_ID);
        c.setContent(content);
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureParams() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        return captor.getValue();
    }

    private String messageOf(Map<String, Object> params) throws Exception {
        return String.valueOf(contentOf(params).get("message"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contentOf(Map<String, Object> params) throws Exception {
        return JSON.readValue(String.valueOf(params.get("content")), Map.class);
    }
}
