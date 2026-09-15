package com.heima.content.controller.v1.ai;

import com.heima.content.service.ai.router.AiModelRouter;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型路由观测端点（运维/面试演示用）
 */
@RestController
@RequestMapping("/api/v1/ai/router")
public class AiModelRouterController {

    @Autowired
    private AiModelRouter aiModelRouter;

    @GetMapping("/config")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult config() {
        return ResponseResult.okResult(aiModelRouter.configSnapshot());
    }

    /**
     * 成本报表：token 用量 × 模型定价 → 按 feature/model 的金额。
     *
     * <p>回答两个决策问题：① 哪个 AI 功能最烧钱；② 该功能换便宜模型能省多少
     * （对比 {@code costPer1kTokens} 与目标模型单价即可估算）。
     */
    @GetMapping("/cost")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult cost(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        return ResponseResult.okResult(aiModelRouter.costReport(days));
    }
}
