package com.zhuri.coding.content.service.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 弹窗关闭记录（Redis Set）单测。
 *
 * <p>这个组件的全部价值就在"不惹事"：
 * <ol>
 *   <li><b>key 与成员形状</b>：{@code popup:closed:{popupId}} 一个 Set、成员 = userId
 *       —— Set 方案下编辑弹窗结束时间只需一条 EXPIRE，这是选它而不是
 *       String-per-user 的原因；</li>
 *   <li><b>TTL 语义</b>：上限 = 弹窗结束时间，下限 60s（到期弹窗的"到点前弹出、
 *       用户点关"竞态窗口也要能落记录）；</li>
 *   <li><b>全面 best-effort</b>：Redis 挂了所有方法都不许炸上游 ——
 *       丢数据最坏后果是重弹一次、自愈；fail-open（查不到=未关闭）同理。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("弹窗关闭记录（PopupCloseStore）")
class PopupCloseStoreTest {

    private static final long POPUP_ID = 30L;
    private static final long USER_ID = 77L;
    private static final String KEY = "popup:closed:" + POPUP_ID;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOps;

    @InjectMocks
    private PopupCloseStore store;

    /**
     * markClosed / isClosed 走 opsForSet；refreshTtl / purge 直接用 template。
     * 按 stubSpendOps 需求各自调用 —— Mockito 严格模式下，@BeforeEach 里统一 stub
     * 会让"只测 TTL/purge"的用例吃到 UnnecessaryStubbing。
     */
    private void stubSetOps() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    // ==================== 写入与查询 ====================

    @Nested
    @DisplayName("写入与查询")
    class MarkAndQuery {

        @Test
        @DisplayName("markClosed：SADD 成员=userId 字符串 + EXPIRE 剩余时间（key 形状固定）")
        void marksClosedWithRemainingTtl() {
            stubSetOps();
            Date end = new Date(System.currentTimeMillis() + 100_000L);
            when(redisTemplate.expire(eq(KEY), any(Duration.class))).thenReturn(true);

            store.markClosed(POPUP_ID, USER_ID, end);

            verify(setOps).add(KEY, String.valueOf(USER_ID));
            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(redisTemplate).expire(eq(KEY), ttl.capture());
            assertTrue(ttl.getValue().getSeconds() >= 60 && ttl.getValue().getSeconds() <= 100,
                "TTL 应约等于剩余时间：" + ttl.getValue());
        }

        @Test
        @DisplayName("结束时间已过 / 为空 → TTL 按 60s 下限兜底（不向 Redis 传非正数）")
        void floorsTtlAtSixtySeconds() {
            stubSetOps();
            when(redisTemplate.expire(eq(KEY), any(Duration.class))).thenReturn(true);

            store.markClosed(POPUP_ID, USER_ID, new Date(System.currentTimeMillis() - 5_000L));

            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(redisTemplate).expire(eq(KEY), ttl.capture());
            assertEquals(60, ttl.getValue().getSeconds(), "到期弹窗的关闭记录至少活一分钟");

            store.markClosed(POPUP_ID, USER_ID, null);
            verify(redisTemplate, org.mockito.Mockito.times(2))
                .expire(eq(KEY), ttl.capture());
            assertEquals(60, ttl.getAllValues().get(1).getSeconds());
        }

        @Test
        @DisplayName("isClosed：SISMEMBER 命中 / 未命中 / null 各归其位")
        void queriesMembership() {
            stubSetOps();
            when(setOps.isMember(KEY, String.valueOf(USER_ID))).thenReturn(true);
            assertTrue(store.isClosed(POPUP_ID, USER_ID));

            when(setOps.isMember(KEY, String.valueOf(USER_ID))).thenReturn(false);
            assertFalse(store.isClosed(POPUP_ID, USER_ID));

            when(setOps.isMember(KEY, String.valueOf(USER_ID))).thenReturn(null);
            assertFalse(store.isClosed(POPUP_ID, USER_ID), "null 视为未关闭");
        }
    }

    // ==================== TTL 维护与清理 ====================

    @Nested
    @DisplayName("TTL 维护与清理")
    class TtlAndPurge {

        @Test
        @DisplayName("refreshTtl：编辑结束时间后按新值刷 EXPIRE")
        void refreshesTtl() {
            Date end = new Date(System.currentTimeMillis() + 60_000L);
            when(redisTemplate.expire(eq(KEY), any(Duration.class))).thenReturn(true);

            store.refreshTtl(POPUP_ID, end);

            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(redisTemplate).expire(eq(KEY), ttl.capture());
            assertEquals(60, ttl.getValue().getSeconds(),
                "剩余不足 60s 时按下限兜底（Math.max 只在 60000ms 处生效）：" + ttl.getValue());
        }

        @Test
        @DisplayName("purge：删除弹窗时 DEL 关闭记录（兜底清孤儿 key）")
        void purgesCloseRecord() {
            store.purge(POPUP_ID);
            verify(redisTemplate).delete(KEY);
        }
    }

    // ==================== best-effort：Redis 故障不许炸上游 ====================

    @Nested
    @DisplayName("best-effort")
    class BestEffort {

        @Test
        @DisplayName("SADD 失败 → markClosed 吞异常，且不再去 EXPIRE")
        void swallowsAddFailure() {
            stubSetOps();
            when(setOps.add(anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> store.markClosed(POPUP_ID, USER_ID, new Date()));
            verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("SISMEMBER 失败 → isClosed 返回 false（fail-open：查不到=未关闭，重弹无害）")
        void swallowsQueryFailure() {
            stubSetOps();
            when(setOps.isMember(anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

            assertFalse(store.isClosed(POPUP_ID, USER_ID));
        }

        @Test
        @DisplayName("EXPIRE / DEL 失败 → refreshTtl / purge 吞异常")
        void swallowsTtlAndDeleteFailure() {
            when(redisTemplate.expire(anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
            when(redisTemplate.delete(anyString()))
                .thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> store.refreshTtl(POPUP_ID, new Date()));
            assertDoesNotThrow(() -> store.purge(POPUP_ID));
        }

        @Test
        @DisplayName("SADD 成功但 EXPIRE 失败 → 吞异常（孤儿 key 由删除弹窗时的 purge 兜底）")
        void swallowsExpireFailureAfterAdd() {
            stubSetOps();
            when(redisTemplate.expire(anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> store.markClosed(POPUP_ID, USER_ID, new Date()));
            verify(setOps).add(KEY, String.valueOf(USER_ID));
        }
    }
}
