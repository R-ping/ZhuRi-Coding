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
 *
 * <p>P2-3b 摘要压缩：会话达到 {@link AiConversationMemoryService#COMPRESS_THRESHOLD_MSGS} 条时，
 * 把最早 {@link AiConversationMemoryService#COMPRESS_BATCH_MSGS} 条用 LLM 压成一条
 * 「【早期对话摘要】」消息放回头部（role=assistant，前端按普通助手消息渲染天然兼容），
 * 上下文 token 随长会话回落而非线性膨胀。压缩流程：
 * <ol>
 *   <li>同步段（appendTurn 尾部）：长度检查 + SETNX 抢互斥锁，防止并发重复压缩；</li>
 *   <li>异步段（独立单线程池）：LRANGE 快照 → LLM 摘要 → LTRIM 裁掉旧头部 → LPUSH 摘要 → 刷 TTL。</li>
 * </ol>
 * 两步写回均为 Redis 原子命令且只动头部，与并发的 appendTurn（RPUSH 尾部）天然不冲突；
 * 摘要失败仅本次放弃，下次 appendTurn 再次触发，fail-open。
 */
@Slf4j
@Service
public class RedisConversationMemoryService implements AiConversationMemoryService {

    private static final String KEY_PREFIX = "ai:memory:conv:";

    /** 压缩互斥锁 Key 前缀 */
    private static final String COMPRESS_LOCK_PREFIX = "ai:memory:compress:lock:";

    /** 会话记忆 TTL */
    private static final Duration TTL = Duration.ofDays(7);

    /** 摘要消息前缀（前端渲染为普通助手消息，天然兼容） */
    private static final String SUMMARY_PREFIX = "【早期对话摘要】";

    /** 摘要压缩兜底提示词（Prompt 注册表 key=memory_compress，未注册/未注入时使用） */
    private static final String COMPRESS_SYSTEM_FALLBACK =
        "你是会话记忆压缩器。把下面的 AI 问答对话压缩为要点摘要，保留：用户关注的主题方向、"
            + "提及的特定技术/事实、未解决或有后续倾向的问题。不评价、不扩展、不编造，300 字以内，"
            + "直接输出摘要正文。";

    /** 单条消息参与摘要的最大字符数（防超长对话撑爆 prompt） */
    private static final int MSG_MAX_CHARS = 500;

    /** 对话片段整体截断（压不动的部分截断丢弃，摘要宁可少不可编） */
    private static final int DIALOG_MAX_CHARS = 8000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 摘要压缩开关 */
    @org.springframework.beans.factory.annotation.Value("${ai.memory.compress.enabled:true}")
    private boolean compressEnabled;

    @Autowired
    private CacheService cacheService;

    /** 统一 LLM 出口（P2-3b 摘要压缩走 gateway：安全横切 + token 计量）；未注入（纯单测）时跳过压缩 */
    @Autowired(required = false)
    private com.heima.content.service.ai.AiLlmGateway llmGateway;

    /** Prompt 注册表（P2-1）：memory_compress 提示词可热更；未注入时走代码兜底 */
    @Autowired(required = false)
    private com.heima.content.service.ai.AiPromptRegistry promptRegistry;

    /** 压缩专用单线程池（串行化；超载丢弃任务，下次 appendTurn 再触发） */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("aiMemoryCompressExecutor")
    private java.util.concurrent.Executor compressExecutor;

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
        // P2-3b：写完本轮后检查是否需要摘要压缩（同步段极轻，LLM 调用在后台线程）
        try {
            compressIfNeeded(userId);
        } catch (Exception e) {
            log.debug("[AiMemory] 压缩触发检查失败（fail-open），userId={}", userId, e);
        }
    }

    @Override
    public boolean compressIfNeeded(Integer userId) {
        if (userId == null) {
            return false;
        }
        if (!compressEnabled) {
            return false;
        }
        if (llmGateway == null) {
            // 纯单测/未装配环境：跳过压缩
            return false;
        }
        try {
            StringRedisTemplate t = redis();
            String key = keyOf(userId);
            Long size = t.opsForList().size(key);
            if (size == null || size < COMPRESS_THRESHOLD_MSGS) {
                return false;
            }
            // 互斥锁：同一用户同一时刻只跑一个压缩任务（60s 自动过期兜底异常残留）
            String lockKey = COMPRESS_LOCK_PREFIX + userId;
            Boolean locked = t.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(60));
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    doCompress(userId, key);
                } finally {
                    try {
                        t.delete(lockKey);
                    } catch (Exception ignore) {
                        // 锁释放失败靠 60s 过期兜底
                    }
                }
            }, compressExecutor);
            return true;
        } catch (Exception e) {
            log.debug("[AiMemory] 压缩任务提交失败（fail-open），userId={}", userId, e);
            return false;
        }
    }

    /** 压缩执行（后台线程）：LRANGE 快照 → LLM 摘要 → LTRIM 裁旧头部 → LPUSH 摘要 → 刷 TTL */
    private void doCompress(Integer userId, String key) {
        try {
            StringRedisTemplate t = redis();
            List<String> raws = t.opsForList().range(key, 0, -1);
            if (raws == null || raws.size() < COMPRESS_THRESHOLD_MSGS) {
                return;
            }
            int batch = (int) Math.min(COMPRESS_BATCH_MSGS, raws.size());
            StringBuilder dialog = new StringBuilder();
            for (int i = 0; i < batch; i++) {
                Map<String, String> turn = parse(raws.get(i));
                if (turn == null) {
                    continue;
                }
                String role = "user".equals(turn.get("role")) ? "用户" : "助手";
                dialog.append(role).append("：")
                    .append(truncate(turn.get("content"), MSG_MAX_CHARS)).append('\n');
            }
            if (dialog.length() == 0) {
                return;
            }
            String sys = COMPRESS_SYSTEM_FALLBACK;
            if (promptRegistry != null) {
                try {
                    com.heima.content.service.ai.AiPromptRegistry.ResolvedPrompt p =
                        promptRegistry.resolve("memory_compress", COMPRESS_SYSTEM_FALLBACK, userId);
                    if (p != null && p.content != null && !p.content.isBlank()) {
                        sys = p.content;
                    }
                } catch (Exception ignore) {
                    // 注册表异常走代码兜底
                }
            }
            String summary = llmGateway.generateOrNull(com.heima.content.service.ai.AiFeatures.MEMORY_COMPRESS,
                sys, truncate(dialog.toString(), DIALOG_MAX_CHARS), null, null);
            if (summary == null || summary.isBlank()) {
                log.info("[AiMemory] 摘要生成失败，本次放弃压缩（下次再触发），userId={}", userId);
                return;
            }
            // 两步原子写回：只动头部，与并发的 appendTurn（RPUSH 尾部）不冲突。
            // 竞态窗口内新 append 的消息在尾部，不受 LTRIM(N,-1)/LPUSH 影响，不会丢消息
            t.opsForList().trim(key, batch, -1);
            t.opsForList().leftPush(key, toJson("assistant", SUMMARY_PREFIX + summary.trim()));
            t.expire(key, TTL);
            log.info("[AiMemory] 会话摘要压缩完成, userId={}, 压缩 {} 条 -> 1 条摘要", userId, batch);
        } catch (Exception e) {
            log.debug("[AiMemory] 摘要压缩失败（fail-open），userId={}", userId, e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
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