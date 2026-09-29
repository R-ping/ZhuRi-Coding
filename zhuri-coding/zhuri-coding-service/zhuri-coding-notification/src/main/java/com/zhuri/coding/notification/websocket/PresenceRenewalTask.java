package com.zhuri.coding.notification.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 在线状态续期：定期刷新本实例仍持有连接的那些用户的 TTL。
 *
 * <p>为什么需要它：{@link PresenceRegistry} 的过期时间是给"实例被 kill"兜底的——
 * 那时收不到 disconnect 事件，状态会残留在 Redis 里。但用户一直在线、两端又没什么动静时，
 * 也不会有新的 markOnline 来刷新 TTL，于是会被误判离线。这里按固定间隔续一次。
 *
 * <p>间隔要明显小于 TTL，否则续期赶不上过期。
 */
@Slf4j
@Component
public class PresenceRenewalTask {

    private final SessionManager sessionManager;
    private final PresenceRegistry presenceRegistry;

    @Autowired
    public PresenceRenewalTask(SessionManager sessionManager, PresenceRegistry presenceRegistry) {
        this.sessionManager = sessionManager;
        this.presenceRegistry = presenceRegistry;
    }

    @Scheduled(fixedDelay = 30_000)
    public void renew() {
        int count = 0;
        for (Long userId : sessionManager.getOnlineUserIds()) {
            presenceRegistry.touch(userId);
            count++;
        }
        if (count > 0) {
            log.debug("在线状态续期完成, 本实例在线用户数={}", count);
        }
    }
}
