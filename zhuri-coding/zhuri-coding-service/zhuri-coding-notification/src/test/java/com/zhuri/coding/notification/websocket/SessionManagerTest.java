package com.heima.notification.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionManager 单元测试
 *
 * 覆盖在线用户上线/下线、多端在线、在线判断与在线人数统计。
 */
@DisplayName("SessionManager WebSocket 会话管理")
class SessionManagerTest {

    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager();
    }

    @Test
    @DisplayName("userOnline → 上线并记录会话 ID")
    void testUserOnline() {
        sessionManager.userOnline(100L, "s1");
        assertTrue(sessionManager.isOnline(100L));
        assertTrue(sessionManager.getSessionIds(100L).contains("s1"));
        assertEquals(1, sessionManager.getOnlineCount());
    }

    @Test
    @DisplayName("userOffline → 按 sessionId 精确下线；全部会话断开后移除在线状态")
    void testUserOffline() {
        sessionManager.userOnline(100L, "s1");
        // 多端在线：同用户两个会话
        sessionManager.userOnline(100L, "s2");
        assertEquals(1, sessionManager.getOnlineCount());

        // 只断开一个会话：仍在线
        sessionManager.userOffline(100L, "s1");
        assertTrue(sessionManager.isOnline(100L));
        assertEquals(1, sessionManager.getSessionIds(100L).size());

        // 最后一个会话断开：下线
        sessionManager.userOffline(100L, "s2");
        assertFalse(sessionManager.isOnline(100L));
        assertEquals(0, sessionManager.getOnlineCount());
    }

    @Test
    @DisplayName("多用户在线 → 统计正确的在线人数")
    void testOnlineCount() {
        sessionManager.userOnline(1L, "a");
        sessionManager.userOnline(2L, "b");
        sessionManager.userOnline(3L, "c");
        assertEquals(3, sessionManager.getOnlineCount());
        sessionManager.userOffline(2L, "b");
        assertEquals(2, sessionManager.getOnlineCount());
    }

    @Test
    @DisplayName("未上线用户 → 在线判断为 false")
    void testNotOnline() {
        assertFalse(sessionManager.isOnline(999L));
        assertTrue(sessionManager.getSessionIds(999L).isEmpty());
    }
}
