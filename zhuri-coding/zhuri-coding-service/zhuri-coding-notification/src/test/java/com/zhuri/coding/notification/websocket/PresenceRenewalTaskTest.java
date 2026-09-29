package com.zhuri.coding.notification.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PresenceRenewalTask 单元测试
 *
 * TTL 是给"实例被 kill"兜底的，但用户持续在线又没什么动静时不会有新的 markOnline，
 * 得靠这里按本实例实际持有的连接定期续期，否则会被误判离线。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceRenewalTask 在线状态续期")
class PresenceRenewalTaskTest {

    @Mock
    private SessionManager sessionManager;

    @Mock
    private PresenceRegistry presenceRegistry;

    @InjectMocks
    private PresenceRenewalTask task;

    @Test
    @DisplayName("对本实例在线的每个用户各续一次")
    void testRenewAll() {
        when(sessionManager.getOnlineUserIds()).thenReturn(Set.of(100L, 200L));

        task.renew();

        verify(presenceRegistry).touch(100L);
        verify(presenceRegistry).touch(200L);
    }

    @Test
    @DisplayName("无在线用户 → 不调用 Redis")
    void testNoOnlineUser() {
        when(sessionManager.getOnlineUserIds()).thenReturn(Set.of());

        task.renew();

        verify(presenceRegistry, never()).touch(org.mockito.ArgumentMatchers.anyLong());
    }
}
