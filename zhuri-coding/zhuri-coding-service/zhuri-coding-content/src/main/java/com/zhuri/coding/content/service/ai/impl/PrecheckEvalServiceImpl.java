package com.zhuri.coding.content.service.ai.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.service.ai.PrecheckEvalService;
import com.zhuri.coding.content.service.ai.agent.workflow.PrecheckWorkflow;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 发布预检「阶段级评测」实现。
 *
 * <p>每 case 走真实 {@link PrecheckWorkflow#run(title, content, null)}（显式编排，不经过 controller 的
 * 配额/登录/封面），解析最终 VO 后按四个阶段维度与 expected 比对。维度命中判定逻辑收敛为纯静态方法
 * {@link #assess(AiPrecheckVo, Expected)}（不依赖 LLM/Spring），便于离线单测与 Gate 回测复用同一套口径。
 *
 * <p>报告字段（见 {@link PrecheckEvalReport}）：各维度命中率 0-1、整体准确率（四维全中才算 case 通过）、
 * 以及占位 baseline（旧直答版对比，增量4 尚未接入固定 available=false）。
 */
@Slf4j
@Service
public class PrecheckEvalServiceImpl implements PrecheckEvalService {

    private static final String EVAL_FILE = "ai-eval/eval-precheck.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PrecheckWorkflow workflow;

    public PrecheckEvalServiceImpl(PrecheckWorkflow workflow) {
        this.workflow = workflow;
    }

    @Override
    public PrecheckEvalReport evaluatePrecheck(List<EvalCase> cases) {
        long start = System.currentTimeMillis();
        PrecheckEvalReport report = new PrecheckEvalReport();
        List<PerCase> perCase = new ArrayList<>();
        if (cases == null) {
            cases = new ArrayList<>();
        }
        report.setTotalCases(cases.size());

        int pass = 0;
        int degraded = 0;
        int safetyHit = 0, tagHit = 0, qualityHit = 0, summaryHit = 0;

        for (EvalCase c : cases) {
            // 走显式编排工作流（贯穿 SAFETY/QUALITY/SEO/CRITIC/FORMAT），最终 VO 即评测对象
            AiPrecheckVo vo = safeRun(c);
            PerCase pc = assess(vo, c == null ? null : c.getExpected());
            pc.setTitle(c == null || c.getTitle() == null ? "" : c.getTitle());
            perCase.add(pc);

            if (pc.isDegraded()) {
                degraded++;
            }
            if (pc.isPass()) {
                pass++;
            }
            if (pc.isSafety()) {
                safetyHit++;
            }
            if (pc.isTag()) {
                tagHit++;
            }
            if (pc.isQuality()) {
                qualityHit++;
            }
            if (pc.isSummary()) {
                summaryHit++;
            }
        }

        int total = cases.size();
        report.setPassCases(pass);
        report.setDegradedCases(degraded);
        report.setOverallAccuracy(total == 0 ? 0d : round3((double) pass / total));
        report.setSafetyHitRate(total == 0 ? 0d : round3((double) safetyHit / total));
        report.setTagHitRate(total == 0 ? 0d : round3((double) tagHit / total));
        report.setQualityHitRate(total == 0 ? 0d : round3((double) qualityHit / total));
        report.setSummaryHitRate(total == 0 ? 0d : round3((double) summaryHit / total));
        report.setPerCase(perCase);
        log.info("[PrecheckEval] 阶段级评测完成, cases={}, pass={}, degraded={}, accuracy={}, costMs={}",
            total, pass, degraded, report.getOverallAccuracy(), System.currentTimeMillis() - start);
        return report;
    }

    /** 容错执行工作流：异常视为整体降级（对应该 case 各维度未命中并单独标注） */
    private AiPrecheckVo safeRun(EvalCase c) {
        if (c == null) {
            return null;
        }
        try {
            return workflow.run(c.getTitle() == null ? "" : c.getTitle(),
                c.getContent() == null ? "" : c.getContent(), null);
        } catch (Exception e) {
            log.warn("[PrecheckEval] 工作流执行异常，按降级处理: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<EvalCase> loadDefaultCases() {
        try {
            ClassPathResource res = new ClassPathResource(EVAL_FILE);
            if (!res.exists()) {
                log.warn("[PrecheckEval] 评测集不存在: {}", EVAL_FILE);
                return new ArrayList<>();
            }
            try (InputStream in = res.getInputStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return objectMapper.readValue(json, new TypeReference<List<EvalCase>>() {
                });
            }
        } catch (Exception e) {
            log.error("[PrecheckEval] 评测集加载异常: {}", EVAL_FILE, e);
            return new ArrayList<>();
        }
    }

    /**
     * 单条用例的四阶段维度命中判定（<b>纯函数，供离线单测与 Gate 回测复用同一口径</b>）。
     *
     * <ul>
     *   <li>安全：VO.isViolation == expected.isViolation 记为命中；</li>
     *   <li>标签：VO.tags 与 expected.tagsEachIn 至少 1 个重叠（前缀约束未约束 → 视为通过）；</li>
     *   <li>质量：VO.qualityScore >= expected.qualityFloor（floor 未约束 → 视为通过）；</li>
     *   <li>摘要：VO.summary contains expected.summaryKeywordIn（关键词未约束 → 视为通过）；</li>
     * </ul>
     * VO 为 null（降级）时各维度一律未命中、pass=false、degraded=true。
     *
     * @param vo    预检工作流产物（可能为 null 表示降级）
     * @param exp   期望结论（可能为 null 按降级处理）
     * @return 该 case 的维度判定结果
     */
    public static PerCase assess(AiPrecheckVo vo, Expected exp) {
        PerCase pc = new PerCase();
        if (vo == null || exp == null) {
            // 降级：若 VO 为 null，所有维度记为未命中并单独标注 degraded
            pc.setDegraded(true);
            pc.setSafety(false);
            pc.setTag(false);
            pc.setQuality(false);
            pc.setSummary(false);
            pc.setPass(false);
            return pc;
        }
        // ① 安全维度
        pc.setSafety(Boolean.TRUE.equals(vo.getViolation()) == exp.isViolation());
        // ② 标签维度：VO 标签与期望标签至少 1 个重叠（期望未约束 → 默认通过）
        List<String> voTags = vo.getTags() == null ? Collections.emptyList() : vo.getTags();
        List<String> expTags = exp.getTagsEachIn() == null ? Collections.emptyList() : exp.getTagsEachIn();
        boolean tag = expTags.isEmpty();
        if (!tag) {
            for (String e : expTags) {
                if (e == null) {
                    continue;
                }
                for (String v : voTags) {
                    if (v != null && v.trim().equalsIgnoreCase(e.trim())) {
                        tag = true;
                        break;
                    }
                }
                if (tag) {
                    break;
                }
            }
        }
        pc.setTag(tag);
        // ③ 质量维度：VO.qualityScore >= qualityFloor（期望未约束 → 默认通过）
        boolean quality = exp.getQualityFloor() == null
            || (vo.getQualityScore() != null && vo.getQualityScore() >= exp.getQualityFloor());
        pc.setQuality(quality);
        // ④ 摘要维度：VO.summary contains summaryKeywordIn（期望未约束 → 默认通过）
        String kw = exp.getSummaryKeywordIn();
        boolean summary = kw == null || kw.isBlank()
            || (vo.getSummary() != null && vo.getSummary().contains(kw));
        pc.setSummary(summary);
        pc.setPass(pc.isSafety() && pc.isTag() && pc.isQuality() && pc.isSummary());
        return pc;
    }

    /** 0-1 命中率保留三位小结，便于可读 */
    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}