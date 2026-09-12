package com.heima.content.service.ai.memory.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.common.redis.CacheService;
import com.heima.content.service.ai.memory.AiConversationMemoryService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话记忆持久化实现（Redis List 存储）。
 *
 * <p>Key 结构：{@code ai:memory:conv:{userId}}，TTL 7 天；
 * 追加一轮 = RIGHT PUSH 两条（user/assistant）→ LTRIM 保留最近 {@link AiConversationMemoryService#MAX_PERSISTED_MSGS} 条。
 * 全部读写异常 fail-open：Redis 抖动时 AI 问答主链路不受影响，仅丢失该轮记忆。
 */
@Slf4j
@Service
public class RedisConversationMemoryService implements AiConversationMemoryService {

    private static final String KEY_PREFIX = "ai:memory:conv:";

    /** 会话记忆 TTL */
    private static final Duration TTL = Duration.ofDays(7);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private CacheService cacheService;

    private String keyOf(Integer userId) {
        return KEY_PREFIX + userId;
    }

    private StringRedisTemplate redis() {
        return cacheService.getstringRedisTemplate();
    }

    @Override
    public List<Map<String, String>> load(Integer userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        try {
            List<String> raws = redis().opsForList().range(keyOf(userId), 0, -1);
            if (raws == null || raws.isEmpty()) {
                return new ArrayList<>();
            }
            List<Map<String, String>> turns = new ArrayList<>(raws.size());
            for (String raw : raws) {
                Map<String, String> turn = parse(raw);
                if (turn != null) {
                    turns.add(turn);
                }
            }
            return turns;
        } catch (Exception e) {
            log.warn("[AiMemory] 会话加载失败，userId={}", userId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void appendTurn(Integer userId, String question, String answer) {
        if (userId == null) {
            return;
        }
        try {
            StringRedisTemplate t = redis();
            String key = keyOf(userId);
            t.opsForList().rightPushAll(key, toJson("user", question), toJson("assistant", answer));
            // 滚动裁剪：仅保留最近 MAX_PERSISTED_MSGS 条，防止无限增长
            t.opsForList().trim(key, -MAX_PERSISTED_MSGS, -1);
            t.expire(key, TTL);
        } catch (Exception e) {
            log.warn("[AiMemory] 会话持久化失败，userId={}", userId, e);
        }
    }

    @Override
    public void clear(Integer userId) {
        if (userId == null) {
            return;
        }
        try {
            redis().delete(keyOf(userId));
            log.info("[AiMemory] 会话记忆已清空，userId={}", userId);
        } catch (Exception e) {
            log.warn("[AiMemory] 会话清空失败，userId={}", userId, e);
        }
    }

    private Map<String, String> parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(raw);
            Map<String, String> turn = new LinkedHashMap<>();
            turn.put("role", node.path("role").isTextual() ? node.path("role").asText() : "assistant");
            turn.put("content", node.path("content").asText(""));
            return turn;
        } catch (Exception e) {
            log.debug("[AiMemory] 跳过格式异常的会话消息: {}", raw);
            return null;
        }
    }

    private String toJson(String role, String content) {
        Map<String, String> turn = new LinkedHashMap<>();
        turn.put("role", role == null ? "assistant" : role);
        turn.put("content", content == null ? "" : content);
        try {
            return OBJECT_MAPPER.writeValueAsString(turn);
        } catch (Exception e) {
            // 极端兜底：直接拼接安全 JSON（role 为白名单、content 转义），保证不中断
            String safeContent = content == null ? "" : content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
            return "{\"role\":\"" + (role == null ? "assistant" : role) + "\",\"content\":\"" + safeContent + "\"}";
        }
    }
}