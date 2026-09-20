package com.zhuri.coding.content.service.ai.impl;

import com.zhuri.coding.content.service.ai.AiPromptRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 注册表实现：本地快照 + 周期懒刷新 + 灰度分流 + 代码兜底。
 *
 * <p>取舍：
 * <ul>
 *   <li><b>DB 而非 Nacos</b>：与项目既有配置类表（额度包/成就触发器）同模式，种子/灰度数据 SQL 可审可回滚；
 *       Nacos 动态配置在联调环境不可用时会让 prompt 链路整体退化；</li>
 *   <li><b>快照懒刷新</b>（默认 60s）：prompt 变更是天级操作，60s 延迟可接受，避免每次请求查库；</li>
 *   <li><b>fail-open</b>：DB 异常/表缺失/开关关闭 → 沿用旧快照或直接代码兜底，绝不阻断问答主链路。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiPromptRegistryImpl implements AiPromptRegistry {

    /** 总开关（排障可一键关闭：全部走代码兜底） */
    @Value("${ai.prompt-registry.enabled:true}")
    private boolean enabled;

    /** 快照刷新周期（秒） */
    @Value("${ai.prompt-registry.refresh-seconds:60}")
    private int refreshSeconds;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Object lock = new Object();

    /** 快照：key -> 组（base + 灰度候选，均按 version 降序） */
    private volatile Map<String, PromptGroup> snapshot = Map.of();
    private volatile long snapshotLoadedAt = 0L;

    @Override
    public ResolvedPrompt resolve(String key, String fallback, Integer userId) {
        if (key == null || key.isBlank()) {
            return new ResolvedPrompt("blank", fallback == null ? "" : fallback, 0);
        }
        if (enabled) {
            try {
                reloadIfStale();
                PromptGroup group = snapshot.get(key);
                if (group != null) {
                    ResolvedPrompt hit = pick(group, userId);
                    if (hit != null) {
                        return hit;
                    }
                }
            } catch (Exception e) {
                // DB 异常已由 reload 内部消化并保留旧快照；此处兜底防未知异常外泄
                log.warn("[AiPrompt] 解析失败，走代码兜底, key={}", key, e);
            }
        }
        return new ResolvedPrompt(key, fallback == null ? "" : fallback, 0);
    }

    /** 灰度（userId 非空，version 降序逐个试）→ 正式版（version 最大）→ null（调用方兜底） */
    private ResolvedPrompt pick(PromptGroup group, Integer userId) {
        if (userId != null && !group.grays.isEmpty()) {
            int bucket = Math.floorMod(userId, 100);
            for (Row gray : group.grays) {
                if (bucket < gray.rolloutPercent) {
                    return new ResolvedPrompt(gray.key, gray.content, gray.version);
                }
            }
        }
        if (group.base != null) {
            return new ResolvedPrompt(group.base.key, group.base.content, group.base.version);
        }
        return null;
    }

    private void reloadIfStale() {
        long now = System.currentTimeMillis();
        if (now - snapshotLoadedAt < refreshSeconds * 1000L) {
            return;
        }
        synchronized (lock) {
            if (System.currentTimeMillis() - snapshotLoadedAt < refreshSeconds * 1000L) {
                return;
            }
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT prompt_key, version, content, rollout_percent FROM ap_ai_prompt "
                        + "WHERE enabled = 1 ORDER BY prompt_key, version DESC");
                Map<String, PromptGroup> next = new HashMap<>();
                for (Map<String, Object> r : rows) {
                    String key = String.valueOf(r.get("prompt_key"));
                    int version = ((Number) r.get("version")).intValue();
                    String content = r.get("content") == null ? "" : String.valueOf(r.get("content"));
                    int rollout = r.get("rollout_percent") == null ? 0 : ((Number) r.get("rollout_percent")).intValue();
                    if (content.isBlank()) {
                        continue;
                    }
                    PromptGroup g = next.computeIfAbsent(key, k -> new PromptGroup());
                    if (rollout <= 0) {
                        // 显式取最大 version 的正式版（不依赖 SQL 排序）
                        if (g.base == null || version > g.base.version) {
                            g.base = new Row(key, version, content, rollout);
                        }
                    } else {
                        g.grays.add(new Row(key, version, content, Math.min(rollout, 99)));
                    }
                }
                // 灰度候选按 version 降序（高版本先试），同样不依赖 SQL 顺序
                for (PromptGroup g : next.values()) {
                    g.grays.sort((a, b) -> Integer.compare(b.version, a.version));
                }
                this.snapshot = next;
                this.snapshotLoadedAt = System.currentTimeMillis();
                log.info("[AiPrompt] 注册表快照已刷新, keys={}, cost={}", next.size(),
                    System.currentTimeMillis() - now + "ms");
            } catch (Exception e) {
                // 保留旧快照；loadedAt 也推进，避免 DB 持续不可用时每请求都重试查询
                this.snapshotLoadedAt = System.currentTimeMillis();
                log.warn("[AiPrompt] 注册表刷新失败，沿用旧快照(或代码兜底): {}", e.getMessage());
            }
        }
    }

    /** 同一 key 的候选组 */
    private static final class PromptGroup {
        Row base;
        List<Row> grays = new ArrayList<>();
    }

    private static final class Row {
        final String key;
        final int version;
        final String content;
        final int rolloutPercent;

        Row(String key, int version, String content, int rolloutPercent) {
            this.key = key;
            this.version = version;
            this.content = content;
            this.rolloutPercent = rolloutPercent;
        }
    }
}
