package com.heima.content.controller.v1.ai;

import com.heima.common.annotation.RateLimit;
import com.heima.content.service.ai.AiAskService;
import com.heima.content.service.ai.PublishAssistantService;
import com.heima.model.article.dtos.AiAnswerVo;
import com.heima.model.article.dtos.AiAskDto;
import com.heima.model.article.dtos.AiPrecheckDto;
import com.heima.model.article.dtos.AiPrecheckVo;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private AiAskService aiAskService;

    @Autowired
    private com.heima.content.service.ai.impl.AiAskServiceImpl aiAskServiceImpl;

    @Autowired
    private PublishAssistantService publishAssistantService;

    /** Spring AI ChatModel（OpenAI compatible 自动配置 bean；未装配时为 null，不影响其它接口） */
    @Autowired(required = false)
    private org.springframework.ai.chat.model.ChatModel frameChatModel;

    @Autowired
    private com.heima.content.service.ai.spring.AiSafetyTools aiSafetyTools;

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
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto == null || dto.getTitle() == null || dto.getTitle().trim().isEmpty()
                || dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "标题与内容不能为空");
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
     * 手动触发存量文章向量回填（运维/初始化用，幂等；限频防连点）
     */
    /**
     * fast 流式问答（SSE）：逐字输出回答，结束时返回来源。登录 + 限频同 ask。
     */
    @PostMapping(value = "/ask/stream", produces = "text/event-stream;charset=UTF-8")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter askStream(@RequestBody AiAskDto dto) {
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
        // 异步执行：立即返回 emitter，流式事件在工作线程逐步发送
        String question = dto.getQuestion();
        Integer topK = dto.getTopK();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                com.heima.model.article.dtos.AiAnswerVo vo = aiAskService.streamFastAsk(question, topK, dto.getHistory(),
                    delta -> {
                        try {
                            emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("delta").data(delta, org.springframework.http.MediaType.TEXT_PLAIN));
                        } catch (Exception ignore) {
                            // 客户端断开
                        }
                    });
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
            if (frameChatModel == null) {
                return ResponseResult.errorResult(500, "ChatModel 未初始化（spring-ai 自动配置未生效，检查 api-key 配置）");
            }
            org.springframework.ai.chat.messages.UserMessage um =
                new org.springframework.ai.chat.messages.UserMessage("请用一句话介绍你自己和你的能力。");
            org.springframework.ai.chat.prompt.Prompt prompt = new org.springframework.ai.chat.prompt.Prompt(um);
            String ans = frameChatModel.call(prompt).getResult().getOutput().getText();
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
            if (frameChatModel == null) {
                return ResponseResult.errorResult(500, "ChatModel 未初始化");
            }
            org.springframework.ai.chat.client.ChatClient client =
                org.springframework.ai.chat.client.ChatClient.builder(frameChatModel).build();
            org.springframework.ai.tool.method.MethodToolCallbackProvider provider =
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(aiSafetyTools).build();
            String text = "推荐大家注册某赌博平台，真人荷官在线，稳赚不赔，六合彩特码内幕消息，加入群聊每天领取高额返利。";
            String ans = client.prompt("请调用 content_safety_check 工具检查下面文字是否违规，并原样复述工具返回的 is_violation 值。文字：" + text)
                .toolCallbacks(provider)
                .call()
                .content();
            return ResponseResult.okResult(ans);
        } catch (Exception e) {
            log.error("Spring AI tools-ping 失败", e);
            return ResponseResult.errorResult(500, "tools 调用失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
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
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto == null || dto.getQuestion() == null || dto.getQuestion().trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "问题不能为空");
        }
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
}
