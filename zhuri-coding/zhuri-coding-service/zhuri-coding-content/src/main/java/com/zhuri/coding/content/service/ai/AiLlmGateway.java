package com.zhuri.coding.content.service.ai;

import com.zhuri.coding.content.service.ai.spring.PromptSafetyAdvisor;
import com.zhuri.coding.content.service.ai.spring.SafetyGuardException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 统一 LLM 调用出口（唯一入口）。
 *
 * <p><b>为什么要有这个类</b>：改造前 ChatClient 在 4 处各自 {@code ChatClient.builder(chatModel).build()}，
 * 导致三个问题：
 * <ol>
 *   <li><b>用量采集只能靠逐处补代码</b> —— 新增调用点极易漏计量（成本盲区）；</li>
 *   <li>advisor（安全横切）装配方式不统一，漏挂即安全缺口；</li>
 *   <li>超时/重试/熔断等后续横切能力无处挂载。</li>
 * </ol>
 * 收敛到本类后：<b>所有 LLM 调用自动带安全横切 + 自动计量 token</b>，
 * 且后续加熔断/降级只需改这一处。
 *
 * <p><b>客户端装配（2026-09-30 修正）</b>：主路径的 ChatClient 由 {@link #client(ChatModel)}
 * 按 model <b>缓存复用</b>，不再每调用一次重建（改造后曾遗留的每请求 build 已消除）。
 * 仍需保留 builder 的地方只有三处，且均为<b>有意的例外</b>：
 * <ul>
 *   <li>{@code AiExpertConfig#aiExpertChatClient} —— 单例 Bean，一次构建；</li>
 *   <li>{@code AgentRunner} 构造期 —— 一次构建；</li>
 *   <li>{@link #probeInternal} —— 探针要求"裸链路"，<b>刻意不挂</b> advisor，故不使用带安全横切的缓存客户端。</li>
 * </ul>
 *
 * <p><b>异常语义（与改造前保持一致，调用方无需改动）</b>：任何异常都内部消化并返回 null——
 * 安全护栏命中（{@link SafetyGuardException}）记 WARN，其余记 ERROR；调用方按"生成失败"降级。
 *
 * <p><b>为什么没有重试（有意的，不是遗漏）</b>：Spring AI 官方框架<b>不内置容错</b>，
 * 只把 Advisor 作为横切挂载点。社区主流做法是"按失败模式分别处理"——
 * <b>不重试是一刀切，确实比"分模式重试"粗糙</b>。本类仍选择不重试，理由是三条权衡：
 * <ol>
 *   <li><b>成本放大</b> —— 失败的那一次可能已产生（部分）计费，重试即翻倍 token 成本；</li>
 *   <li><b>时延放大</b> —— LLM 调用本身就是秒级，重试会把用户等待拉长到不可接受；</li>
 *   <li><b>已有熔断兜底</b> —— 故障期由 {@link AiCircuitBreaker} 快速失败，重试只会加剧打满线程池的风险。</li>
 * </ol>
 * <b>已知落后项</b>：外部最佳实践把 LLM 失败分为 429 / 5xx / 超时 / 格式错误 / 安全拦截五类，分别处理
 * （429 重试需尊重 {@code Retry-After}；格式错误只重试一次；安全拦截永不重试）。
 * 本类只做了"安全拦截永不重试"这一类（{@link SafetyGuardException} 单独处理），其余四类是统一不重试。
 * 失败经 {@code FailPolicy} 走 fail-open（返回 null 让调用方降级）。
 * <b>若将来要补重试</b>，必须遵守：① 只重试<b>连接层</b>失败（{@code ConnectException} / 5xx），
 * 绝不重试"模型已开始生成"的失败（会重复计费）；② 重试放在<b>熔断内侧</b>
 * （熔断打开时直接失败，不进重试循环）；③ 429 重试必须尊重 {@code Retry-After}；
 * ④ <b>不要做成 Advisor</b>——Advisor 是同步的，在链里做阻塞重试会拖长整条链。
 *
 * <p><b>会话记忆</b>：由调用方构建 {@link ChatMemory} 传入（保持既有"请求级实例、固定会话 key"语义），
 * 传 null 即不挂记忆 advisor（如 Query Rewrite / Rerank 等内部调用）。
 */
@Slf4j
@Service
public class AiLlmGateway {

    /**
     * 默认模型（spring-ai 自动配置主模型 bean）。
     * 显式 @Qualifier 明确指向唯一 Bean：多模型阶段存在 qwenFlashChatModel 与 openAiChatModel 两个候选，
     * 不限定会因 NoUniqueBeanDefinitionException 导致启动失败；本字段仅作路由器缺失时的最终兜底
     * （正常路径由 {@link #resolveModel(String)} 经 AiModelRouter 按 feature 决策）。
     */
    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("openAiChatModel")
    private ChatModel chatModel;

    /** 功能级模型路由（feature → 模型）；未装配时回退注入的默认 chatModel */
    @Autowired(required = false)
    private com.zhuri.coding.content.service.ai.router.AiModelRouter modelRouter;

    @Autowired
    private PromptSafetyAdvisor promptSafetyAdvisor;

    @Autowired
    private AiTokenMeter tokenMeter;

    /** 配额结算（token 维度）；未装配时跳过（如单测上下文） */
    @Autowired(required = false)
    private AiQuotaService quotaService;

    /** 熔断器（LLM 故障时快速失败，避免拖满线程池） */
    @Autowired(required = false)
    private AiCircuitBreaker circuitBreaker;

    /** 指标（熔断打开计数） */
    @Autowired(required = false)
    private AiMetricsCollector metrics;

    /**
     * ChatClient 缓存（model → client）。
     *
     * <p>改造前的 {@link #client} 每调用一次就 {@code ChatClient.builder(...).build()}，
     * 本类 3 条调用路径（同步 / 流式 / 探针）× 每次请求都在重建：builder 会分配 spec、解析
     * observation 等，属纯浪费；且每次重建都拿不到 Spring AI 自动配置里
     * {@code ChatClientBuilderCustomizer} 的定制（用 {@code ChatClient.builder(model)} 静态方法
     * 绕过了自动配置的 {@code ChatClient.Builder} bean）。
     *
     * <p>用 {@link java.util.concurrent.ConcurrentHashMap} 缓存：模型 bean 是单例、数量固定
     * （当前 2~3 个），键基数不增长，无泄漏风险。探针路径刻意走裸 client（不挂 advisor），
     * 故不在此缓存内。
     */
    private final java.util.concurrent.ConcurrentHashMap<ChatModel, ChatClient> clientCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 同步生成（非流式）：自动挂安全横切（+ 可选会话记忆）+ 自动计量。
     *
     * @param feature        功能标识（{@link AiFeatures}），用于成本归因与模型路由
     * @param systemPrompt   system 消息
     * @param user           用户消息（null 视为空串）
     * @param memory         会话记忆实例（null=不挂记忆，如内部改写/精排调用）
     * @param conversationId 会话 key（memory 非空时必填）
     * @return 生成文本；失败返回 null
     */
    public String generateOrNull(String feature, String systemPrompt, String user,
                                 ChatMemory memory, String conversationId) {
        return generateOrNull(feature, resolveModel(feature), systemPrompt, user, memory, conversationId);
    }

    /**
     * 同步生成（显式指定模型）：供"功能已有专属模型 Bean"的调用点使用
     * （如评论治理的 commentChatModel；传入 null 时按"模型未装配"降级返回 null，
     * 保持调用方原有的"未配置即跳过"语义）。
     */
    public String generateOrNull(String feature, ChatModel explicitModel, String systemPrompt, String user,
                                 ChatMemory memory, String conversationId) {
        if (explicitModel == null) {
            log.warn("[AiLlmGateway] 模型未装配，跳过调用: feature={}", feature);
            return null;
        }
        // 熔断打开：快速失败（不去建立连接等超时），调用方按"生成失败"降级
        if (!allowByCircuit(AiCircuitBreaker.TARGET_LLM, feature)) {
            return null;
        }
        try {
            ChatClient.ChatClientRequestSpec spec = withAdvisors(
                    client(explicitModel).prompt().system(systemPrompt).user(user == null ? "" : user),
                    memory, conversationId);
            ChatResponse response = spec.call().chatResponse();
            circuitSuccess();
            tokenMeter.record(feature, response);
            settleQuota(response, systemPrompt, user);
            return response != null && response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
        } catch (SafetyGuardException e) {
            // 护栏命中不是"依赖故障"：不计入熔断失败，避免正常安全拦截把模型熔断掉
            log.warn("[AiLlmGateway] 输出护栏命中（顺从短语），丢弃该回答并降级: feature={}, {}", feature, e.getMessage());
            return null;
        } catch (Exception e) {
            circuitFailure();
            log.error("[AiLlmGateway] LLM 生成失败: feature={}", feature, e);
            return null;
        }
    }

    /**
     * 熔断放行判定；打开时记指标并打印降级日志。
     *
     * @param target  熔断目标（{@link AiCircuitBreaker} 的 TARGET_*），决定查哪一个熔断器；
     *                曾把 feature 当 target 传（feature 只进日志），语义错位已修正
     * @param feature 功能标识，仅用于日志定位，不影响熔断判定
     */
    private boolean allowByCircuit(String target, String feature) {
        if (circuitBreaker == null || circuitBreaker.allow(target)) {
            return true;
        }
        if (metrics != null) {
            metrics.incr("ai_circuit_rejected_" + target);
        }
        log.warn("[AiLlmGateway] {} 熔断打开中，快速失败（不发起调用）: feature={}", target, feature);
        return false;
    }

    /** 熔断成功上报（可空注入，单测上下文下跳过） */
    private void circuitSuccess() {
        if (circuitBreaker != null) {
            circuitBreaker.onSuccess(AiCircuitBreaker.TARGET_LLM);
        }
    }

    /** 熔断失败上报（可空注入，单测上下文下跳过） */
    private void circuitFailure() {
        if (circuitBreaker != null) {
            circuitBreaker.onFailure(AiCircuitBreaker.TARGET_LLM);
        }
    }

    /**
     * 流式生成：逐段回调增量文本，返回完整文本；自动计量。
     *
     * <p>计量口径：优先取流末块 metadata 中的真实 usage；若网关未回传（部分 OpenAI 兼容服务
     * 需要 stream_options.include_usage）则按字符估算并标记 estimated，保证成本面板不出现空洞。
     *
     * @param onDelta 增量回调（可为 null）
     * @return 完整文本；失败返回 null
     */
    public String generateStreamOrNull(String feature, String systemPrompt, String user,
                                       ChatMemory memory, String conversationId,
                                       Consumer<String> onDelta) {
        return generateStreamOrNull(feature, resolveModel(feature), systemPrompt, user,
                memory, conversationId, onDelta);
    }

    /** 流式生成（显式指定模型），语义同 {@link #generateOrNull(String, ChatModel, String, String, ChatMemory, String)} */
    public String generateStreamOrNull(String feature, ChatModel explicitModel, String systemPrompt, String user,
                                       ChatMemory memory, String conversationId,
                                       Consumer<String> onDelta) {
        if (explicitModel == null) {
            log.warn("[AiLlmGateway] 模型未装配，跳过流式调用: feature={}", feature);
            return null;
        }
        if (!allowByCircuit(AiCircuitBreaker.TARGET_LLM, feature)) {
            return null;
        }
        // 声明在 try 外：catch（取消）分支需要读取已生成内容与 usage 做计量
        StringBuilder acc = new StringBuilder();
        Usage[] lastUsage = new Usage[1];
        String[] model = new String[1];
        // 额度“到线即停”：开始前取一次可用额度快照，并把 prompt 侧估算先计入
        // （否则“还没输出就已超额度”的情况仍会白跑一轮）；后续在内存累计比对，不查存储
        final long quotaLimit = resolveStreamQuotaLimit();
        final int promptTokens = estimateTokens(
                (systemPrompt == null ? 0 : systemPrompt.length()) + (user == null ? 0 : user.length()));
        try {
            ChatClient.ChatClientRequestSpec spec = withAdvisors(
                    client(explicitModel).prompt().system(systemPrompt).user(user == null ? "" : user),
                    memory, conversationId);
            spec.stream().chatResponse()
                    .doOnNext(resp -> {
                        if (resp == null) {
                            return;
                        }
                        if (resp.getMetadata() != null) {
                            if (resp.getMetadata().getUsage() != null) {
                                lastUsage[0] = resp.getMetadata().getUsage();
                            }
                            if (resp.getMetadata().getModel() != null) {
                                model[0] = resp.getMetadata().getModel();
                            }
                        }
                        String delta = resp.getResult() != null && resp.getResult().getOutput() != null
                                ? resp.getResult().getOutput().getText() : null;
                        if (delta != null) {
                            acc.append(delta);
                            if (onDelta != null) {
                                onDelta.accept(delta);
                            }
                            // 触达可用额度即中断流迭代（沿途为本地纯计算、无 I/O，故可逐 chunk 判断）。
                            // 抛异常是本工程既有的中断手段——客户端断开也走 onDelta 抛异常这条路。
                            if (quotaLimit != Long.MAX_VALUE) {
                                long estimated = promptTokens + estimateTokens(acc.length());
                                if (estimated >= quotaLimit) {
                                    throw new QuotaExhaustedException("流式生成额度耗尽: feature=" + feature
                                            + ", 估算已用 " + estimated + " tokens >= 可用 " + quotaLimit);
                                }
                            }
                        }
                    })
                    .blockLast();

            long settledTokens = recordStreamUsage(feature, model[0], lastUsage[0], systemPrompt, user, acc.toString());
            settleQuotaByTokens(settledTokens);
            circuitSuccess();
            return acc.toString();
        } catch (SafetyGuardException e) {
            log.warn("[AiLlmGateway] 流式输出护栏命中，丢弃该回答并降级: feature={}, {}", feature, e.getMessage());
            return null;
        } catch (QuotaExhaustedException e) {
            // 额度耗尽：非故障（不计熔断失败）、也非用户取消——按已生成部分计量并结算后向上抛出。
            // 抛给调用方的原因：调用方必须知道"这是截断答案"，从而不写记忆、不落语义缓存，并给用户明确提示。
            handleQuotaExhausted(feature, model[0], lastUsage[0], systemPrompt, user, acc.toString());
            throw e;
        } catch (java.util.concurrent.CancellationException e) {
            // P2-3 流式取消：客户端断开导致 onDelta 抛取消异常中断流迭代。
            // 取消不是故障——不计熔断失败；已生成部分按字符估算计量（成本面板不留空洞）后丢弃
            recordCancelledUsage(feature, model[0], lastUsage[0], systemPrompt, user, acc.length());
            log.info("[AiLlmGateway] 流式生成被客户端取消: feature={}, 已生成 {} 字符", feature, acc.length());
            return null;
        } catch (Exception e) {
            // 兜底：部分响应框架会包装取消/额度异常，识别 cause 链分别处理
            if (isQuotaExhausted(e)) {
                handleQuotaExhausted(feature, model[0], lastUsage[0], systemPrompt, user, acc.toString());
                throw new QuotaExhaustedException("流式生成额度耗尽（包装异常）: feature=" + feature);
            }
            if (isCancellation(e)) {
                recordCancelledUsage(feature, model[0], lastUsage[0], systemPrompt, user, acc.length());
                log.info("[AiLlmGateway] 流式生成被客户端取消（包装异常）: feature={}, 已生成 {} 字符",
                        feature, acc.length());
                return null;
            }
            circuitFailure();
            log.error("[AiLlmGateway] LLM 流式生成失败: feature={}", feature, e);
            return null;
        }
    }

    /**
     * 探针：纯连通性诊断（frame-ping 用）。
     *
     * <p>与正式调用（{@link #generateOrNull(String, String, String, ChatMemory, String)}）不同：
     * 探针语义要求暴露原始链路行为，因此<b>不挂</b>安全护栏 / 会话记忆 advisor（护栏会拦截探针内容、
     * 引入额外语义，无法验证链路本身），<b>不计</b> token、<b>不结算</b>配额（诊断端点 + 登录/限频保护，
     * 成本可忽略）；但仍走熔断放行与成功/失败计数——探针失败同样反映 LLM 健康度，不绕过熔断观测。
     *
     * @param prompt 探针问题
     * @return 模型文本；模型未装配 / 熔断打开 / 调用失败 → null
     */
    public String probeOrNull(String prompt) {
        return probeInternal(null, prompt, null);
    }

    /**
     * 探针（带工具）：验证原生工具调用协议（tools-ping 用），语义同 {@link #probeOrNull(String)}。
     *
     * @param systemPrompt system 消息（可为 null）
     * @param userPrompt   用户消息（可含触发工具调用的指令）
     * @param toolProvider 工具回调提供者（如 {@code MethodToolCallbackProvider}）
     * @return 模型文本；模型未装配 / 熔断打开 / 调用失败 → null
     */
    public String probeWithToolsOrNull(String systemPrompt, String userPrompt,
                                       org.springframework.ai.tool.ToolCallbackProvider toolProvider) {
        return probeInternal(systemPrompt, userPrompt, toolProvider);
    }

    /** 探针共用实现：裸 ChatClient（无 advisors），保留熔断与统一日志 */
    private String probeInternal(String systemPrompt, String userPrompt,
                                 org.springframework.ai.tool.ToolCallbackProvider toolProvider) {
        ChatModel model = resolveModel(AiFeatures.OTHER);
        if (model == null) {
            log.warn("[AiLlmGateway] 探针跳过：模型未装配");
            return null;
        }
        if (!allowByCircuit(AiCircuitBreaker.TARGET_LLM, AiFeatures.OTHER)) {
            return null;
        }
        try {
            ChatClient.ChatClientRequestSpec spec = ChatClient.builder(model).build().prompt();
            // ChatClient 拒绝空 system 文本（IllegalArgumentException），探针允许 system 为 null → 跳过
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                spec = spec.system(systemPrompt);
            }
            if (userPrompt != null && !userPrompt.isEmpty()) {
                spec = spec.user(userPrompt);
            }
            if (toolProvider != null) {
                spec = spec.toolCallbacks(toolProvider);
            }
            ChatResponse response = spec.call().chatResponse();
            circuitSuccess();
            return response != null && response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
        } catch (Exception e) {
            circuitFailure();
            log.error("[AiLlmGateway] LLM 探针失败", e);
            return null;
        }
    }

    /** 取消场景计量：真实 usage 优先，缺失（null 或 0/0，Spring AI 空元数据返回非 null 空 Usage）按字符估算 */
    private void recordCancelledUsage(String feature, String model, Usage usage,
                                      String systemPrompt, String user, int generatedChars) {
        try {
            Integer p = usage != null ? usage.getPromptTokens() : null;
            Integer c = usage != null ? usage.getCompletionTokens() : null;
            boolean real = p != null && c != null && p + c > 0;
            int promptTokens = real ? p : estimateTokens(
                (systemPrompt == null ? 0 : systemPrompt.length()) + (user == null ? 0 : user.length()));
            int completionTokens = real ? c : estimateTokens(generatedChars);
            if (promptTokens + completionTokens > 0) {
                tokenMeter.record(feature, model == null ? "unknown" : model,
                        promptTokens, completionTokens, !real);
            }
        } catch (Exception ignore) {
            // 计量失败不影响取消主流程
        }
    }

    /** 判断异常链中是否包含取消语义（覆盖响应式框架包装场景） */
    private static boolean isCancellation(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (cur instanceof java.util.concurrent.CancellationException) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /** 识别「额度耗尽」中断（含被响应式框架包装的情况，与 {@link #isCancellation} 同款 cause 链遍历） */
    private static boolean isQuotaExhausted(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (cur instanceof QuotaExhaustedException) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 流式额度上限快照：可用 = 今日免费剩余 + 钱包 token 余额。
     *
     * <p>只在流开始前取<b>一次</b>（之后由 {@code doOnNext} 在内存里累计比对，避免逐 chunk 查 Redis/DB）。
     * 返回 {@link Long#MAX_VALUE} 表示不做流式限额——包括未装配配额服务、无用户上下文、查询异常三种情况。
     * 代价（已知）：并发请求可能各持同一份快照，属<b>并发超支</b>，需靠闸门侧原子预扣收敛。
     */
    private long resolveStreamQuotaLimit() {
        if (quotaService == null) {
            return Long.MAX_VALUE;
        }
        try {
            return quotaService.availableTokens(currentUserId());
        } catch (Exception e) {
            log.debug("[AiLlmGateway] 流式额度快照获取失败，本次不限额: {}", e.getMessage());
            return Long.MAX_VALUE;
        }
    }

    /**
     * 流式额度耗尽收尾：按已生成部分计量 + 结算（成本面板不留空洞）+ 打指标。
     *
     * <p>与「客户端取消」的区别：取消是用户主动放弃，这里是额度到线被动中断。两者都不计熔断失败，
     * 但<b>指标与用户提示必须分开</b>，否则无法区分"该扩容"还是"查客户端"。由调用方负责告知前端。
     */
    private void handleQuotaExhausted(String feature, String model, Usage usage,
                                      String systemPrompt, String user, String generated) {
        long settled = recordStreamUsage(feature, model, usage, systemPrompt, user, generated);
        settleQuotaByTokens(settled);
        if (metrics != null) {
            metrics.incr("aiask_stream_quota_exhausted");
        }
        log.warn("[AiLlmGateway] 流式生成额度耗尽已中断: feature={}, 已生成 {} 字符, 结算 {} tokens",
                feature, generated == null ? 0 : generated.length(), settled);
    }

    /**
     * 流式生成过程中「额度到线」的信号异常。
     *
     * <p>为什么用异常而不是返回标记：中断流迭代的唯一通道就是回调抛异常
     * （框架本身也用它处理客户端断开，见 {@code onDelta} 抛 CancellationException 的既有用法）。
     * 且它<b>必须与取消区分开</b>——网关内部完成计量/结算后<b>向上抛出</b>，
     * 由调用方决定"不写记忆、不落语义缓存、并给用户明确提示"，避免把截断答案当成完整答案沉淀。
     */
    public static class QuotaExhaustedException extends RuntimeException {
        public QuotaExhaustedException(String message) {
            super(message);
        }
    }

    /**
     * 流式用量落库：真实 usage 优先，缺失（null 或 0/0）则按字符估算并标记 estimated。
     *
     * @return 本次计入的 tokens 总量（供配额结算复用，避免二次解析）
     */
    private long recordStreamUsage(String feature, String model, Usage usage,
                                   String systemPrompt, String user, String answer) {
        try {
            Integer p = usage != null ? usage.getPromptTokens() : null;
            Integer c = usage != null ? usage.getCompletionTokens() : null;
            int promptTokens = p == null ? 0 : p;
            int completionTokens = c == null ? 0 : c;
            if (promptTokens + completionTokens > 0) {
                tokenMeter.record(feature, model, promptTokens, completionTokens, false);
                return promptTokens + completionTokens;
            }
            // 网关未回传真实用量（部分 OpenAI 兼容服务需 stream_options.include_usage）：
            // 按字符估算，保证成本面板不留空洞，且 estimated 标记让口径可追溯
            int promptChars = (systemPrompt == null ? 0 : systemPrompt.length()) + (user == null ? 0 : user.length());
            int estPrompt = estimateTokens(promptChars);
            int estCompletion = estimateTokens(answer == null ? 0 : answer.length());
            tokenMeter.record(feature, model, estPrompt, estCompletion, true);
            return (long) estPrompt + estCompletion;
        } catch (Exception e) {
            log.warn("[AiLlmGateway] 流式用量计量失败(忽略): feature={}, err={}", feature, e.getMessage());
            return 0L;
        }
    }

    /** 配额结算（按已知 token 数，流式路径复用） */
    private void settleQuotaByTokens(long tokens) {
        if (quotaService == null || tokens <= 0) {
            return;
        }
        try {
            Integer userId = currentUserId();
            if (userId != null) {
                quotaService.settleTokens(userId, tokens);
            }
        } catch (Exception e) {
            log.warn("[AiLlmGateway] 配额结算失败(忽略): {}", e.getMessage());
        }
    }

    /**
     * 字符 → token 估算（1 token ≈ 2 字符），<b>仅用于网关未回传真实 usage 的兜底</b>。
     *
     * <p><b>口径方向（务必看清）</b>：本方法<b>低估</b> token 数（中文实际约 1.5 字符/token，
     * 此处按 2 字符/token 粗算，故算出来的 token 偏少）。选"低估"是因为它同时承担两个目标且
     * 两害相权取其轻：
     * <ul>
     *   <li><b>结算</b>（{@link #settleQuota}）——低估 = 平台少收，用户侧无投诉风险；</li>
     *   <li><b>限额</b>（{@link #resolveStreamQuotaLimit} 的 doOnNext 到线即停）——低估 = 用户可能
     *       实际消耗略超预算，属"少拦一点"，比"误拦正常请求"体验伤害小。</li>
     * </ul>
     *
     * <p><b>已知取舍</b>：两个目标对估算方向的要求是<b>相反</b>的（结算想不虚报=低估，
     * 硬限额想不超支=高估）。当前实现统一选低估。若将来需要"硬限额不超支"，应改为
     * <b>按真实 usage 前置预扣</b>，而不是把这里的比例调大——调大会连带把结算也算高。
     */
    static int estimateTokens(int chars) {
        return chars <= 0 ? 0 : Math.max(1, chars / 2);
    }

    /**
     * 配额结算（token 维度）：从 ThreadLocal 取当前登录用户；未登录/未装配则跳过。
     *
     * <p>usage 缺失（网关未回传）时**按字符估算**结算，与流式路径 {@link #settleQuotaByTokens(long)}
     * 口径一致 —— 否则同步调用在 usage 缺失场景下用户不扣费、成本由平台吸收。
     */
    private void settleQuota(ChatResponse response, String systemPrompt, String user) {
        if (quotaService == null || response == null || response.getMetadata() == null) {
            return;
        }
        try {
            Integer userId = currentUserId();
            if (userId == null) {
                return; // 内部无登录态调用（如定时任务）不结算
            }
            Usage usage = response.getMetadata().getUsage();
            int total = 0;
            if (usage != null) {
                total = (usage.getPromptTokens() == null ? 0 : usage.getPromptTokens())
                      + (usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
            }
            if (total <= 0) {
                // 与流式路径同口径：真实 usage 缺失时按字符估算，避免同步调用"漏收"
                String answer = response.getResult() != null && response.getResult().getOutput() != null
                        ? response.getResult().getOutput().getText() : null;
                int promptChars = (systemPrompt == null ? 0 : systemPrompt.length())
                        + (user == null ? 0 : user.length());
                total = estimateTokens(promptChars) + estimateTokens(answer == null ? 0 : answer.length());
            }
            if (total > 0) {
                quotaService.settleTokens(userId, total);
            }
        } catch (Exception e) {
            // 结算旁路：失败只告警，绝不影响已完成的 LLM 调用结果
            log.warn("[AiLlmGateway] 配额结算失败(忽略): {}", e.getMessage());
        }
    }

    /** 当前登录用户（网关/控制器已把用户放入 ThreadLocal） */
    private Integer currentUserId() {
        com.zhuri.coding.model.user.pojos.ApUser u = com.zhuri.coding.utils.thread.AppThreadLocalUtil.getUser();
        return u == null ? null : u.getId();
    }

    /** 按 feature 解析模型：优先路由层（多模型阶段），其次注入的默认模型 */
    private ChatModel resolveModel(String feature) {
        if (modelRouter != null) {
            try {
                ChatModel routed = modelRouter.resolve(feature == null ? AiFeatures.OTHER : feature);
                if (routed != null) {
                    return routed;
                }
            } catch (Exception e) {
                log.warn("[AiLlmGateway] 模型路由失败，回退默认模型: feature={}, err={}", feature, e.getMessage());
            }
        }
        return chatModel;
    }

    /**
     * 统一客户端装配：安全横切必挂（走 defaultAdvisors，可缓存复用）；会话记忆 advisor
     * <b>不在此处装配</b>，由调用方经 {@link #withConversation} 在请求级挂载。
     *
     * <p><b>为什么不把 memory advisor 放进 defaultAdvisors</b>：{@code ChatMemory} 是<b>请求级实例</b>
     * （每个会话一次构建，见调用方），而 {@code defaultAdvisors} 属于"客户端级"配置——
     * 若把 {@code MessageChatMemoryAdvisor.builder(memory).build()} 放进来，就必须为每个请求
     * 重建 ChatClient（本类改造前的做法），客户端复用彻底失效，且 advisor 重复构建产生无谓对象。
     *
     * <p><b>为什么缓存是安全的</b>：ChatClient 自身不可变——{@code prompt()} 会复制一份请求级
     * {@code DefaultChatClientRequestSpec}，运行时的 {@code advisors(...)} / {@code options(...)}
     * 等改动<b>只作用于该副本</b>（字节码实证：拷贝构造器与 {@code advisors(...)} 均为
     * {@code List.addAll} 到新 spec，不触碰 default 配置）。因此按 model 缓存 client 后，
     * 各请求的 advisor 叠加互不干扰。
     */
    private ChatClient client(ChatModel model) {
        return clientCache.computeIfAbsent(model, m -> ChatClient.builder(m)
                .defaultAdvisors(promptSafetyAdvisor)
                .build());
    }

    /**
     * 会话记忆 advisor 的请求级挂载。
     *
     * <p>与 {@link #withConversation} 合并为一次 {@code advisors(...)} 调用（两者都是对同一个
     * advisor 规格的补充：一个加 advisor 本体、一个加它的 param）。运行时 {@code advisors(...)}
     * 是 <b>append 语义</b>，叠加在 {@code defaultAdvisors} 之后 → 最终链为
     * {@code [promptSafetyAdvisor, MessageChatMemoryAdvisor]}，与改造前顺序一致。
     *
     * <p>{@code MessageChatMemoryAdvisor} 必须<b>每个请求新建</b>：它持有该请求的
     * {@code ChatMemory} 实例，跨请求复用会把别人的记忆窗口串进当前会话。
     */
    private ChatClient.ChatClientRequestSpec withAdvisors(ChatClient.ChatClientRequestSpec spec,
                                                          ChatMemory memory, String conversationId) {
        if (memory == null || conversationId == null) {
            return spec;
        }
        return spec.advisors(a -> a
                .advisors(MessageChatMemoryAdvisor.builder(memory).build())
                .param(ChatMemory.CONVERSATION_ID, conversationId));
    }
}
