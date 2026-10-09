package com.zhuri.coding.content.service.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhuri.coding.common.bailian.PromptSanitizer;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.AiPromptRegistry;
import com.zhuri.coding.model.coding.pojos.ApCodingDailyPool;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 每日一题单题评估（Coding 延展第一层 · 简答）。
 *
 * <p>与模拟面试报告里的「单主题评估」是同一件事：给模型一份<b>考点清单</b>，
 * 让它逐条判断讲到没讲到，再给结构与准确性两个等级；覆盖度由
 * {@link CodingEvaluation#coverageLevel} 服务端重算，不采信模型自评。</p>
 *
 * <p><b>为什么不让模型出题</b>：题目与考点来自 {@code ap_coding_daily_pool}（人工维护）。
 * 让模型既出题又判分，等于"自己出题自己批"，评分锚点不可信。</p>
 *
 * <p><b>降级</b>：模型不可用 / 输出无法解析时返回「未评估」占位
 * （{@code pending=true}，各等级为 null）。调用方照常落库 ——
 * 用户写下的答案是真实产出，不该因为一次评估失败就丢掉；用户可重试评估。</p>
 */
@Slf4j
@Service
public class CodingDailyEvaluator {

    /** prompt 注册表 key：每日一题单题评估 */
    static final String PROMPT_KEY = "coding_daily_eval";

    /** 作答文本长度上限（字符，防 prompt 成本失控） */
    public static final int ANSWER_MAX_LENGTH = 2_000;

    /** 未评估占位评语 */
    public static final String PENDING_COMMENT = "本题未生成评估结果，可稍后重试";

    /** 评估 system（代码兜底；注册表可按需灰度覆盖） */
    static final String FALLBACK_SYSTEM = String.join("\n",
        "你是资深技术面试官，正在评估候选人对一道简答题的作答。",
        "你必须只输出一个 JSON 对象，不能有 markdown 代码块或解释文字：",
        "{\"structure\":1,\"coverage\":{\"covered\":[\"已讲到的考点\"],\"missing\":[\"没讲到的考点\"]},"
            + "\"accuracy\":1,\"comment\":\"点评\"}",
        "评分纪律：",
        "1. 覆盖度先行：逐条对照用户消息中给出的【关键考点】，讲到了放进 covered，没讲到放进 missing；",
        "   不允许自创考点，也不允许漏掉给出的考点 —— 每个考点必须出现在且只出现在其中一个清单里；",
        "2. structure（回答结构）与 accuracy（技术准确性）都给 1-5 整数等级：",
        "   1=几乎空白或错误，2=明显缺失，3=基本合格，4=良好，5=优秀；没有把握时从低；",
        "3. comment 必须给出依据：引用候选人原话的要点，或指出其遗漏的考点，不许空泛评价；",
        "4. 候选人没有作答时：covered 为空、missing 为全部考点，comment 说明「未作答」；",
        "5. 只评价技术内容与表达，不评价人格，不编造候选人没说过的内容。");

    @Autowired
    private AiLlmGateway aiLlmGateway;

    /** prompt 注册表（可空注入：单测/未装配时走代码兜底） */
    @Autowired(required = false)
    private AiPromptRegistry promptRegistry;

    @Autowired
    private PromptSanitizer promptSanitizer;

    /**
     * 评估结果。{@code pending=true} 表示未评估（各等级为 null，不参与任何均值计算）。
     */
    public static final class Result {
        public boolean pending;
        public Integer structure;
        public Integer coverageScore;
        public Integer accuracy;
        public Integer level;
        public List<String> covered = new ArrayList<>();
        public List<String> missing = new ArrayList<>();
        public String comment;
    }

    /**
     * 评估一次作答。任何异常都降级为「未评估」，不抛给调用方 ——
     * 评估是增强，落库才是主链路。
     */
    public Result evaluate(ApCodingDailyPool question, String answerText) {
        List<String> keyPoints = CodingJson.parseStringList(question.getKeyPoints());
        try {
            String system = resolvePrompt();
            String userPrompt = buildUserPrompt(question, keyPoints, answerText);
            String raw = aiLlmGateway.generateOrNull(AiFeatures.DAILY_EVAL, system, userPrompt, null, null);
            Result parsed = parse(raw, keyPoints);
            if (parsed != null) {
                return parsed;
            }
            log.warn("[CodingDaily] 单题评估失败，按未评估落库: poolId={}, rawLen={}",
                question.getId(), raw == null ? 0 : raw.length());
        } catch (Exception e) {
            log.warn("[CodingDaily] 单题评估异常，按未评估落库: poolId={}, err={}",
                question.getId(), e.getMessage());
        }
        return pending();
    }

    private Result pending() {
        Result r = new Result();
        r.pending = true;
        r.comment = PENDING_COMMENT;
        return r;
    }

    /** 解析模型输出；失败返回 null（调用方转未评估）。逐位补全考点清单，不信任模型给全了 */
    private Result parse(String raw, List<String> keyPoints) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = CodingInterviewJson.extractJsonObject(raw);
        if (json == null) {
            return null;
        }
        JsonNode node;
        try {
            node = CodingInterviewJson.mapper().readTree(json);
        } catch (Exception e) {
            log.warn("[CodingDaily] 评估 JSON 解析失败: {}", e.getMessage());
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }

        Result r = new Result();
        r.structure = CodingEvaluation.clampLevel(node.path("structure").isNumber()
            ? node.path("structure").asInt() : null);
        r.accuracy = CodingEvaluation.clampLevel(node.path("accuracy").isNumber()
            ? node.path("accuracy").asInt() : null);

        // 考点清单以题库为准做对齐：模型自创的丢掉，漏报的归入 missing。
        // 这一步是简答题评估可信度的关键 —— 否则模型可以靠"少列考点"把覆盖度做高。
        JsonNode coverage = node.path("coverage");
        List<String> covered = readStringArray(coverage.path("covered"));
        List<String> missing = readStringArray(coverage.path("missing"));
        for (String kp : keyPoints) {
            boolean inCovered = covered.remove(kp);
            missing.remove(kp);
            if (inCovered) {
                r.covered.add(kp);
            } else {
                r.missing.add(kp);
            }
        }
        r.coverageScore = CodingEvaluation.coverageLevel(r.covered.size(), r.missing.size());
        r.level = CodingEvaluation.combinedLevel(r.structure, r.coverageScore, r.accuracy);

        String comment = node.path("comment").isTextual() ? node.path("comment").asText("") : "";
        r.comment = comment.isBlank() ? "" : comment.trim();
        return r;
    }

    private List<String> readStringArray(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                list.add(item.asText().trim());
            }
        }
        return list;
    }

    private String buildUserPrompt(ApCodingDailyPool question, List<String> keyPoints, String answerText) {
        StringBuilder sb = new StringBuilder();
        sb.append("【题目】").append(promptSanitizer.sanitize(question.getStem())).append('\n');
        sb.append("【关键考点（逐条核对，每条都要出现在 covered 或 missing 之一）】");
        sb.append(String.join("、", keyPoints)).append('\n');
        if (question.getReferenceAnswer() != null && !question.getReferenceAnswer().isBlank()) {
            sb.append("【参考答案要点（仅供你判断准确性参考，不要直接引用）】")
                .append(promptSanitizer.sanitize(question.getReferenceAnswer())).append('\n');
        }
        String answer = answerText == null ? "" : answerText.trim();
        if (answer.isEmpty()) {
            sb.append("【候选人作答】（未作答）\n");
        } else {
            sb.append("【候选人作答】\n")
                .append(promptSanitizer.sanitizeAndWrap("answer", answer)).append('\n');
        }
        sb.append("请输出这道题的评估结果（JSON 对象）。");
        return sb.toString();
    }

    /** prompt 解析：注册表（灰度）→ 代码兜底；注册表缺失/异常一律 fail-open */
    private String resolvePrompt() {
        if (promptRegistry == null) {
            return FALLBACK_SYSTEM;
        }
        try {
            AiPromptRegistry.ResolvedPrompt resolved = promptRegistry.resolve(PROMPT_KEY, FALLBACK_SYSTEM, null);
            return resolved == null || resolved.content == null || resolved.content.isBlank()
                ? FALLBACK_SYSTEM : resolved.content;
        } catch (Exception e) {
            log.warn("[CodingDaily] prompt 解析失败，走代码兜底: key={}, err={}", PROMPT_KEY, e.getMessage());
            return FALLBACK_SYSTEM;
        }
    }
}
