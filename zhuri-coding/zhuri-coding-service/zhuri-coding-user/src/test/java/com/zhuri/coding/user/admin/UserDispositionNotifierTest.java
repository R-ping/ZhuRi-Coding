package com.zhuri.coding.user.admin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.common.constants.ArticleConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 处置通知投递单测。
 *
 * <p>断言的是<b>跨服务契约</b>：通知服务的 {@code createNotification} 只认
 * {@code userId / type / sourceId / content}，其中 {@code content} 是一段 JSON 字符串，
 * 前端按里面的字段渲染。契约错一格不会报错，只会表现为"通知列表里多一条看不懂的东西"，
 * 所以这里逐字段钉住。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("处置通知投递（UserDispositionNotifier）")
class UserDispositionNotifierTest {

    private static final String TARGET = "1001";

    @Mock
    private INotificationClient notificationClient;

    @InjectMocks
    private UserDispositionNotifier notifier;

    @SuppressWarnings("unchecked")
    private JSONObject capturedContent() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> params = captor.getValue();
        // userId 必须是数字：通知服务的实现是 Long.valueOf(userId.toString())，
        // 传成 "id=1001" 这类字符串会抛 NumberFormatException
        assertEquals(1001L, params.get("userId"));
        assertEquals(ArticleConstants.NOTIFICATION_TYPE_SYSTEM, params.get("type"));
        assertEquals(TARGET, params.get("sourceId"));
        Object content = params.get("content");
        assertTrue(content instanceof String, "content 必须是 JSON 字符串，不是嵌套对象");
        return JSON.parseObject((String) content);
    }

    @Test
    @DisplayName("警告：content 带原文案与 adminHandleType，前端据此区分处置类型")
    void warnPayload() {
        notifier.notify(TARGET, UserDispositionNotifier.HANDLE_WARN, "你的账号收到第 1 次警告。原因：刷屏");

        JSONObject content = capturedContent();
        assertEquals("你的账号收到第 1 次警告。原因：刷屏", content.getString("message"));
        assertEquals("system", content.getString("notification_type"));
        assertEquals(UserDispositionNotifier.HANDLE_WARN, content.getString("adminHandleType"));
    }

    @Test
    @DisplayName("封禁与解封用的是两个不同的 handleType，前端才能给出不同入口")
    void handleTypesAreDistinct() {
        assertEquals("USER_BAN", UserDispositionNotifier.HANDLE_BAN);
        assertEquals("USER_UNBAN", UserDispositionNotifier.HANDLE_UNBAN);
        assertTrue(!UserDispositionNotifier.HANDLE_BAN.equals(UserDispositionNotifier.HANDLE_UNBAN)
            && !UserDispositionNotifier.HANDLE_WARN.equals(UserDispositionNotifier.HANDLE_BAN));
    }

    @Test
    @DisplayName("收件人为空直接拒绝，不发一条没有收件人的通知")
    void rejectsBlankTarget() {
        assertThrows(IllegalArgumentException.class,
            () -> notifier.notify("  ", UserDispositionNotifier.HANDLE_BAN, "msg"));
        verify(notificationClient, never()).createNotification(any());
    }

    @Test
    @DisplayName("投递异常原样抛出：失败语义由调用方决定（警告要整体失败、封禁只记日志）")
    void propagatesClientFailure() {
        RuntimeException boom = new RuntimeException("通知服务不可用");
        org.mockito.Mockito.doThrow(boom).when(notificationClient).createNotification(any());

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> notifier.notify(TARGET, UserDispositionNotifier.HANDLE_BAN, "msg"));

        assertEquals(boom, thrown);
    }
}
