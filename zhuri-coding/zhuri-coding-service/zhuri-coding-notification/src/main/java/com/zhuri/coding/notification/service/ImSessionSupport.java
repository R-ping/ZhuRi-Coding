package com.zhuri.coding.notification.service;

import com.zhuri.coding.model.notification.pojos.ImSession;
import com.zhuri.coding.notification.mapper.ImSessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

/**
 * 私信会话的「查到就返回、没有就建」。
 *
 * <p>会话在 {@code session_key}（小ID_大ID）上有唯一索引，两个人同时发起会话时
 * 必然有一方插入失败；失败方回读既有会话即可，不需要锁。
 * 抽成静态工具是因为发消息与自动私信两条链路都要用它，而它们分属不同的 Service。
 */
@Slf4j
public final class ImSessionSupport {

    private ImSessionSupport() {
    }

    public static String sessionKey(Long uid1, Long uid2) {
        return Math.min(uid1, uid2) + "_" + Math.max(uid1, uid2);
    }

    public static ImSession getOrInsert(ImSessionMapper mapper, Long uid1, Long uid2) {
        String key = sessionKey(uid1, uid2);
        ImSession session = mapper.selectBySessionKey(key);
        if (session != null) {
            return session;
        }
        session = new ImSession();
        session.setSessionKey(key);
        session.setUser1Id(Math.min(uid1, uid2));
        session.setUser2Id(Math.max(uid1, uid2));
        session.setIsActive(0);
        session.setUser1UnreadCount(0);
        session.setUser2UnreadCount(0);
        session.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(session);
            return session;
        } catch (DuplicateKeyException e) {
            ImSession existing = mapper.selectBySessionKey(key);
            if (existing == null) {
                throw new IllegalStateException("会话创建冲突后回读失败, sessionKey=" + key, e);
            }
            return existing;
        }
    }
}
