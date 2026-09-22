package com.zhuri.coding.content.controller.v1.ai;

import com.zhuri.coding.content.service.ai.ArticleQaService;
import com.zhuri.coding.model.article.dtos.ArticleAskDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 单篇文章 AI 端点（Step3·① 文章速览 + 单篇问答）
 *
 * <p>GET /api/v1/ai/summary/{articleId}：AI 摘要（Redis 缓存 24h，游客可访问，IP 限频）
 * <p>POST /api/v1/ai/ask-article：对当前文章提问（答案只来自本文），SSE 流式，
 * 事件协议与 /ask/stream 一致：delta(文本增量) / done(JSON) / error(文本)。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class AiArticleController {

    private static final int MAX_QUESTION_LEN = 200;

    @Autowired
    private ArticleQaService articleQaService;

    @Autowired
    private com.zhuri.coding.content.service.ai.AiQuotaService aiQuotaService;

    @Autowired
    private com.zhuri.coding.content.service.ai.AiMetricsCollector aiMetricsCollector;

    @Autowired
    @Qualifier("aiSseExecutor")
    private Executor aiSseExecutor;

    /** 相关问答：文章页"读完想问"问题列表（游客可见，点击提问才需登录；LLM 生成 + Redis 缓存 24h） */
    @GetMapping("/related-questions")
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.IP,
        count = 30, interval = 1, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult relatedQuestions(@RequestParam("articleId") Long articleId) {
        aiMetricsCollector.incr("ai_related_questions");
        if (articleId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        log.info("生成文章相关问题, articleId={}", articleId);
        java.util.List<String> questions = articleQaService.genRelatedQuestions(articleId);
        if (questions == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "相关问题暂不可用");
        }
        return ResponseResult.okResult(questions);
    }

    /** 单篇文章 AI 摘要（游客可访问；失败返回 503，由前端隐藏卡片） */
    @GetMapping("/summary/{articleId}")
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.IP,
        count = 30, interval = 1, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult summary(@PathVariable("articleId") Long articleId) {
        aiMetricsCollector.incr("ai_summary");
        if (articleId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        log.info("生成文章AI摘要, articleId={}", articleId);
        String summary = articleQaService.genSummary(articleId);
        if (summary == null) {
            // 文章不存在/未发布/生成失败统一按"不可用"处理，前端静默隐藏摘要卡
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "AI 摘要暂不可用");
        }
        return ResponseResult.okResult(summary);
    }

    /** 单篇文章流式问答（登录 + 限频；SSE：delta/done/error） */
    @PostMapping(value = "/ask-article", produces = "text/event-stream;charset=UTF-8")
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.USER,
        count = 5, interval = 1, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.IP,
        count = 20, interval = 1, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public SseEmitter askArticle(@RequestBody ArticleAskDto dto) {
        aiMetricsCollector.incr("aiask_article");
        SseEmitter emitter = new SseEmitter(120_000L);
        if (AppThreadLocalUtil.getUser() == null) {
            emitter.completeWithError(new RuntimeException("NEED_LOGIN"));
            return emitter;
        }
        if (dto == null || dto.getArticleId() == null
            || dto.getQuestion() == null || dto.getQuestion().trim().isEmpty()) {
            emitter.completeWithError(new RuntimeException("文章ID或问题不能为空"));
            return emitter;
        }
        String question = dto.getQuestion().trim();
        if (question.length() > MAX_QUESTION_LEN) {
            emitter.completeWithError(new RuntimeException("问题过长（最多" + MAX_QUESTION_LEN + "字）"));
            return emitter;
        }
        // AI 每日免费配额 → 钱包额度包（免费优先；超限发 error 事件友好提示，再正常收尾）
        Integer uid = AppThreadLocalUtil.getUser().getId();
        if (uid == null || !aiQuotaService.tryConsume(uid)) {
            try {
                emitter.send(SseEmitter.event().name("error")
                    .data("[" + AppHttpCodeEnum.AI_QUOTA_EXHAUSTED.getCode() + "] "
                        + AppHttpCodeEnum.AI_QUOTA_EXHAUSTED.getErrorMessage(), MediaType.TEXT_PLAIN));
            } catch (Exception ignore) {
            }
            emitter.complete();
            return emitter;
        }
        Long articleId = dto.getArticleId();
        java.util.List<java.util.Map<String, String>> history = dto.getHistory();
        // 异步执行：立即返回 emitter，流式事件在工作线程逐步发送
        CompletableFuture.runAsync(() -> {
            try {
                String full = articleQaService.streamAskArticle(articleId, question, history, delta -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta")
                            .data(delta, MediaType.TEXT_PLAIN));
                    } catch (Exception ignore) {
                        // 客户端断开
                    }
                });
                try {
                    String doneJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        java.util.Map.of("answer", full == null ? "" : full, "sources", java.util.Collections.emptyList()));
                    emitter.send(SseEmitter.event().name("done").data(doneJson, MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    log.warn("AI 单篇问答收尾发送失败, articleId={}", articleId, e);
                }
            } catch (IllegalArgumentException e) {
                log.warn("AI 单篇问答参数/文章异常, articleId={}, msg={}", articleId, e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error")
                        .data(e.getMessage(), MediaType.TEXT_PLAIN));
                } catch (Exception ignore) {
                }
            } catch (com.zhuri.coding.content.service.ai.AiLlmGateway.QuotaExhaustedException qe) {
                // 额度到线被动中断：不是服务故障，给用户可理解的原因（已下发的增量文本保留）
                log.warn("AI 单篇问答额度耗尽中断, articleId={}, msg={}", articleId, qe.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error")
                        .data("本次 AI 额度已用完，回答已中断；已生成内容仍然可用，可购买额度包或次日再试",
                            MediaType.TEXT_PLAIN));
                } catch (Exception ignore) {
                }
            } catch (Exception e) {
                log.error("AI 单篇问答异常, articleId={}", articleId, e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                        .data("AI 服务暂不可用，请稍后再试", MediaType.TEXT_PLAIN));
                } catch (Exception ignore) {
                }
            } finally {
                emitter.complete();
            }
        }, aiSseExecutor);
        return emitter;
    }
}
