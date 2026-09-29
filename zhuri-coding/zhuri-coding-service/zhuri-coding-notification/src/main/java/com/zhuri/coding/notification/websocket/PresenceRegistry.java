package com.zhuri.coding.notification.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 全局在线状态：userId → 当前持有它连接的实例集合。
 *
 * <p>存在 Redis 里，因为{@link SessionManager} 只能看到**本实例**的连接——它作为"本实例连接表"
 * 是对的，但拿它判断"某个用户在不在别的地方在线"就错了：多实例部署时，接收者连在另一个实例上，
 * 本实例的 {@code isOnline()} 恒为 false，实时推送会静默失效（消息已落库，表现为"时不时不实时"）。
 *
 * <p>用 Hash 而不是 String：同一个用户多端在线时，每个实例各占一个 field，
 * 一端断开只删自己的 field，不影响其他端。
 *
 * <p>TTL 是给"实例被 kill"兜底的：那种情况收不到 disconnect 事件，状态会残留在 Redis 里。
 * 只要 TTL 明显大于续期间隔，正常在线的用户不会被误判离线。
 */
@Slf4j
@Component
public class PresenceRegistry {

    private static final String KEY_PREFIX = "im:presence:";

    /** TTL 需明显大于 {@link PresenceRenewalTask} 的续期间隔 */
    static final long TTL_SECONDS = 120;

    @Autowired(required = false)
    private StringRedisTemplate redis;

    /** 本实例标识，启动时生成；多实例下用于区分各自持有的连接 */
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    public String getInstanceId() {
        return instanceId;
    }

    /** 连接建立：登记本实例持有该用户的一个连接，并刷新 TTL */
    public void markOnline(Long userId) {
        if (redis == null || userId == null) {
            return;
        }
        try {
            String key = key(userId);
            redis.opsForHash().put(key, instanceId, String.valueOf(System.currentTimeMillis()));
            redis.expire(key, Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception e) {
            log.warn("登记在线状态失败, userId={}", userId, e);
        }
    }

    /** 连接断开：只摘掉本实例这一端，用户在其他实例上的连接不受影响 */
    public void markOffline(Long userId) {
        if (redis == null || userId == null) {
            return;
        }
        try {
            redis.opsForHash().delete(key(userId), instanceId);
        } catch (Exception e) {
            log.warn("清除在线状态失败, userId={}", userId, e);
        }
    }

    /**
     * 用户是否在**任意实例**上在线。
     * Redis 不可用时返回 false——宁可让调用方走"离线"分支（下次打开再拉），
     * 也不要基于一份残缺的状态判断。
     */
    public boolean isOnline(Long userId) {
        if (redis == null || userId == null) {
            return false;
        }
        try {
            Long size = redis.opsForHash().size(key(userId));
            return size != null && size > 0;
        } catch (Exception e) {
            log.warn("查询在线状态失败, userId={}", userId, e);
            return false;
        }
    }

    /** 续期：用户持续在线时刷新 TTL，避免被当成残留回收 */
    public void touch(Long userId) {
        if (redis == null || userId == null) {
            return;
        }
        try {
            redis.expire(key(userId), Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception e) {
            log.warn("刷新在线状态失败, userId={}", userId, e);
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
