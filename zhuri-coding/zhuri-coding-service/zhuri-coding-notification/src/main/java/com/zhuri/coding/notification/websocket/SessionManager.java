package com.zhuri.coding.notification.websocket;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WebSocket 在线会话管理。
 * <p>
 * 支持同一用户多端在线：userId → Set&lt;sessionId&gt;，
 * 一端断开只移除对应 sessionId，其余端不受影响（此前按 userId 整体移除会导致多端在线时一端断线全体掉线）。
 */
@Slf4j
@Component
public class SessionManager {

    private final Map<Long, Set<String>> onlineUsers = new ConcurrentHashMap<>();

    /** 用户建立连接：登记其会话 ID（支持多端同时在线） */
    public void userOnline(Long userId, String sessionId) {
        onlineUsers.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        log.info("User online: userId={}, sessionId={}, onlineSessions={}", userId, sessionId, onlineUsers.get(userId).size());
    }

    /** 用户断开指定会话：只移除该 sessionId；全部会话断开后才摘除用户在线状态 */
    public void userOffline(Long userId, String sessionId) {
        Set<String> sessions = onlineUsers.get(userId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                onlineUsers.remove(userId);
            }
            log.info("User offline: userId={}, sessionId={}", userId, sessionId);
        }
    }

    public boolean isOnline(Long userId) {
        Set<String> sessions = onlineUsers.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    /** 获取用户全部在线会话 ID（多端场景） */
    public Set<String> getSessionIds(Long userId) {
        Set<String> sessions = onlineUsers.get(userId);
        return sessions != null ? sessions : Set.of();
    }

    public int getOnlineCount() {
        return onlineUsers.size();
    }
}
