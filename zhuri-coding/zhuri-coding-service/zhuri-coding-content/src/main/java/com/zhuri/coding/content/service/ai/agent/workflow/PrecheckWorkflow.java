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
 * 发布预检「DAG 显式编排」器。
 *
 * <p>相较旧路径（主编 Agent 在 ReAct 里授意模型临场调用专家工具），本编排器把阶段显式化为
 * 有向无环图（DAG）：{@link DagExecutor} 依据阶段间依赖拓扑分层、层内并行、层间串行，
 * 并支持<b>条件门控短路</b>——安全审查判定违规即裁剪质量/SEO/查重/终审，直接结构化输出，
 * 避免对违规内容做无谓的高成本评估。
 *
 * <p>图结构：SAFETY（无依赖，首层）→ 若违规则短路至 FORMAT；非违规则 QUALITY/SEO/DUPLICATE
 * 并行（依赖 SAFETY 放行）→ CRITIC（终审，依赖质量/SEO 产物）→ FORMAT（结构化输出 VO）。
 *
 * <p>阶段独立降级（增量2：分级降级 + 失败自愈）：每个专家/终审都带单次超时上限与失败重试；
 * 重试耗尽后，非阻断阶段（质量/SEO/查重）仅 SKIPPED 该阶段、其余照常；仅当阻断性阶段
 * （安全缺失、终审崩溃）仍失败时才返回 null，交由调用方走直答兜底（fail-open），
 * 避免单点故障级联导致整体不可用。
 *
 * <p>可配置项（application.yml）：{@code app.ai.precheck.stage-timeout-ms}（单专家超时）、
 * {@code app.ai.precheck.stage-max-retry}（非阻断重试）、{@code app.ai.precheck.critic-max-retry}（终审重试）。
 *
 * <p>超时语义：每个专家经 {@code runSafe} 在 stage-timeout-ms 内单次超时+重试；DAG 波次有
 * 60s 硬上限兜底，防止单波次异常卡死整体。
 */
@Slf4j
@Component
public class PrecheckWorkflow {

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 通用 DAG 编排器（无状态）：负责阶段依赖分层、层内并行与条件门控短路。 */
    private final DagExecutor<StageType> dagExecutor = new DagExecutor<>();

