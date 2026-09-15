package com.heima.content.controller.v1.ai;

import com.heima.content.service.ai.AiFeedbackService;
import com.heima.model.ai.pojos.AiFeedback;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 反馈端点（👍/👎 反馈闭环）：让问答/摘要/预检的迭代有数据依据
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class AiFeedbackController {

    @Autowired
    private AiFeedbackService aiFeedbackService;

    @PostMapping("/feedback")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.USER,
        count = 30, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult feedback(@RequestBody Map<String, Object> body) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (body == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        String feature = body.get("feature") == null ? null : String.valueOf(body.get("feature"));
        String sceneId = body.get("sceneId") == null ? "" : String.valueOf(body.get("sceneId"));
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question"));
        String answer = body.get("answer") == null ? "" : String.valueOf(body.get("answer"));
        Integer feedback = body.get("feedback") instanceof Number
            ? ((Number) body.get("feedback")).intValue() : null;
        return aiFeedbackService.record(user.getId(), feature, sceneId, question, answer, feedback);
    }

    /**
     * 导出坏例（👎 样本，含可直接并入评测集的候选条目）。
     *
     * <p>P1-2 反馈回灌：👎 数据如果不能被复盘、不能变成评测样本，就只是死数据。
     * 流程：导出 → 人工补「期望召回文章」→ 并入 {@code ai-eval/eval-questions.json} → 下次评测覆盖。
     *
     * <p>要求登录（question/answer 含用户输入内容），IP 限频。
     */
    @GetMapping("/feedback/badcases")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult badCases(
        @org.springframework.web.bind.annotation.RequestParam(required = false) String feature,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("badcases", aiFeedbackService.badCases(feature, limit));
        data.put("evalCandidates", aiFeedbackService.exportEvalCandidates(feature, limit));
        data.put("usage", "把 evalCandidates 里的条目补上 goldenArticleIds 后并入 ai-eval/eval-questions.json");
        return ResponseResult.okResult(data);
    }

    /**
     * 反馈质量统计：按 feature 的 👍/👎 与差评率；超阈值（默认 20%，样本 ≥5）的 feature 会被列入
     * {@code alerted} 并打指标 —— 让"哪个 AI 功能变差了"有主动信号，而不是靠用户投诉发现。
     */
    @GetMapping("/feedback/stats")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.IP,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult feedbackStats(
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        return ResponseResult.okResult(aiFeedbackService.statsByFeature(days));
    }
}
