package com.heima.notification.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionManager 单元测试
 *
 * 覆盖在线用户上线/下线、在线判断、会话 ID 查询与在线人数统计。
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
        assertEquals("s1", sessionManager.getSessionId(100L));
        assertEquals(1, sessionManager.getOnlineCount());
    }

    @Test
    @DisplayName("userOffline → 下线并移除会话")
    void testUserOffline() {
        sessionManager.userOnline(100L, "s1");
        sessionManager.userOffline(100L);
        assertFalse(sessionManager.isOnline(100L));
        assertNull(sessionManager.getSessionId(100L));
        assertEquals(0, sessionManager.getOnlineCount());
    }

    @Test
    @DisplayName("多用户在线 → 统计正确的在线人数")
    void testOnlineCount() {
        sessionManager.userOnline(1L, "a");
        sessionManager.userOnline(2L, "b");
        sessionManager.userOnline(3L, "c");
        assertEquals(3, sessionManager.getOnlineCount());
        sessionManager.userOffline(2L);
        assertEquals(2, sessionManager.getOnlineCount());
    }

    @Test
    @DisplayName("未上线用户 → 在线判断为 false")
    void testNotOnline() {
        assertFalse(sessionManager.isOnline(999L));
        assertNull(sessionManager.getSessionId(999L));
    }
}