    /**
     * 运行 DAG 预检工作流。
     *
     * @param title   文章标题
     * @param content 文章正文（已截断）
     * @param articleId 文章 id（查重时排除自身）
     * @return 预检 VO；任一步骤异常或整体失败时返回 null，调用方降级直答
     */
    public AiPrecheckVo run(String title, String content, Long articleId) {
        StageContext ctx = new StageContext(title, content, articleId, null);
        long wfStart = System.currentTimeMillis();
        try {
            // 阶段图编排：SAFETY→(违规则短路 | 非违规并行 QUALITY/SEO/DUPLICATE)→CRITIC
            DagExecutor.DagResult<StageType> dag = executeDag(ctx);
            // FORMAT 终态序列化：在 DAG 全部完成后读取各阶段产物产最终 VO（须后于 CRITIC，故不入图）
            notifyStage(StageType.FORMAT, () -> "running");
            AiPrecheckVo vo = buildFinalVo(ctx, dag);
            if (vo == null) {
                // 阻断性阶段失败/安全缺失 → 整体降级（fail-open），降级前通知监听器进入 degraded 态
                StageResult critic = ctx.get(StageType.CRITIC);
                if (critic != null && critic.status() == StageResult.Status.FAILED) {
                    notifyStage(StageType.CRITIC, () -> "degraded");
                }
                log.warn("[PrecheckWorkflow] 预检未产出 VO（阻断性阶段失败/安全缺失），向下游降级");
                return null;
            }
            vo.setLatencyMs(System.currentTimeMillis() - wfStart);
            notifyStage(StageType.FORMAT, () -> "done");
            log.info("[PrecheckWorkflow] 完成：violation={}, quality={}, tags={}",
                vo.getViolation(), vo.getQualityScore(), vo.getTags() == null ? 0 : vo.getTags().size());
            return vo;
        } catch (Exception e) {
            log.warn("[PrecheckWorkflow] DAG 编排异常，整体降级（fail-open）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 组装并执行预检 DAG 阶段图。
     *
     * <p>节点依赖：SAFETY 无依赖且始终运行（产出安全判定，阻塞性）；QUALITY/SEO/DUPLICATE 依赖
     * SAFETY 且仅当其放行（非违规）才允许执行，违规则被门控裁剪；CRITIC 依赖 QUALITY/SEO 产物
     * 合成草稿后终审；FORMAT 依赖 SAFETY 并始终执行，产出最终 VO。
     *
     * <p>被裁剪（短路）的阶段经 onCut 同步派发 running→done，保证前端进度条各阶段都有收口事件。
     */
    private DagExecutor.DagResult<StageType> executeDag(StageContext ctx) {
        List<DagExecutor.Node<StageType>> nodes = new ArrayList<>(6);
        // SAFETY：无依赖 → 首个执行；阻断性阶段，产物为安全评审 JSON
        nodes.add(DagExecutor.Node.<StageType>node(StageType.SAFETY)
            .then(r -> {
                notifyStage(StageType.SAFETY, () -> "running");
                StageResult res = runSafe("safety",
                    () -> safetyWorker.review(ctx.title(), ctx.content()), StageType.SAFETY);
                ctx.put(StageType.SAFETY, res);
                notifyStage(StageType.SAFETY, () -> "done");
                return res;
            }).build());
        // QUALITY / SEO：依赖 SAFETY 放行才执行；违规 → 裁剪
        nodes.add(textStage(ctx, StageType.QUALITY, "quality",
            () -> qualityWorker.review(ctx.title(), ctx.content())));
        nodes.add(textStage(ctx, StageType.SEO, "seo",
            () -> seoWorker.review(ctx.title(), ctx.content())));
        // DUPLICATE：同样依赖 SAFETY 放行才查重（违规内容无需查重）
        nodes.add(DagExecutor.Node.<StageType>node(StageType.DUPLICATE)
            .dependsOn(StageType.SAFETY)
            .gate(this::safetyAllowsProceed)
            .then(r -> {
                notifyStage(StageType.DUPLICATE, () -> "running");
                StageResult res = runSimilarity(ctx);
                ctx.put(StageType.DUPLICATE, res);
                notifyStage(StageType.DUPLICATE, () -> "done");
                return res;
            }).build());
        // CRITIC：依赖 QUALITY/SEO；前置被裁剪（违规/安全缺失）→ 整支短路跳过终审
        nodes.add(DagExecutor.Node.<StageType>node(StageType.CRITIC)
            .dependsOn(StageType.QUALITY, StageType.SEO)
            .gate(this::safetyAllowsProceed)
            .then(r -> {
                notifyStage(StageType.CRITIC, () -> "running");
                String draft = mergeDraft(ctx);
                StageResult res;
                if (draft == null) {
                    // 关键专家产物缺失（防御性：正常已被门控裁剪拦截），按阻断处理
                    log.warn("[PrecheckWorkflow] 关键专家产物缺失，无法合成草稿，标记终审阻断");
                    res = StageResult.failed(StageType.CRITIC, 0);
                } else {
                    res = runCritic(draft);
                }
                ctx.put(StageType.CRITIC, res);
                notifyStage(StageType.CRITIC, () -> "done");
                return res;
            }).build());
        // FORMAT 不入图：其作为 DAG 完成后的终态序列化步骤在 run() 中执行（须读取 CRITIC 终态，
        // 且违规短路时仍需产出 VO，故不能依赖会被裁剪的 CRITIC 节点）。

        return dagExecutor.execute(nodes, toolExecutor,
            cutStage -> {
                // 被裁剪（短路跳过）的阶段：派发 running→done，令前端进度条不悬挂
                notifyStage(cutStage, () -> "running");
                notifyStage(cutStage, () -> "done");
            });
    }

    /** 质量/SEO 类「文本评审、非阻断、受安全门控」阶段的公共声明。 */
    private DagExecutor.Node<StageType> textStage(StageContext ctx, StageType type, String name,
                                                  java.util.function.Supplier<String> review) {
        return DagExecutor.Node.<StageType>node(type)
            .dependsOn(StageType.SAFETY)
            .gate(this::safetyAllowsProceed)
            .then(r -> {
                notifyStage(type, () -> "running");
                StageResult res = runSafe(name, review, type);
                ctx.put(type, res);
                notifyStage(type, () -> "done");
                return res;
            })
            .build();
    }

    /** 安全门控：仅当 SAFETY 产出有效且未判定违规时才放行后续（非阻断）阶段。 */
    private boolean safetyAllowsProceed(DagExecutor.DagResult<StageType> r) {
        Object s = r.valueOf(StageType.SAFETY);
        if (!(s instanceof StageResult sr) || sr.status() != StageResult.Status.OK) {
            return false; // 安全缺失：不放行
        }
        JsonNode safety = parseOnNull(sr.payload());
        return safety != null && !safety.path("is_violation").asBoolean(true);
    }

    /** FORMAT 阶段产出 VO：安全缺失/终审阻断 → null（fail-open）；终审被裁剪（违规短路）→ 直接合成。 */
    private AiPrecheckVo buildFinalVo(StageContext ctx, DagExecutor.DagResult<StageType> r) {
        StageResult safety = stageResultOf(r, StageType.SAFETY);
        // 安全缺失（SKIPPED/空）→ 阻断，无法产出 VO
        if (safety == null || safety.status() != StageResult.Status.OK || parseOnNull(safety.payload()) == null) {
            return null;
        }
        DagExecutor.DagResult.Status criticStatus = r.statusOf(StageType.CRITIC);
        if (criticStatus == DagExecutor.DagResult.Status.FAILED) {
            return null; // 终审阻断
        }
        if (criticStatus == DagExecutor.DagResult.Status.OK) {
            return formatVo(ctx); // 正常路径：解析终审产物
        }
        // critic 被裁剪（违规短路）→ 用安全结果直接合成 VO（violation=true）
        return formatShortCircuited(ctx);
    }

    private StageResult stageResultOf(DagExecutor.DagResult<StageType> r, StageType t) {
        Object v = r.valueOf(t);
        return v instanceof StageResult sr ? sr : null;
    }

    /** 违规短路分支的结构化：仅依据安全评审合成 VO，质量/SEO/终审字段取默认值。 */
    private AiPrecheckVo formatShortCircuited(StageContext ctx) {
        JsonNode safety = parseOnNull(ctx.payload(StageType.SAFETY));
        AiPrecheckVo vo = new AiPrecheckVo();
        vo.setViolation(safety != null && safety.path("is_violation").asBoolean(false));
        vo.setViolationType(trimToNull(safety == null ? "" : safety.path("violation_type").asText()));
        vo.setViolationReason(trimToNull(safety == null ? "" : safety.path("violation_reason").asText()));
        vo.setTech(true); // 短路未评估质量，默认技术内容
        vo.setSuggestions(new ArrayList<>());
        vo.setTags(new ArrayList<>());
        vo.setSummary(null);
        fillSimilarity(vo, ctx);
        return vo;
    }

    private StageResult runSimilarity(StageContext ctx) {
        long t = System.currentTimeMillis();
        try {
            // 查重直接调用（对象级，不序列化——内部 ApArticle 大小/枚举字段不适合 JSON 往返）。
            // 查重自身无重试；单次执行若异常按 SKIPPED 处理，不拖垮 DAG 其余分支。
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
            if (hit.similarity() >= SimilaritySearchTool.ALERT_THRESHOLD) {
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

    /**
     * 相似度填充：这三个字段**只由确定性检索结果决定**——命中则写入，未命中 / 未查重一律清空。
     *
     * <p><b>为什么"未命中"要显式清空，而不是"什么都不做"</b>：把「无高相似」表达成一个明确的
     * null，才能让"该字段只有一个写入出口"成为代码层面可验证的事实 —— 无论将来谁在别处写了它，
     * 都会被这一处覆盖掉。这正是本项目早期踩过的坑：当时模型输出的 schema 里带着这三个字段，
     * 代码又只做"命中才覆盖"，于是确定性检索未命中时，模型编造的预警被原样展示给了作者。
     * 现在 schema 已不含这三个字段（见 AGENT_SYSTEM_PROMPT），但"唯一出口"的约束仍然保留。
     *
     * <p>「未查重」同样清空：SAFETY 违规短路（违规内容无需查重）与 DUPLICATE 阶段异常 SKIPPED
     * 都属于"没有确定性结论"，不能让它落到一个"看起来像有结论"的状态。
     */
    private void fillSimilarity(AiPrecheckVo vo, StageContext ctx) {
        Object payload = ctx.payload(StageType.DUPLICATE);
        SimilaritySearchTool.SimilarArticle best = null;
        if (payload instanceof List<?> hits && !hits.isEmpty()
                && hits.get(0) instanceof SimilaritySearchTool.SimilarArticle hit) {
            best = hit;
        }
        applySimilarity(vo, best);
    }

    /** 唯一的相似度写入出口：best 为 null 即"确定性地无高相似"（或未查重）→ 显式清空模型填值 */
    private static void applySimilarity(AiPrecheckVo vo, SimilaritySearchTool.SimilarArticle best) {
        if (best == null) {
            vo.setSimilarArticleId(null);
            vo.setSimilarTitle(null);
            vo.setSimilarity(null);
            return;
        }
        vo.setSimilarArticleId(best.article().getId());
        vo.setSimilarTitle(best.article().getTitle());
        vo.setSimilarity(Math.round(best.similarity() * 10000) / 10000.0);
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
        // 相似度三个字段刻意不在此解析：模型输出 schema 里已不含 similar_*，
        // 其值只由 fillSimilarity 的确定性检索填充（唯一写入出口，见该类方法注释）。
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