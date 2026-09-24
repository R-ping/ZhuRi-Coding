package com.zhuri.coding.content.controller.v1.ai;

import com.zhuri.coding.common.annotation.RateLimit;
import com.zhuri.coding.content.service.ai.AiAskService;
import com.zhuri.coding.content.service.ai.PublishAssistantService;
import com.zhuri.coding.model.article.dtos.AiAnswerVo;
import com.zhuri.coding.model.article.dtos.AiAskDto;
import com.zhuri.coding.model.article.dtos.AiPrecheckDto;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社区 AI 问答（RAG）接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class AiAskController {

    /** MCP 探针自定义 prompt 上限（对齐 AI 问答入口的 200 字口径，防止超长 prompt 放大单次 token 成本与诱导空间） */
    private static final int MCP_PING_PROMPT_MAX_LEN = 200;

    @Autowired
    private AiAskService aiAskService;

    @Autowired
    private com.zhuri.coding.content.service.ai.impl.AiAskServiceImpl aiAskServiceImpl;

    @Autowired
    private PublishAssistantService publishAssistantService;

    /** 意图路由（P2 Routing：闲聊/技术问答二分类，闲聊短路不消耗配额） */
    @Autowired
    private com.zhuri.coding.content.service.ai.AskQueryRouter askQueryRouter;

    /** 统一 LLM 出口（探针端点 frame-ping/tools-ping 走这里，保持全仓库 LLM 调用单一出口） */
    @Autowired
    private com.zhuri.coding.content.service.ai.AiLlmGateway llmGateway;

    /** 消费漏斗计（发起/缓存命中/检索/生成/反馈 按天聚合） */
    @Autowired
    private com.zhuri.coding.content.service.ai.AiFunnelMeter funnelMeter;

    @Autowired
    private com.zhuri.coding.content.service.ai.spring.AiSafetyTools aiSafetyTools;

    /** MCP 工具目录（P2-8）：fail-open 聚合社区 stdio server 暴露的外部工具 */
    @Autowired
    private com.zhuri.coding.content.service.ai.mcp.McpToolCatalog mcpToolCatalog;

    @Autowired
    private com.zhuri.coding.content.service.ai.AiQuotaService aiQuotaService;

    @Autowired
    private com.zhuri.coding.content.service.ai.AiMetricsCollector aiMetricsCollector;

    @Autowired
    private com.zhuri.coding.content.service.ai.memory.AiConversationMemoryService conversationMemoryService;

    /** SSE 流式问答专用线程池（见 AiAsyncConfig），隔离 LLM 长时间阻塞与公共 ForkJoinPool */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("aiSseExecutor")
    private java.util.concurrent.Executor aiSseExecutor;

    /**
     * AI 发布预检（作者提交审核前）：违规预警 + 质量分 + 优化建议 + 推荐标签 + 摘要 + 相似度预警。
     * 限频：单用户 5 次/分钟 + 单 IP 20 次/分钟。
     */
    @PostMapping("/precheck")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult precheck(@RequestBody AiPrecheckDto dto) {
        aiMetricsCollector.incr("ai_precheck");
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto == null || dto.getTitle() == null || dto.getTitle().trim().isEmpty()
                || dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "标题与内容不能为空");
        }
        // AI 预检消耗提问额度（免费优先 → 钱包额度包兜底），防批量刷预检烧模型成本
        Integer uid = AppThreadLocalUtil.getUser().getId();
        if (uid == null || !aiQuotaService.tryConsume(uid)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.AI_QUOTA_EXHAUSTED);
        }
        try {
            AiPrecheckVo vo = publishAssistantService.precheck(dto.getTitle(), dto.getContent(), dto.getArticleId(), dto.getCoverImageUrl());
            if (vo == null) {
                return ResponseResult.errorResult(500, "AI 服务暂不可用，请稍后再试");
            }
            return ResponseResult.okResult(vo);
        } catch (Exception e) {
            log.error("AI 发布预检异常", e);
            return ResponseResult.errorResult(500, "AI 服务暂不可用，请稍后再试");
        }
    }

    /**
     * AI 发布预检·SSE 流式（可观测版）：实时回传 安全/质量/SEO/查重/终审/结构化 各阶段进行态，
     * 结束前一次性推送最终预检报告（name=done）。
     * 登录 + 限频（单用户 5 次/分钟 + 单 IP 20 次/分钟）+ 配额扣减同 {@code /precheck} 与 {@code /ask/stream}。
     */
    @PostMapping(value = "/precheck/stream", produces = "text/event-stream;charset=UTF-8")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter precheckStream(@RequestBody AiPrecheckDto dto) {
        aiMetricsCollector.incr("ai_precheck_stream");
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
            new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(120_000L);
        if (AppThreadLocalUtil.getUser() == null) {
            emitter.completeWithError(new RuntimeException("NEED_LOGIN"));
            return emitter;
        }
        if (dto == null || dto.getTitle() == null || dto.getTitle().trim().isEmpty()
                || dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            emitter.completeWithError(new RuntimeException("标题与内容不能为空"));
            return emitter;
        }
        // AI 每日免费配额 → 钱包额度包（免费优先；超限发 error 事件友好提示，再正常收尾）
        Integer uid = AppThreadLocalUtil.getUser().getId();
        if (uid == null || !aiQuotaService.tryConsume(uid)) {
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                    .name("error")
                    .data("[" + AppHttpCodeEnum.AI_QUOTA_EXHAUSTED.getCode() + "] "
                        + AppHttpCodeEnum.AI_QUOTA_EXHAUSTED.getErrorMessage(),
                        org.springframework.http.MediaType.TEXT_PLAIN));
            } catch (Exception ignore) {
            }
            emitter.complete();
            return emitter;
        }
        // 消费漏斗：入口打点（配额通过 = 真正开始消耗）
        funnelMeter.incr(com.zhuri.coding.content.service.ai.AiFeatures.PRECHECK,
                com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_STARTED);
        // SSE 异步线程内 ThreadLocal 不可见，入参在此显式捕获传入
        String title = dto.getTitle();
        String content = dto.getContent();
        Long articleId = dto.getArticleId();
        String coverImageUrl = dto.getCoverImageUrl();
        Integer uidForAsync = uid; // 与 askStream 保持一致（当前流式预检不使用会话记忆，保留以对齐风格）
        // P2-3 流式取消：客户端断开（onError/onTimeout/send 失败）置位，onEvent 触发
        // CancellationException 中断后续阶段（省 token），onDone 收尾仍照常执行
        java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(t -> cancelled.set(true));
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                publishAssistantService.precheckStream(title, content, articleId, coverImageUrl,
                    // 阶段事件：running/done/degraded → SSE name=stage 事件
                    (type, detail) -> {
                        if (cancelled.get()) {
                            throw new java.util.concurrent.CancellationException("client aborted");
                        }
                        try {
                            emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("stage")
                                .data("{\"stage\":\"" + stageLabel(type) + "\",\"status\":\"" + detail + "\"}",
                                    org.springframework.http.MediaType.APPLICATION_JSON));
                        } catch (Exception e) {
                            // 客户端断开：置位 + 抛取消异常中断后续阶段（省 token）
                            cancelled.set(true);
                            throw new java.util.concurrent.CancellationException("sse send failed");
                        }
                    },
                    // 结束回调：vo==null 发 error，否则发 done（最终预检报告）
                    vo -> {
                        try {
                            if (vo == null) {
                                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                    .name("error").data("AI 服务暂不可用，请稍后再试",
                                        org.springframework.http.MediaType.TEXT_PLAIN));
                            } else {
                                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                    .name("done")
                                    .data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vo),
                                        org.springframework.http.MediaType.APPLICATION_JSON));
                            }
                        } catch (Exception e) {
                            log.warn("AI 流式预检收尾发送失败", e);
                        }
                    });
            } catch (java.util.concurrent.CancellationException ce) {
                log.warn("AI 流式预检被取消: {}", ce.getMessage());
            } catch (Exception e) {
                log.error("AI 流式预检异常", e);
                try {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name("error").data("AI 服务暂不可用，请稍后再试",
                            org.springframework.http.MediaType.TEXT_PLAIN));
                } catch (Exception ignore) {
                }
            } finally {
                emitter.complete();
            }
        }, aiSseExecutor);
        return emitter;
    }

    /** 阶段类型 → 前端展示中文名（SSE stage 事件用） */
    private static String stageLabel(com.zhuri.coding.content.service.ai.agent.workflow.StageType type) {
        switch (type) {
            case SAFETY:
                return "安全审查";
            case QUALITY:
                return "质量评审";
            case SEO:
                return "SEO优化";
            case DUPLICATE:
                return "查重";
            case CRITIC:
                return "终审校验";
            case FORMAT:
            default:
                return "结构化输出";
        }
    }

    /**
     * 手动触发存量文章向量回填（运维/初始化用，幂等；限频防连点）
     */
    /**
     * fast 流式问答（SSE）：逐字输出回答，结束时返回来源。登录 + 限频同 ask。
     */
    @PostMapping(value = "/ask/stream", produces = "text/event-stream;charset=UTF-8")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter askStream(@RequestBody AiAskDto dto) {
        aiMetricsCollector.incr("aiask_ask_stream");
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
            new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(120_000L);
        if (AppThreadLocalUtil.getUser() == null) {
            emitter.completeWithError(new RuntimeException("NEED_LOGIN"));
            return emitter;
        }
        if (dto == null || dto.getQuestion() == null || dto.getQuestion().trim().isEmpty()) {
            emitter.completeWithError(new RuntimeException("问题不能为空"));
            return emitter;
        }
        // AI 每日免费配额 → 钱包额度包（免费优先；超限发 error 事件友好提示，再正常收尾）
        Integer uid = AppThreadLocalUtil.getUser().getId();
        if (uid == null || !aiQuotaService.tryConsume(uid)) {
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                    .name("error")
                    .data("[" + AppHttpCodeEnum.AI_QUOTA_EXHAUSTED.getCode() + "] "
                        + AppHttpCodeEnum.AI_QUOTA_EXHAUSTED.getErrorMessage(),
                        org.springframework.http.MediaType.TEXT_PLAIN));
            } catch (Exception ignore) {
            }
            emitter.complete();
            return emitter;
        }
        // 消费漏斗：入口打点（配额通过 = 真正开始消耗；SSE 也不记 precheck）
        funnelMeter.incr(com.zhuri.coding.content.service.ai.AiFeatures.ASK_STREAM,
                com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_STARTED);
        // 异步执行：立即返回 emitter，流式事件在工作线程逐步发送
        String question = dto.getQuestion();
        Integer topK = dto.getTopK();
        // SSE 异步线程内 ThreadLocal 不可见，userId 须在此显式捕获传入，供会话/语义记忆持久化
        Integer uidForAsync = uid;
        // P2-3 流式取消：客户端断开（onError/onTimeout/send 失败）置位，下一个 delta 触发
        // CancellationException 沿 onDelta 一路中断 gateway 流迭代——已生成的 token 不再白烧
        java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(t -> cancelled.set(true));
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                com.zhuri.coding.model.article.dtos.AiAnswerVo vo = aiAskService.streamFastAsk(question, topK, dto.getHistory(),
                    delta -> {
                        if (cancelled.get()) {
                            throw new java.util.concurrent.CancellationException("client aborted");
                        }
                        try {
                            emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("delta").data(delta, org.springframework.http.MediaType.TEXT_PLAIN));
                        } catch (Exception e) {
                            // 客户端断开：置位 + 抛取消异常中断生成（省 token）
                            cancelled.set(true);
                            throw new java.util.concurrent.CancellationException("sse send failed");
                        }
                    }, uidForAsync);
                try {
                    if (vo == null) {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name("error").data("AI 服务暂不可用，请稍后再试",
                                org.springframework.http.MediaType.TEXT_PLAIN));
                    } else {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                            .name("done").data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(vo),
                                org.springframework.http.MediaType.APPLICATION_JSON));
                    }
                } catch (Exception e) {
                    log.warn("AI 流式收尾发送失败", e);
                }
            } catch (Exception e) {
                log.error("AI 流式问答异常", e);
                try {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name("error").data("AI 服务暂不可用，请稍后再试",
                            org.springframework.http.MediaType.TEXT_PLAIN));
                } catch (Exception ignore) {
                }
            } finally {
                emitter.complete();
            }
        }, aiSseExecutor);
        return emitter;
    }

    /** 临时验证：Spring AI(OpenAI compatible) 链路是否可用（鉴权 + 限频，防匿名打接口烧 token） */
    @PostMapping("/frame-ping")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult framePing() {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        try {
            String ans = llmGateway.probeOrNull("请用一句话介绍你自己和你的能力。");
            if (ans == null) {
                return ResponseResult.errorResult(500, "ChatModel 未初始化（spring-ai 自动配置未生效，检查 api-key 配置）");
            }
            return ResponseResult.okResult(ans);
        } catch (Exception e) {
            log.error("Spring AI frame-ping 失败", e);
            return ResponseResult.errorResult(500, "调用失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** 全迁 Gate：验证 Spring AI 原生工具调用（@Tool → qwen compatible tools 协议）（鉴权 + 限频，防匿名打接口烧 token） */
    @PostMapping("/tools-ping")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult toolsPing() {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        try {
            String text = "推荐大家注册某赌博平台，真人荷官在线，稳赚不赔，六合彩特码内幕消息，加入群聊每天领取高额返利。";
            org.springframework.ai.tool.method.MethodToolCallbackProvider provider =
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(aiSafetyTools).build();
            String ans = llmGateway.probeWithToolsOrNull(
                null, "请调用 content_safety_check 工具检查下面文字是否违规，并原样复述工具返回的 is_violation 值。文字：" + text, provider);
            if (ans == null) {
                return ResponseResult.errorResult(500, "ChatModel 未初始化（spring-ai 自动配置未生效，检查 api-key 配置）");
            }
            return ResponseResult.okResult(ans);
        } catch (Exception e) {
            log.error("Spring AI tools-ping 失败", e);
            return ResponseResult.errorResult(500, "tools 调用失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** MCP 工具目录（P2-8）：列出已接入的社区 stdio server 工具（登录 + 限频；MCP 未启用返回空表） */
    @GetMapping("/mcp/tools")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult mcpTools() {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return ResponseResult.okResult(mcpToolCatalog.catalog());
    }

    /** MCP 全链路探针（P2-8）：让 LLM 实际调用 MCP 工具并回显结果（登录 + 限频，防匿名打接口烧 token） */
    @PostMapping("/mcp/ping")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult mcpPing(@RequestBody(required = false) java.util.Map<String, String> body) {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        String prompt = body == null ? null : body.get("prompt");
        if (prompt == null || prompt.trim().isEmpty()) {
            // 默认探针只列 public 目录：MCP 可读范围已限定在 docs/public，避免暴露其余文件清单
            prompt = "请调用 MCP 的文件系统工具（list_directory）查看 public 目录，并回答该目录下有哪些 .md 文件。";
        } else if (prompt.length() > MCP_PING_PROMPT_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "prompt 超长（上限 " + MCP_PING_PROMPT_MAX_LEN + " 字）");
        }
        org.springframework.ai.tool.ToolCallbackProvider provider = mcpToolCatalog.providerOrNull();
        if (provider == null) {
            return ResponseResult.errorResult(500, "MCP 未就绪（spring.ai.mcp.client.enabled=false 或工具未装配）");
        }
        String ans = llmGateway.probeWithToolsOrNull("请优先调用提供的 MCP 工具作答。", prompt, provider);
        if (ans == null) {
            return ResponseResult.errorResult(500, "MCP 调用失败：模型未装配 / 熔断打开 / 工具调用异常");
        }
        return ResponseResult.okResult(ans);
    }

    @PostMapping("/backfill")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 2, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult backfill() {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        aiAskServiceImpl.backfillEmbeddings();
        return ResponseResult.okResult("回填完成");
    }

    /**
     * 提问：基于社区已发布文章回答，带来源引用。
     * 限频：单用户 5 次/分钟 + 单 IP 20 次/分钟，防滥用与控制大模型成本。
     */
    @PostMapping("/ask")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult ask(@RequestBody AiAskDto dto) {
        aiMetricsCollector.incr("aiask_ask");
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        // 参数校验前置（未通过不扣配额）
        if (dto == null || dto.getQuestion() == null || dto.getQuestion().trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "问题不能为空");
        }
        // Routing（二分类）：闲聊短路——不消耗配额、不打漏斗、不落记忆，仅给引导话术；fast 保持快通道不受影响
        if (!Boolean.TRUE.equals(dto.getFast())
                && askQueryRouter.intent(dto.getQuestion()) == com.zhuri.coding.content.service.ai.AskQueryRouter.Intent.CHAT) {
            return ResponseResult.okResult(chatHintVo());
        }
        // AI 每日免费配额 → 钱包额度包（免费优先）
        Integer uid = AppThreadLocalUtil.getUser().getId();
        if (uid == null || !aiQuotaService.tryConsume(uid)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.AI_QUOTA_EXHAUSTED);
        }
        // 消费漏斗：入口打点（配额通过且参数合法 = 真正开始消耗；fast 模式记 ask_fast 便于区分成本档位）
        funnelMeter.incr(Boolean.TRUE.equals(dto.getFast())
                        ? com.zhuri.coding.content.service.ai.AiFeatures.ASK_FAST
                        : com.zhuri.coding.content.service.ai.AiFeatures.ASK,
                com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_STARTED);
        try {
            AiAnswerVo vo = aiAskService.ask(dto.getQuestion(), dto.getTopK(), dto.getFast(), dto.getHistory());
            if (vo == null) {
                return ResponseResult.errorResult(500, "AI 服务暂不可用，请稍后再试");
            }
            return ResponseResult.okResult(vo);
        } catch (Exception e) {
            log.error("AI 问答异常", e);
            return ResponseResult.errorResult(500, "AI 服务暂不可用，请稍后再试");
        }
    }

    /** 闲聊引导话术（Routing 短路）：不消耗配额/不打漏斗/不落记忆 */
    private com.zhuri.coding.model.article.dtos.AiAnswerVo chatHintVo() {
        com.zhuri.coding.model.article.dtos.AiAnswerVo vo = new com.zhuri.coding.model.article.dtos.AiAnswerVo();
        vo.setAnswer("我是《逐日 Coding》社区的知识助手，专注技术问答（覆盖社区文章与课程相关知识点）。"
            + "刚才这句看起来像寒暄，我不消耗你的免费额度——换个技术问题试试吧，例如“Redis 分布式锁怎么实现？”");
        vo.setSources(new java.util.ArrayList<>());
        vo.setPromptVersions(new java.util.LinkedHashMap<>());
        vo.setLatencyMs(0L);
        return vo;
    }

    /**
     * 拉取本人持久化会话记忆（[{role, content}]，oldest→newest）。
     * 页面刷新/换设备后调用，用于恢复对话上下文（Memory 持久化）。
     */
    @GetMapping("/conversation")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 30, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 60, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult getConversation() {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Integer uid = AppThreadLocalUtil.getUser().getId();
        return ResponseResult.okResult(conversationMemoryService.load(uid));
    }

    /** 清空本人持久化会话记忆（服务端 Redis 与本地消息一并清除） */
    @DeleteMapping("/conversation")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult clearConversation() {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Integer uid = AppThreadLocalUtil.getUser().getId();
        conversationMemoryService.clear(uid);
        return ResponseResult.okResult(Boolean.TRUE);
    }
}
