package com.heima.content.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI 外部依赖熔断器（轻量自研，零新依赖）。
 *
 * <p><b>为什么需要</b>：AI 链路目前靠 try-catch fail-open —— 逻辑上正确，但**故障期间每个请求仍会
 * 建立连接 → 等满超时**，把 Tomcat/业务线程池拖满，AI 的故障会扩散成整站故障。
 * 熔断解决的是"故障已经确定发生，就别再去试"：快速失败，把资源留给正常请求。
 *
 * <p><b>状态机</b>（Redis 实现，天然多实例一致）：
 * <pre>
 *   关闭 --连续失败达阈值（默认 5 次 / 60s 窗口）--> 打开（TTL 30s，期间所有请求快速失败）
 *   打开 --TTL 到期--> 半开（放行第一个请求试探）--成功--> 关闭（清零计数）
 *                                            --失败--> 重新打开
 * </pre>
 *
 * <p><b>多目标独立</b>：{@code llm} 与 {@code embedding} 各自独立熔断 —— 向量服务挂了
 * 不代表对话模型挂，反之亦然，不能互相误伤。
 *
 * <p><b>可靠性约定</b>：熔断器自身异常一律 fail-open（放行），绝不因为"熔断器故障"阻断主链路；
 * 它是保护措施，不是新的故障点。
 */
@Component
@Slf4j
public class AiCircuitBreaker {

    /** 目标：对话/生成模型 */
    public static final String TARGET_LLM = "llm";
    /** 目标：向量化模型（RAG 召回与缓存的关键路径） */
    public static final String TARGET_EMBEDDING = "embedding";

    private static final String FAIL_KEY = "ai:cb:%s:fail";
    private static final String OPEN_KEY = "ai:cb:%s:open";

    /** 连续失败阈值（窗口内） */
    @Value("${ai.circuit.failure-threshold:5}")
    private int failureThreshold = 5;

    /** 失败统计窗口（秒）：只统计最近窗口内的失败，避免"历史陈账"永久累积 */
    @Value("${ai.circuit.window-seconds:60}")
    private long windowSeconds = 60;

    /** 熔断打开时长（秒）：到期即进入半开，放行试探请求 */
    @Value("${ai.circuit.open-seconds:30}")
    private long openSeconds = 30;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * 是否放行本次调用。
     *
     * @return true=放行；false=熔断打开中，调用方应快速失败并降级
     */
    public boolean allow(String target) {
        if (redisTemplate == null) {
            return true; // 无 Redis：无法判定，放行（fail-open）
        }
        try {
            return !Boolean.TRUE.equals(redisTemplate.hasKey(openKey(target)));
        } catch (Exception e) {
            log.debug("[AiCircuit] 熔断状态查询失败，放行: target={}, err={}", target, e.getMessage());
            return true;
        }
    }

    /** 报告成功：清零失败计数并关闭熔断（半开试探成功即恢复） */
    public void onSuccess(String target) {
        if (redisTemplate == null) {
            return;
        }
        try {
            Boolean wasOpen = redisTemplate.hasKey(openKey(target));
            redisTemplate.delete(openKey(target));
            redisTemplate.delete(failKey(target));
            if (Boolean.TRUE.equals(wasOpen)) {
                log.info("[AiCircuit] 半开试探成功，熔断关闭: target={}", target);
            }
        } catch (Exception e) {
            log.debug("[AiCircuit] 熔断恢复写入失败: target={}, err={}", target, e.getMessage());
        }
    }

    /**
     * 报告失败：窗口内失败累计达阈值则打开熔断。
     *
     * <p>调用方传 {@code openHint=true}（如鉴权失败、参数错误）可跳过计数 —— 只统计
     * "依赖不可用"类失败，避免把用户侧错误算成下游故障。
     */
    public void onFailure(String target) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String fk = failKey(target);
            Long fails = redisTemplate.opsForValue().increment(fk);
            if (fails != null && fails == 1L) {
                redisTemplate.expire(fk, windowSeconds, TimeUnit.SECONDS);
            }
            if (fails != null && fails >= failureThreshold
                    && !Boolean.TRUE.equals(redisTemplate.hasKey(openKey(target)))) {
                redisTemplate.opsForValue().set(openKey(target), "1", openSeconds, TimeUnit.SECONDS);
                log.warn("[AiCircuit] 熔断打开: target={}, 窗口内失败={}次, 打开时长={}s（期间快速失败，到期半开试探）",
                        target, fails, openSeconds);
            }
        } catch (Exception e) {
            log.debug("[AiCircuit] 熔断计数写入失败: target={}, err={}", target, e.getMessage());
        }
    }

    /** 状态快照（观测端点/排障用） */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("failureThreshold", failureThreshold);
        out.put("windowSeconds", windowSeconds);
        out.put("openSeconds", openSeconds);
        Map<String, Object> targets = new LinkedHashMap<>();
        for (String t : new String[]{TARGET_LLM, TARGET_EMBEDDING}) {
            Map<String, Object> st = new LinkedHashMap<>();
            boolean open;
            long fails;
            try {
                open = redisTemplate != null && Boolean.TRUE.equals(redisTemplate.hasKey(openKey(t)));
                String v = redisTemplate == null ? null : redisTemplate.opsForValue().get(failKey(t));
                fails = v == null ? 0L : Long.parseLong(v);
            } catch (Exception e) {
                open = false;
                fails = -1L; // -1 = 状态不可读（Redis 异常）
            }
            st.put("open", open);
            st.put("recentFailures", fails);
            targets.put(t, st);
        }
        out.put("targets", targets);
        return out;
    }

    private static String failKey(String target) {
        return String.format(FAIL_KEY, target);
    }

    private static String openKey(String target) {
        return String.format(OPEN_KEY, target);
    }
}
