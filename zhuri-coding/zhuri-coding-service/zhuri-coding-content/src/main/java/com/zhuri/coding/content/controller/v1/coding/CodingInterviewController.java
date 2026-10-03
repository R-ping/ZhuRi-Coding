package com.zhuri.coding.content.controller.v1.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.coding.CodingInterviewService;
import com.zhuri.coding.model.coding.dtos.CodingInterviewFinishDTO;
import com.zhuri.coding.model.coding.dtos.CodingInterviewStartDTO;
import com.zhuri.coding.model.coding.dtos.CodingInterviewTurnDTO;
import com.zhuri.coding.model.coding.vos.CodingInterviewTurnVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 模拟面试接口（Coding 延展第三层 · Stage A）
 *
 * <p>路径：/api/v1/coding/interview（经网关 /content 前缀转发）。全部需登录
 * （以 ThreadLocal 用户为准，不接受外部传 userId）。turn 为 SSE 流式：
 * delta=面试官发言增量 / done=本轮完整发言+进度 / error=业务错误（额度耗尽带 [3301] 前缀）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/coding/interview")
public class CodingInterviewController {

    /** done 事件 VO 序列化（ObjectMapper 线程安全，静态复用避免每次 new） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private CodingInterviewService interviewService;

    /** SSE 流式专用线程池（见 AiAsyncConfig）：隔离 LLM 长阻塞，且 TaskDecorator 传递用户上下文保配额结算 */
    @Autowired
    @Qualifier("aiSseExecutor")
    private Executor aiSseExecutor;

    /**
     * 开面：进行中未超时直接续答；否则每日场次校验 → 额度预检 → 提纲生成 → 落库
     * POST /api/v1/coding/interview/start
     * {"direction": "Java 后端", "difficulty": 2}
     */
    @PostMapping("/start")
    public ResponseResult start(@RequestBody CodingInterviewStartDTO dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return interviewService.start(user.getId(), dto);
    }

    /**
     * 进行中的面试（懒过期：已超时置过期并返回空；供刷新恢复）
     * GET /api/v1/coding/interview/current
     */
    @GetMapping("/current")
    public ResponseResult current() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return interviewService.current(user.getId());
    }

    /**
     * 提交作答（SSE）：流式返回追问/下一题；完成后 done 事件带 turnCount/completed
     * POST /api/v1/coding/interview/turn
     * {"interviewId": 1, "answer": "...", "turnSeq": 0}
     */
    @PostMapping(value = "/turn", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter turn(@RequestBody CodingInterviewTurnDTO dto) {
        SseEmitter emitter = new SseEmitter(120_000L);
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            emitter.completeWithError(new RuntimeException("NEED_LOGIN"));
            return emitter;
        }
        Integer uid = user.getId();
        // SSE 异步线程内 ThreadLocal 不可见，userId 在此显式捕获传入
        // 流式取消：客户端断开（onError/onTimeout/send 失败）置位，下一个 delta 抛
        // CancellationException 中断生成（省 token）；本轮不落库，刷新后可重试
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(t -> cancelled.set(true));
        CompletableFuture.runAsync(() -> {
            try {
                interviewService.turnStream(uid, dto, new CodingInterviewService.TurnSink() {
                    @Override
                    public void delta(String text) {
                        if (cancelled.get()) {
                            throw new CancellationException("client aborted");
                        }
                        try {
                            emitter.send(SseEmitter.event()
                                .name("delta").data(text, MediaType.TEXT_PLAIN));
                        } catch (Exception e) {
                            // 客户端断开：置位 + 抛取消异常中断生成（省 token）
                            cancelled.set(true);
                            throw new CancellationException("sse send failed");
                        }
                    }

                    @Override
                    public void done(CodingInterviewTurnVO vo) {
                        try {
                            emitter.send(SseEmitter.event()
                                .name("done")
                                .data(OBJECT_MAPPER.writeValueAsString(vo), MediaType.APPLICATION_JSON));
                        } catch (Exception e) {
                            log.warn("模拟面试轮次收尾发送失败", e);
                        }
                    }

                    @Override
                    public void error(String message) {
                        try {
                            emitter.send(SseEmitter.event()
                                .name("error").data(message, MediaType.TEXT_PLAIN));
                        } catch (Exception ignore) {
                        }
                    }
                });
            } catch (AiLlmGateway.QuotaExhaustedException qe) {
                // 额度到线被动中断：非故障，前端凭 [3301] 前缀引导充值（已下发的增量文本保留）
                log.warn("模拟面试轮次额度耗尽中断: {}", qe.getMessage());
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("[" + AppHttpCodeEnum.AI_QUOTA_EXHAUSTED.getCode() + "] "
                            + AppHttpCodeEnum.AI_QUOTA_EXHAUSTED.getErrorMessage(), MediaType.TEXT_PLAIN));
                } catch (Exception ignore) {
                }
            } catch (Exception e) {
                log.error("模拟面试轮次异常", e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error").data("面试官暂时离线，请重试", MediaType.TEXT_PLAIN));
                } catch (Exception ignore) {
                }
            } finally {
                emitter.complete();
            }
        }, aiSseExecutor);
        return emitter;
    }

    /**
     * 结束面试：幂等（已生成报告直接回放；报告缺失/解析失败则重新生成）；超时拒绝
     * POST /api/v1/coding/interview/finish
     * {"interviewId": 1}
     */
    @PostMapping("/finish")
    public ResponseResult finish(@RequestBody CodingInterviewFinishDTO dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return interviewService.finish(user.getId(), dto);
    }

    /**
     * 面试报告 + 全量回放（仅本人）
     * GET /api/v1/coding/interview/report?id=1
     */
    @GetMapping("/report")
    public ResponseResult report(@RequestParam("id") Long id) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return interviewService.report(user.getId(), id);
    }

    /**
     * 已完成场次历史（分页，按开面时间倒序）
     * GET /api/v1/coding/interview/history?page=1&size=10
     */
    @GetMapping("/history")
    public ResponseResult history(@RequestParam(value = "page", defaultValue = "1") Integer page,
                                  @RequestParam(value = "size", defaultValue = "10") Integer size) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return interviewService.history(user.getId(), page, size);
    }
}