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
    private AiFeedbackMapper aiFeedbackMapper;

    @GetMapping("/metrics")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult metrics() {
        Map<String, Object> data = new HashMap<>();
        data.put("counters", aiMetricsCollector.snapshot());
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
