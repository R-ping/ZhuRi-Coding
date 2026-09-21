package com.zhuri.coding.content.service.ai.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * JSON 格式化/结构化输出 Skill（id=json_output）。
 *
 * <p>两种能力：
 * <ul>
 *   <li>{@link #parseOrNull(String)}：容错 JSON 提取（截取首个 {@code {} } 到末个 {@code }}）
 *       → {@link JsonNode}，沿用原 parseJson 语义（模型常在首尾附加解释文本）；</li>
 *   <li>{@link #parsePrecheckBeanOrNull(String)}：Spring AI 结构化输出（{@link BeanOutputConverter}）
 *       把发布预检 FINAL JSON 直接 Bean 化为 {@link AiPrecheckVo}，降低多智能体解析失败率；
 *       转换失败/异常返回 null，由调用方回落 {@link #parseOrNull} 等兜底路径。</li>
 * </ul>
 *
 * <p>fail-open：任何异常不抛给调用方。
 */
@Slf4j
@Component
public class JsonOutputSkill implements AiSkill {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BeanOutputConverter<PrecheckBean> precheckConverter =
        new BeanOutputConverter<>(PrecheckBean.class);

    @Override
    public String id() {
        return "json_output";
    }

    @Override
    public String name() {
        return "JSON 格式化输出";
    }

    @Override
    public String description() {
        return "容错 JSON 提取 + 发布预检 FINAL JSON 结构化 Bean 化（降低多智能体解析失败率）";
    }

    @Override
    public Object execute(SkillContext ctx) {
        // 通用入口：仅做容错提取返回 JsonNode
        return parseOrNull(ctx == null ? null : ctx.rawText());
    }

    /** 容错提取：截取首个 { 到末个 } 后 readTree；失败返回 null */
    public JsonNode parseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int b = s.indexOf('{');
        int e = s.lastIndexOf('}');
        if (b < 0 || e <= b) {
            return null;
        }
        try {
            return objectMapper.readTree(s.substring(b, e + 1));
        } catch (Exception ex) {
            log.warn("[AiSkill-{}] JSON 容错解析失败: {}", id(), truncate(raw, 120));
            return null;
        }
    }

    /**
     * Bean 化发布预检 FINAL JSON（Spring AI Structured Output）。
     *
     * <p>成功返回仅含 LLM 字段的 {@link AiPrecheckVo}（image/latency 等由调用方后续填充）；
     * 转换失败/字段缺失等异常统一返回 null（调用方回落 parseOrNull + 旧映射）。
     */
    public AiPrecheckVo parsePrecheckBeanOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            PrecheckBean bean = precheckConverter.convert(raw);
            if (bean == null) {
                return null;
            }
            return toVo(bean);
        } catch (Exception e) {
            log.debug("[AiSkill-{}] 预检 Bean 化失败，回落容错解析: {}", id(),
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return null;
        }
    }

    private AiPrecheckVo toVo(PrecheckBean b) {
        AiPrecheckVo vo = new AiPrecheckVo();
        vo.setViolation(b.violation() != null ? b.violation() : Boolean.FALSE);
        vo.setViolationType(trimToNull(b.violationType()));
        vo.setViolationReason(trimToNull(b.violationReason()));
        // 模型可能返回越界值（>100 / <0）：钳制到 0–100，避免前端评分/进度条失真
        vo.setQualityScore(normalizeScore(b.qualityScore()));
        vo.setTech(b.tech() == null ? Boolean.TRUE : b.tech());
        vo.setSuggestions(b.suggestions() == null ? new ArrayList<>() : b.suggestions());
        vo.setTags(b.tags() == null ? new ArrayList<>() : b.tags());
        vo.setSummary(trimToNull(b.summary()));
        if (b.similarArticleId() != null && b.similarArticleId() > 0) {
            vo.setSimilarArticleId(b.similarArticleId());
            vo.setSimilarTitle(trimToNull(b.similarTitle()));
            // 相似度越界（>1 / <0）会让前端"相似度 x%"失真，统一钳制到 0–1
            vo.setSimilarity(normalizeSimilarity(b.similarity()));
        }
        return vo;
    }

    /** FINAL JSON 的 Bean 形态（snake_case 对齐模型输出；与 AiPrecheckVo 前端字段解耦） */
    public record PrecheckBean(
        @JsonProperty("is_violation") Boolean violation,
        @JsonProperty("violation_type") String violationType,
        @JsonProperty("violation_reason") String violationReason,
        @JsonProperty("quality_score") Integer qualityScore,
        @JsonProperty("is_tech") Boolean tech,
        List<String> suggestions,
        List<String> tags,
        String summary,
        @JsonProperty("similar_article_id") Long similarArticleId,
        @JsonProperty("similar_title") String similarTitle,
        Double similarity) {
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }

    /** 质量分归一化到 0–100（模型可能越界；缺省 0） */
    private static int normalizeScore(Integer score) {
        return score == null ? 0 : Math.max(0, Math.min(100, score));
    }

    /** 相似度归一化到 0–1（模型可能返回 >1 或负数；null 原样保留，由调用方兜底） */
    private static Double normalizeSimilarity(Double similarity) {
        return similarity == null ? null : Math.max(0d, Math.min(1d, similarity));
    }
}
