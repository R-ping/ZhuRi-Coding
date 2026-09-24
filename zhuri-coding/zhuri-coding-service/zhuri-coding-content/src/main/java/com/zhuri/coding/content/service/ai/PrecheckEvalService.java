package com.zhuri.coding.content.service.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发布预检「阶段级评测」（Precheck eval）
 *
 * <p>与 RAG 问答的 Recall 评测（{@link AiEvalService}）对等，但评测对象是发布预检的<b>显式编排版</b>
 * {@code PrecheckWorkflow}：对每条 golden（标题 + 正文 + 期望）真实跑一次工作流（SAFETY/QUALITY/SEO/
 * CRITIC/FORMAT 阶段），按四个阶段产出维度（安全 / 标签 / 质量 / 摘要）分别统计命中率并聚合成整体准确率，
 * 作为"显式编排版 vs 旧直答版"质量在 CI 可回归对比的金标准基线（增量4）。
 *
 * <p>golden 数据来自 classpath {@code ai-eval/eval-precheck.json}（每条含 title / content / expected）。
 * 调用方只能经 {@link AiEvalController#runPrecheck()} 在<b>登录 + 限频</b>下做离线/管理验证，不面向普通用户。
 * 评测会真实消耗 LLM 调用（每 case 一次预检），生产勿滥用。
 */
public interface PrecheckEvalService {

    /**
     * 对给定用例集走显式编排工作流逐一评测，汇总四阶段维度命中率与整体准确率。
     *
     * @param cases 待评测用例（title + content + expected）；为空则返回空报告
     * @return 阶段级评测报告（含 eachCase 粒度明细与占位 baseline 对比字段）
     */
    PrecheckEvalReport evaluatePrecheck(List<EvalCase> cases);

    /**
     * 加载默认 golden 集（classpath {@code ai-eval/eval-precheck.json}）。读取失败返回空列表，
     * 由调用方自行判定（评测集缺失不静默通过）。
     *
     * @return golden 用例列表（可能为空）
     */
    List<EvalCase> loadDefaultCases();

    /**
     * 单条评测用例（对应 eval-precheck.json 的一个元素）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class EvalCase {
        /** 待预检文章标题 */
        private String title = "";
        /** 待预检文章正文（简短） */
        private String content = "";
        /** 期望结论（golden label） */
        private Expected expected;
    }

    /**
     * 期望结论：四个阶段维度的 golden label。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class Expected {
        /** 期望是否违规（安全维度） */
        @JsonProperty("is_violation")
        private boolean isViolation;
        /** 期望标签子集：任意一个命中即标签维度通过（空集视为不约束 → 通过） */
        @JsonProperty("tags_each_in")
        private List<String> tagsEachIn = new ArrayList<>();
        /** 期望质量分下限（质量维度：VO.qualityScore >= floor 即通过；null 视为不约束） */
        @JsonProperty("quality_floor")
        private Integer qualityFloor;
        /** 期望摘要应包含的关键词（摘要维度；null/空视为不约束 → 通过） */
        @JsonProperty("summary_keyword_in")
        private String summaryKeywordIn;
    }

    /**
     * 单条用例的阶段维度判定结果（供 report.perCase 与 GI 聚合复用）。
     */
    @Data
    @NoArgsConstructor
    public static class PerCase {
        /** 用例标题（便于报告可读） */
        private String title = "";
        /** 是否降级（VO 为 null，各维度一律未命中） */
        private boolean degraded;
        /** 安全维度命中 */
        private boolean safety;
        /** 标签维度命中 */
        private boolean tag;
        /** 质量维度命中 */
        private boolean quality;
        /** 摘要维度命中 */
        private boolean summary;
        /** 四个维度全部命中才算该 case 通过 */
        private boolean pass;
    }

    /**
     * 阶段级评测报告（对外返回：「显式编排版」各维度命中率 + 整体准确率 + 占位 baseline 对比字段）。
     */
    @Data
    @NoArgsConstructor
    public static class PrecheckEvalReport {
        /** 参评用例总数 */
        private int totalCases;
        /** 四维度全中（通过）的用例数 */
        private int passCases;
        /** 降级（VO 为 null）用例数 */
        private int degradedCases;
        /** 整体准确率 = passCases / totalCases（0-1） */
        private double overallAccuracy;
        /** 安全维度命中率（0-1） */
        private double safetyHitRate;
        /** 标签维度命中率（0-1） */
        private double tagHitRate;
        /** 质量维度命中率（0-1） */
        private double qualityHitRate;
        /** 摘要维度命中率（0-1） */
        private double summaryHitRate;
        /** 旧直答版 baseline 占位（待接入直答路径后填真实值） */
        private Baseline baseline = new Baseline();
        /** 每条用例的维度判定明细 */
        private List<PerCase> perCase = new ArrayList<>();
    }

    /**
     * 旧直答版 baseline 占位：增量4 仅完成"显式编排版"回测，直答版对照待接入。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Baseline {
        /** 是否已有真实直答版 baseline（本增量固定 false） */
        private boolean available = false;
        /** 说明文案 */
        private String note = "待接入直答路径后填真实 baseline";
    }
}