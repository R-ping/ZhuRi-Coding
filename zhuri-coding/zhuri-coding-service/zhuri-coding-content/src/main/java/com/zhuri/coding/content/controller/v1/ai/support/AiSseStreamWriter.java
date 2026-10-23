package com.zhuri.coding.content.controller.v1.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.service.ai.qa.AiStage;
import com.zhuri.coding.content.service.ai.qa.AiStageStatus;
import com.zhuri.coding.content.service.ai.qa.AiStreamSink;
import com.zhuri.coding.model.article.dtos.AiAnswerVo;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 统一 SSE 写入器：stage / delta / done / error + 客户端断开取消。
 *
 * <p>所有问答入口共用，避免控制器各自维护一份发送与异常分支。
 */
@Slf4j
public class AiSseStreamWriter implements AiStreamSink {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SseEmitter emitter;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public AiSseStreamWriter(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(t -> cancelled.set(true));
    }

    @Override
    public void stage(AiStage stage, AiStageStatus status) {
        if (stage == null || status == null) {
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("stage", stage.name().toLowerCase());
        payload.put("status", status.name().toLowerCase());
        send("stage", toJson(payload), MediaType.APPLICATION_JSON);
    }

    @Override
    public void delta(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        send("delta", text, MediaType.TEXT_PLAIN);
    }

    public void done(AiAnswerVo vo) {
        send("done", toJson(vo), MediaType.APPLICATION_JSON);
    }

    public void error(String message) {
        send("error", message == null ? "AI 服务暂不可用，请稍后再试" : message, MediaType.TEXT_PLAIN);
    }

    public void complete() {
        emitter.complete();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    private void send(String name, String data, MediaType type) {
        if (cancelled.get()) {
            throw new CancellationException("client aborted");
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data, type));
        } catch (Exception e) {
            cancelled.set(true);
            throw new CancellationException("sse send failed");
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[AiSse] JSON 序列化失败", e);
            return "{}";
        }
    }
}
