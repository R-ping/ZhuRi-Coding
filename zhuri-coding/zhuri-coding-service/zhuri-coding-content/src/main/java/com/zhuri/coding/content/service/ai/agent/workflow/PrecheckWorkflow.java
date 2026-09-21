package com.zhuri.coding.content.service.ai.agent.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhuri.coding.content.service.ai.agent.tools.SimilaritySearchTool;
import com.zhuri.coding.content.service.ai.agent.workers.CriticExpertWorker;
import com.zhuri.coding.content.service.ai.agent.workers.QualityExpertWorker;
import com.zhuri.coding.content.service.ai.agent.workers.SafetyExpertWorker;
import com.zhuri.coding.content.service.ai.agent.workers.SeoExpertWorker;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 发布预检「显式工作流」编排器。
 *
 * <p>相较旧路径（主编 Agent 在 ReAct 里授意模型临场调用专家工具），本编排器把
 * 阶段显式化为代码控制：SAFETY/QUALITY/SEO 三个独立专家经 {@code aiAgentToolExecutor}
 * 并行执行（互不依赖），DUPLICATE 查重并行进行；随后将各专家产物合成为完整草稿，
 * 交 CRITIC 终审校验修正，最后 FORMAT 结构化输出 {@link AiPrecheckVo}。
 *
 * <p>阶段独立降级（增量2：分级降级 + 失败自愈）：每个专家/终审都带单次超时上限与失败重试；
 * 重试耗尽后，非阻断阶段（质量/SEO/查重）仅 SKIPPED 该阶段、其余照常；仅当阻断性阶段
 * （安全缺失、终审崩溃）重试后仍失败时才返回 null，交由调用方走直答兜底（fail-open），
 * 避免单点故障级联导致整体不可用。
 *
 * <p>可配置项（application.yml）：{@code app.ai.precheck.stage-timeout-ms}（单专家超时）、
 * {@code app.ai.precheck.stage-max-retry}（非阻断重试）、{@code app.ai.precheck.critic-max-retry}（终审重试）、
 * {@code app.ai.precheck.parallel-timeout-ms}（并行批次整体等待上限）。
 */
@Slf4j
@Component
public class PrecheckWorkflow {

    /** 相似度预警阈值（与旧路径一致，低于该值不算"疑似重复"） */
    private static final double SIMILAR_ALERT = 0.72;

    private final SafetyExpertWorker safetyWorker;
    private final QualityExpertWorker qualityWorker;
    private final SeoExpertWorker seoWorker;
    private final CriticExpertWorker criticWorker;
    private final SimilaritySearchTool similarityTool;
    private final Executor toolExecutor;

