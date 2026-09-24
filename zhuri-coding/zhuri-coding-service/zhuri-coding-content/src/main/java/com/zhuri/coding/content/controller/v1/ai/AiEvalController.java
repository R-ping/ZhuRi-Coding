package com.zhuri.coding.content.controller.v1.ai;

import com.zhuri.coding.content.service.ai.AiEvalService;
import com.zhuri.coding.content.service.ai.PrecheckEvalService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 检索评测端点（RAG eval，Recall@k）
 *
 * <p>黄金问答对 → query 向量化 → pgvector 召回 → 命中率统计。
 * 用于检索质量改动的回归基线（改 rewrite/rerank/阈值前后各跑一次对比）。
 * 登录 + 低频限制（每次评测 N 次向量化调用，避免被刷成本）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/eval")
public class AiEvalController {

    @Autowired
    private AiEvalService aiEvalService;

    @Autowired
    private PrecheckEvalService precheckEvalService;

    @PostMapping("/run")
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.USER,
        count = 2, interval = 1, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.IP,
        count = 5, interval = 1, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult run() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        log.info("AI 检索评测触发, userId={}", user.getId());
        Map<String, Object> report = aiEvalService.runEval();
        return ResponseResult.okResult(report);
    }

    /**
     * 答案级评测（生成侧）：引用精确率 / 引用召回率 / 未溯源率。
     *
     * <p>成本远高于检索评测（每题一次生成），故限流更严、且 limit 默认 5（服务端硬顶 12）。
     * 建议在 `ai.faithfulness.mode=sync` 下跑，避免 ask() 内部再异步校验一次。
     */
    @PostMapping("/answer")
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.USER,
        count = 1, interval = 2, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.IP,
        count = 2, interval = 5, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult runAnswer(@RequestParam(required = false) Integer limit) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        int n = limit == null || limit <= 0 ? 5 : limit;
        log.info("AI 答案级评测触发, userId={}, limit={}", user.getId(), n);
        return ResponseResult.okResult(aiEvalService.runAnswerEval(n));
    }

    /**
     * 评测门禁：检索 + 答案级组合评测，按 ai.eval.gate.* 阈值判定 pass/fail（含逐项 checks）。
     *
     * <p>成本最高（全量向量化 + 最多 12 次生成），限流最严；建议改 prompt / 调召回阈值 / 换模型后跑，
     * 作为 RAG 质量回归门禁（gate.pass=false 即回退）。
     */
    @PostMapping("/gate")
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.USER,
        count = 1, interval = 5, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.IP,
        count = 2, interval = 10, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult runGate() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        log.info("AI 评测门禁触发, userId={}", user.getId());
        return ResponseResult.okResult(aiEvalService.runGate());
    }

    /**
     * 发布预检「阶段级评测」（离线/管理验证用，不面向普通用户）。
     *
     * <p>读取默认 golden 集 {@code eval-precheck.json}，对每条真实跑显式编排 {@code PrecheckWorkflow}，
     * 返回四阶段维度（安全/标签/质量/摘要）命中率与整体准确率，作为"显式编排版 vs 旧直答版"质量回归基线。
     *
     * <p>⚠️ 成本：每 case 一次真实预检（含 LLM 调用），限流最严；仅需在改预检 prompt / 编排顺序后跑。
     */
    @PostMapping("/precheck")
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.USER,
        count = 1, interval = 10, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.IP,
        count = 1, interval = 20, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult runPrecheck() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        log.info("AI 阶段级评测(Precheck)触发, userId={}", user.getId());
        java.util.List<PrecheckEvalService.EvalCase> cases = precheckEvalService.loadDefaultCases();
        return ResponseResult.okResult(precheckEvalService.evaluatePrecheck(cases));
    }
}
