package com.heima.content.controller.v1.ai;

import com.heima.content.mapper.ai.AiFeedbackMapper;
import com.heima.content.service.ai.AiMetricsCollector;
import com.heima.model.ai.pojos.AiFeedback;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 观测端点：功能调用次数 + 反馈采纳信号（👍/👎 计数）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class AiMetricsController {

    @Autowired
    private AiMetricsCollector aiMetricsCollector;

    @Autowired
    private com.heima.content.service.ai.AiTokenMeter aiTokenMeter;

    @Autowired(required = false)
    private com.heima.content.service.ai.AiCircuitBreaker circuitBreaker;

    /** 消费漏斗计（发起/缓存命中/检索/生成/反馈 按天聚合 + 转化率） */
    @Autowired
    private com.heima.content.service.ai.AiFunnelMeter funnelMeter;

    @Autowired
    private AiFeedbackMapper aiFeedbackMapper;

    /**
     * token 用量成本面板：按 feature / 按天聚合（最近 N 天）。
     *
     * <p>用于回答"哪个 AI 功能最烧钱"——rerank 是短 prompt、创作复盘是长输出，
     * 只有按 token 维度拆开看才能做成本决策（模型路由选型、额度包定价）。
     */
    @GetMapping("/metrics/tokens")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult tokens(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        Map<String, Object> data = new HashMap<>();
        data.put("processSnapshot", aiTokenMeter.snapshot());
        data.put("summary", aiTokenMeter.summary(days));
        return ResponseResult.okResult(data);
    }

    /**
     * 熔断状态：llm / embedding 各自是否打开、窗口内失败数。
     *
     * <p>排障口径：{@code open=true} 表示"AI 依赖故障中，服务正在快速失败并降级" ——
     * 此时 RAG 会退化为无 AI 能力的路径，与"业务代码 bug"区分开。
     */
    @GetMapping("/metrics/circuit")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult circuit() {
        Map<String, Object> data = new HashMap<>();
        data.put("circuit", circuitBreaker == null ? "unavailable" : circuitBreaker.snapshot());
        return ResponseResult.okResult(data);
    }

    /**
     * 消费漏斗：发起 → 检索 → 生成 → 反馈 各阶段按天聚合 + 转化率。
     *
     * <p>用于回答"AI 问答一路走到被用户认可的转化率"——多少提问真的生成了回答
     * （{@code askToGenerated}）、缓存命中省了多少模型调用（{@code cacheHitRate}）、
     * 生成后有多少用户给了反馈（{@code generatedToFeedback}）。
     * days 越界由 {@code AiFunnelMeter.summary} 内部收敛到 1~30。
     */
    @GetMapping("/metrics/funnel")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult funnel(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        Map<String, Object> data = new HashMap<>();
        data.put("funnel", funnelMeter.summary(days));
        return ResponseResult.okResult(data);
    }

    @GetMapping("/metrics")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult metrics() {
        Map<String, Object> data = new HashMap<>();
        data.put("counters", aiMetricsCollector.snapshot());
        if (circuitBreaker != null) {
            data.put("circuit", circuitBreaker.snapshot());
        }
        try {
            long up = aiFeedbackMapper.selectCount(new LambdaQueryWrapper<AiFeedback>()
                .eq(AiFeedback::getFeedback, AiFeedback.FEEDBACK_UP));
            long down = aiFeedbackMapper.selectCount(new LambdaQueryWrapper<AiFeedback>()
                .eq(AiFeedback::getFeedback, AiFeedback.FEEDBACK_DOWN));
            Map<String, Long> fb = new HashMap<>();
            fb.put("up", up);
            fb.put("down", down);
            fb.put("total", up + down);
            data.put("feedback", fb);
        } catch (Exception e) {
            data.put("feedback", "unavailable");
        }
        return ResponseResult.okResult(data);
    }
}