    /** 各专家并行执行专用池（复用 AgentRunner 同一线程池，见 AiAsyncConfig#aiAgentToolExecutor） */
    public PrecheckWorkflow(SafetyExpertWorker safetyWorker,
                            QualityExpertWorker qualityWorker,
                            SeoExpertWorker seoWorker,
                            CriticExpertWorker criticWorker,
                            SimilaritySearchTool similarityTool,
                            @Qualifier("aiAgentToolExecutor") Executor toolExecutor) {
        this.safetyWorker = safetyWorker;
        this.qualityWorker = qualityWorker;
        this.seoWorker = seoWorker;
        this.criticWorker = criticWorker;
        this.similarityTool = similarityTool;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 阶段进度事件监听器（SSE 可观测用，可扩为多路）。
     *
     * <p>每个监听器以 {@code (StageType, Supplier<String>)} 为契约：第一个参数是阶段类型，
     * 第二个是延迟求值的状态明细（running/done/degraded），仅在真正发送事件时才 {@code get()}，
     * 避免无关监听器付出字符串构造开销。拷贝写数组保证并发 add/remove 与遍历安全。
     */
    private final java.util.List<java.util.function.BiConsumer<StageType, java.util.function.Supplier<String>>> listeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 注册阶段事件监听器；为空直接忽略（防 NPE） */
    public void addStageListener(java.util.function.BiConsumer<StageType, java.util.function.Supplier<String>> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 按对象引用移除单个监听器（并发隔离关键：多个用户并发跑流式预检时，每个调用方
     * 只应移除自己注册的那个实例，绝不能全量清理，否则会串扰其他用户的流）。
     */
    public void removeStageListener(java.util.function.BiConsumer<StageType, java.util.function.Supplier<String>> listener) {
        listeners.remove(listener);
    }

    /** 清空全部阶段事件监听器（仅供测试/重置使用；生产并发场景慎用，勿用于清理单个流） */
    public void clearStageListeners() {
        listeners.clear();
    }

    /**
     * 派发阶段事件：遍历当前全部监听器逐个通知；监听器自身抛异常被吞掉，绝不打断主流程。
     * 注意：此回调必须是同步快速、非阻塞的（仅把事件交给上游 SSE 缓冲/发送，不可自行耗时代码）。
     */
    private void notifyStage(StageType stage, java.util.function.Supplier<String> detail) {
        for (java.util.function.BiConsumer<StageType, java.util.function.Supplier<String>> l : listeners) {
            try {
                l.accept(stage, detail);
            } catch (Exception e) {
                log.warn("[PrecheckWorkflow] 阶段事件监听器异常（吞掉，不影响主流程）: {}", e.getMessage());
            }
        }
    }

    /** 单专家单次调用超时上限（毫秒，含重试各次），默认 15s */
    @Value("${app.ai.precheck.stage-timeout-ms:15000}")
    private long stageTimeoutMs = 15000;

    /** 非阻断专家阶段的失败重试次数（含首调），默认 2（即首调+1 次重试） */
    @Value("${app.ai.precheck.stage-max-retry:2}")
    private int stageMaxRetry = 2;

    /** 终审 Critic 的失败重试次数（含首调），默认 2 */
    @Value("${app.ai.precheck.critic-max-retry:2}")
    private int criticMaxRetry = 2;

    /** 并行专家批次整体等待超时上限（毫秒），防止单一专家卡死拖垮整体，默认 45s */
    @Value("${app.ai.precheck.parallel-timeout-ms:45000}")
    private long parallelTimeoutMs = 45000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 运行显式预检工作流。
     *
     * @param title   文章标题
     * @param content 文章正文（已截断）
     * @param articleId 文章 id（查重时排除自身）
     * @return 预检 VO；任一步骤异常或整体失败时返回 null，调用方降级直答
     */
    public AiPrecheckVo run(String title, String content, Long articleId) {
        StageContext ctx = new StageContext(title, content, articleId, null);
        long wfStart = System.currentTimeMillis();

        // 阶段 1：独立专家并行（安全/质量/SEO）+ 查重
        runParallelBatch(ctx);

        // 阶段 2：把专家产物合成为完整草稿 JSON（供 Critic 终审）
        String draft = mergeDraft(ctx);
        if (draft == null) {
            log.warn("[PrecheckWorkflow] 关键专家产物缺失，无法合成草稿，直接降级");
            return null;
        }
        // 终审 Critic（阻断性）：进入前通知 running，产出后通知 done
        notifyStage(StageType.CRITIC, () -> "running");
        StageResult criticResult = runCritic(draft);
        ctx.put(StageType.CRITIC, criticResult);
        notifyStage(StageType.CRITIC, () -> "done");

        // 阶段 3：结构化输出 VO（含相似度填充）
        notifyStage(StageType.FORMAT, () -> "running");
        AiPrecheckVo vo = formatVo(ctx);
        if (vo == null) {
            // 终审崩溃/结构化失败 → 整体降级（fail-open），异常降级前通知监听器进入 degraded 态
            if (criticResult != null && criticResult.status() == StageResult.Status.FAILED) {
                notifyStage(StageType.CRITIC, () -> "degraded");
            }
            log.warn("[PrecheckWorkflow] 终审产出无法解析为 VO，记录失败上下文");
            return null;
        }
        vo.setLatencyMs(System.currentTimeMillis() - wfStart);
        notifyStage(StageType.FORMAT, () -> "done");
        log.info("[PrecheckWorkflow] 完成：violation={}, quality={}, tags={}",
            vo.getViolation(), vo.getQualityScore(), vo.getTags() == null ? 0 : vo.getTags().size());
        return vo;
    }

    /** 并行执行 安全/质量/SEO/查重 四步，各自独立带超时+重试并独立降级；整体等待受 parallelTimeoutMs 约束，防止单专家卡死 */
    private void runParallelBatch(StageContext ctx) {
        // 阶段事件：四个并行专家进入前通知 running（前端据此归位各阶段进行态）
        notifyStage(StageType.SAFETY, () -> "running");
        notifyStage(StageType.QUALITY, () -> "running");
        notifyStage(StageType.SEO, () -> "running");
        notifyStage(StageType.DUPLICATE, () -> "running");
        List<CompletableFuture<StageResult>> futures = new ArrayList<>(4);
        futures.add(CompletableFuture.supplyAsync(() -> runSafe("safety", () -> safetyWorker.review(ctx.title(), ctx.content()), StageType.SAFETY), toolExecutor));
        futures.add(CompletableFuture.supplyAsync(() -> runSafe("quality", () -> qualityWorker.review(ctx.title(), ctx.content()), StageType.QUALITY), toolExecutor));
        futures.add(CompletableFuture.supplyAsync(() -> runSafe("seo", () -> seoWorker.review(ctx.title(), ctx.content()), StageType.SEO), toolExecutor));
        futures.add(CompletableFuture.supplyAsync(() -> runSimilarity(ctx), toolExecutor));
        collectFutureResults(ctx, futures);
        // 阶段事件：并行批次汇总完成，四个阶段统一通知 done（无论成功/SKIPPED）
        notifyStage(StageType.SAFETY, () -> "done");
        notifyStage(StageType.QUALITY, () -> "done");
        notifyStage(StageType.SEO, () -> "done");
        notifyStage(StageType.DUPLICATE, () -> "done");
    }

    /**
     * 在 bounded timeout 内等待子任务完成并按阶段归档；超时/中断/未完成的任务按 SKIPPED 记入，
     * 保证任一步骤卡死都不拖垮整体（各阶段仍先走各自内部超时+重试）。
     */
    private void collectFutureResults(StageContext ctx, List<CompletableFuture<StageResult>> futures) {
        boolean interrupted = false;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(parallelTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[PrecheckWorkflow] 并行批次等待超时 {}ms，未完成阶段按 SKIPPED 归档", parallelTimeoutMs);
        } catch (InterruptedException e) {
            interrupted = true;
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("[PrecheckWorkflow] 并行批次执行异常，按已返回结果继续", e);
        }
        for (CompletableFuture<StageResult> f : futures) {
            try {
                StageResult r = f.getNow(null);
                if (r == null) {
                    // 超时或异常导致无结果：归档为对应阶段 SKIPPED
                    log.warn("[PrecheckWorkflow] 阶段无结果（超时/异常），SKIPPED 归档");
                    continue;
                }
                ctx.put(r.stage(), r);
            } catch (Exception e) {
                log.warn("[PrecheckWorkflow] 单阶段结果读取异常，跳过", e);
            }
        }
        if (interrupted) {
            log.warn("[PrecheckWorkflow] 并行等待被中断，Workflow 可能被上层取消");
        }
    }

    private StageResult runSimilarity(StageContext ctx) {
        long t = System.currentTimeMillis();
        try {
            // 查重直接调用（对象级，不序列化——内部 ApArticle 大小/枚举字段不适合 JSON 往返）。
            // 单次超时由外层并行批次整体等待 parallelTimeoutMs 兜底，避免专门包装引入序列化问题。
            List<SimilaritySearchTool.SimilarArticle> hits = similarityTool.searchSimilar(ctx.content());
            return StageResult.ok(StageType.DUPLICATE, filterSelf(hits, ctx.articleId()), System.currentTimeMillis() - t);
        } catch (Exception e) {
            log.warn("[PrecheckWorkflow] 查重阶段异常，按 SKIPPED 处理: {}", e.getMessage());
            return StageResult.skipped(StageType.DUPLICATE, System.currentTimeMillis() - t);
        }
    }

    /** 复用 {@link SimilaritySearchTool.SimilarArticle} 的 record；排除自身后仅保留最相似的一篇 */
    private List<SimilaritySearchTool.SimilarArticle> filterSelf(List<SimilaritySearchTool.SimilarArticle> hits, Long articleId) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        for (SimilaritySearchTool.SimilarArticle hit : hits) {
            if (articleId != null && articleId.equals(hit.article().getId())) {
                continue;
            }
            if (hit.similarity() >= SIMILAR_ALERT) {
                return List.of(hit); // 与历史行为一致：仅以最相似一篇作为预警
            }
        }
        return Collections.emptyList();
    }

    /** 安全的单阶段执行（含失败重试与超时）：异常/超时重试至 maxRetry 次，均失败 → SKIPPED（非阻断） */
    private StageResult runSafe(String name, java.util.function.Supplier<String> supplier, StageType type) {
        long t = System.currentTimeMillis();
        Throwable last = null;
        for (int attempt = 1; attempt <= stageMaxRetry; attempt++) {
            try {
                String s = invokeWithTimeout(supplier, stageTimeoutMs);
                if (s == null || s.isBlank()) {
                    if (attempt < stageMaxRetry) {
                        log.warn("[PrecheckWorkflow] {} 第 {} 次返回空，重试", name, attempt);
                        continue;
                    }
                    log.warn("[PrecheckWorkflow] {} 阶段返回空，SKIPPED", name);
                    return StageResult.skipped(type, System.currentTimeMillis() - t);
                }
                return StageResult.ok(type, s, System.currentTimeMillis() - t);
            } catch (TimeoutException e) {
                last = e;
                if (attempt < stageMaxRetry) {
                    log.warn("[PrecheckWorkflow] {} 第 {} 次超时 {}ms，重试", name, attempt, stageTimeoutMs);
                }
            } catch (Exception e) {
                last = e;
                if (attempt < stageMaxRetry) {
                    log.warn("[PrecheckWorkflow] {} 第 {} 次异常，重试: {}", name, attempt, e.getMessage());
                }
            }
        }
        log.warn("[PrecheckWorkflow] {} 阶段重试 {} 次后仍失败，SKIPPED: {}", name, stageMaxRetry,
            last == null ? "unknown" : last.getMessage());
        return StageResult.skipped(type, System.currentTimeMillis() - t);
    }

    /** 在 stageTimeoutMs 内执行 supplier；超时抛 TimeoutException */
    private String invokeWithTimeout(java.util.function.Supplier<String> supplier, long timeoutMs)
            throws TimeoutException, InterruptedException, ExecutionException {
        return CompletableFuture.supplyAsync(supplier, toolExecutor)
            .get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 把专家产物合成为完整草稿 JSON。安全为阻断性必须存在；质量/SEO 失败则填默认值继续，体现"非阻断独立降级" */
    private String mergeDraft(StageContext ctx) {
        JsonNode safety = parseOnNull(ctx.payload(StageType.SAFETY));
        if (safety == null) {
            log.warn("[PrecheckWorkflow] 安全评审缺失（阻断性阶段），无法合成草稿");
            return null;
        }
        JsonNode quality = parseOnNull(ctx.payload(StageType.QUALITY));
        JsonNode seo = parseOnNull(ctx.payload(StageType.SEO));
        ObjectNode draft = objectMapper.createObjectNode();
        // 安全（必需）
        draft.put("is_violation", safety.path("is_violation").asBoolean(false));
        draft.put("violation_type", safety.path("violation_type").asText(""));
        draft.put("violation_reason", safety.path("violation_reason").asText(""));
        // 质量（缺失 → 默认值，交由终审校准）
        draft.put("quality_score", quality == null ? 0 : quality.path("quality_score").asInt(0));
        draft.put("is_tech", quality == null || quality.path("is_tech").asBoolean(true));
        draft.set("suggestions", quality != null && quality.path("suggestions").isArray()
            ? quality.path("suggestions") : objectMapper.createArrayNode());
        // SEO（缺失 → 空标签/摘要）
        draft.set("tags", seo != null && seo.path("tags").isArray()
            ? seo.path("tags") : objectMapper.createArrayNode());
        draft.put("summary", seo == null ? "" : seo.path("summary").asText(""));
        return draft.toString();
    }

    /**
     * 终审 Critic 阶段（阻断性）：对完整草稿复查修正；含失败重试与超时。
     * 重试 criticMaxRetry 次仍失败/为空 → FAILED，交由调用方整体降级（fail-open）。
     */
    private StageResult runCritic(String draft) {
        long t = System.currentTimeMillis();
        Throwable last = null;
        for (int attempt = 1; attempt <= criticMaxRetry; attempt++) {
            try {
                String reviewed = criticWorker.review(draft);
                if (reviewed != null && !reviewed.isBlank()) {
                    return StageResult.ok(StageType.CRITIC, reviewed, System.currentTimeMillis() - t);
                }
                if (attempt < criticMaxRetry) {
                    log.warn("[PrecheckWorkflow] 终审第 {} 次返回空，重试", attempt);
                }
            } catch (Exception e) {
                last = e;
                if (attempt < criticMaxRetry) {
                    log.warn("[PrecheckWorkflow] 终审第 {} 次异常，重试: {}", attempt, e.getMessage());
                }
            }
        }
        log.error("[PrecheckWorkflow] 终审重试 {} 次后仍失败（阻断），整体降级: {}", criticMaxRetry,
            last == null ? "empty" : last.getMessage());
        return StageResult.failed(StageType.CRITIC, System.currentTimeMillis() - t);
    }

    /** FORMAT 阶段：解析终审产物为 VO；失败因字段缺失则回退解析原始草稿 */
    private AiPrecheckVo formatVo(StageContext ctx) {
        try {
            JsonNode root = parseOnNull(ctx.payload(StageType.CRITIC));
            if (root == null) {
                return null;
            }
            AiPrecheckVo vo = toVo(root);
            // 相似度填充（来自 DUPLICATE 阶段产物）
            fillSimilarity(vo, ctx);
            return vo;
        } catch (Exception e) {
            log.warn("[PrecheckWorkflow] VO 结构化失败", e);
            return null;
        }
    }

    /** 复用旧路径的相似度填充语义：仅当 DUPLICATE 产出有效命中时写入 */
    private void fillSimilarity(AiPrecheckVo vo, StageContext ctx) {
        Object payload = ctx.payload(StageType.DUPLICATE);
        if (payload instanceof List<?> hits && !hits.isEmpty() && hits.get(0) instanceof SimilaritySearchTool.SimilarArticle hit) {
            vo.setSimilarArticleId(hit.article().getId());
            vo.setSimilarTitle(hit.article().getTitle());
            vo.setSimilarity(Math.round(hit.similarity() * 10000) / 10000.0);
        }
    }

    /** 解析 JSON 字符串，容错地剥离可能存在的 FINAL 前缀与代码块；不可解析返回 null */
    private JsonNode parseOnNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        int idx = s.lastIndexOf('{');
        if (idx < 0) {
            return null;
        }
        String maybe = s.substring(idx);
        try {
            return objectMapper.readTree(maybe);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** JSON → AiPrecheckVo（容错：字段缺失不报错，与旧路径 fromJson 语义一致） */
    private AiPrecheckVo toVo(JsonNode root) {
        AiPrecheckVo vo = new AiPrecheckVo();
        vo.setViolation(root.path("is_violation").asBoolean(false));
        vo.setViolationType(trimToNull(root.path("violation_type").asText()));
        vo.setViolationReason(trimToNull(root.path("violation_reason").asText()));
        vo.setQualityScore(root.path("quality_score").isInt() ? root.path("quality_score").asInt() : null);
        vo.setTech(root.path("is_tech").asBoolean(true));
        vo.setSuggestions(toStringList(root.get("suggestions")));
        vo.setTags(toStringList(root.get("tags")));
        vo.setSummary(trimToNull(root.path("summary").asText()));
        if (root.hasNonNull("similar_article_id") && root.path("similar_article_id").asLong() > 0) {
            vo.setSimilarArticleId(root.path("similar_article_id").asLong());
            vo.setSimilarTitle(trimToNull(root.path("similar_title").asText()));
            vo.setSimilarity(root.path("similarity").isNumber() ? root.path("similarity").asDouble() : null);
        }
        return vo;
    }

    private List<String> toStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            String v = item.asText("");
            if (!v.isBlank() && list.size() < 6) {
                list.add(v.trim());
            }
        }
        return list;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}