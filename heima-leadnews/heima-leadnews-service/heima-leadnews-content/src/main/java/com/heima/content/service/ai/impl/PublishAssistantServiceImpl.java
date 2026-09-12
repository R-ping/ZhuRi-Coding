package com.heima.content.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.common.bailian.DashScopeClient;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.ai.PublishAssistantService;
import com.heima.content.service.ai.agent.AgentResult;
import com.heima.content.service.ai.agent.AgentRunner;
import com.heima.content.service.ai.spring.AiSafetyTools;
import com.heima.content.service.ai.spring.AiSimilarityTools;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.model.article.dtos.AiPrecheckVo;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AI 发布助手实现（v2：Agent 工具调用版）
 *
 * <p>主路径：{@link AgentRunner} ReAct 循环——模型自主决定先调 content_safety_check /
 * search_similar_article 等工具，再综合产出质量分/建议/标签/摘要（FINAL JSON）。
 * 兜底路径：循环异常/超步/解析失败时降级为一次性结构化调用（保证接口始终可用）。
 */
@Slf4j
@Service
public class PublishAssistantServiceImpl implements PublishAssistantService {

    private static final int MAX_TITLE = 120;
    private static final int MAX_CONTENT = 20000;
    /** 提供给模型的正文上限（控制 token 成本） */
    private static final int LLM_CONTENT_CHARS = 8000;
    /** 相似度预警阈值（低于该值不算"疑似重复"） */
    private static final double SIMILAR_ALERT = 0.72;
    private static final int AGENT_MAX_STEPS = 4;

    private static final String AGENT_SYSTEM_PROMPT =
        "你是内容社区《逐日 Coding》的资深编辑助手 Agent，可以调用工具获取事实，再为作者输出发布前预检报告。\n\n" +
        "可用工具：\n" +
        "- content_safety_check：参数 {\"title\":\"标题\",\"content\":\"正文\"}，返回违规检测结果。\n" +
        "- search_similar_article：参数 {\"content\":\"正文\"}，检索最相似的已发布文章。\n\n" +
        "执行方式：你拥有 content_safety_check / search_similar_article 两个工具，需要时请直接调用（框架会自动执行并把结果给你）；\n" +
        "拿到所有工具结果后，输出最终报告（仅输出这一行）：\n" +
        "FINAL: {JSON}\n\n" +
        "FINAL 的 JSON 结构（严格遵守）：\n" +
        "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\",\"quality_score\":0,\"is_tech\":true," +
        "\"suggestions\":[\"建议1\"],\"tags\":[\"标签1\"],\"summary\":\"120字内摘要\"," +
        "\"similar_article_id\":null,\"similar_title\":\"\",\"similarity\":null}\n\n" +
        "规则：\n" +
        "1. 先调用 content_safety_check；再调用 search_similar_article 判断是否与他人重复。\n" +
        "2. is_violation 以工具结果为准；客观技术讨论（安全研究/科普/新闻）不算违规。\n" +
        "3. quality_score 从原创性、逻辑性、表达清晰度综合评分 0-100；suggestions 给 2~4 条可执行建议（禁止空话）。\n" +
        "4. tags 给 3~5 个社区常用标签（如“MySQL”“性能优化”）；summary 为 120 字内一句话摘要。\n" +
        "5. similar_article_id/similar_title/similarity 仅在相似文章相似度 ≥ 0.7 时填写，否则为 null/空。\n" +
        "6. 禁止编造工具结果，未调用工具不得声称已查重。";

    private static final String DIRECT_SYSTEM_PROMPT =
        "你是内容社区《逐日 Coding》的资深编辑助手。请审阅作者文章，仅输出一个 JSON 对象（不要任何额外文字、不要 markdown 代码块），字段：\n" +
        "{\n" +
        "  \"is_violation\": false,\n" +
        "  \"violation_type\": \"\",\n" +
        "  \"violation_reason\": \"\",\n" +
        "  \"quality_score\": 0,\n" +
        "  \"is_tech\": true,\n" +
        "  \"suggestions\": [\"建议1\", \"建议2\"],\n" +
        "  \"tags\": [\"标签1\", \"标签2\", \"标签3\"],\n" +
        "  \"summary\": \"不超过120字的一句话摘要\"\n" +
        "}\n" +
        "要求：\n" +
        "1. is_violation：色情/暴力/政治敏感/违法/辱骂造谣等才为 true；讨论安全漏洞、渗透测试、行业动态等客观技术内容不算违规。\n" +
        "2. quality_score：从原创性、逻辑性、表达清晰度综合评分 0-100。\n" +
        "3. suggestions：2~4 条可执行的改进建议（针对性，禁止空话）。\n" +
        "4. tags：3~5 个技术社区常用标签，粒度适中（如“MySQL”、“性能优化”）。\n" +
        "5. summary：准确概括全文要点的一句话（120 字内）。";

    private static final String USER_PROMPT = "标题：%s\n\n正文：%s";

    @Autowired
    private DashScopeClient dashScopeClient; // 仅多模态封面审核(callVision)使用

    @Autowired
    private org.springframework.ai.chat.model.ChatModel chatModel;

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private AiSafetyTools aiSafetyTools;

    @Autowired
    private AiSimilarityTools aiSimilarityTools;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AiPrecheckVo precheck(String title, String content, Long articleId, String coverImageUrl) {
        long start = System.currentTimeMillis();
        String t = title == null ? "" : title.trim();
        String c = content == null ? "" : content.trim();
        if (t.isEmpty() || c.isEmpty()) {
            return null;
        }
        if (t.length() > MAX_TITLE) {
            t = t.substring(0, MAX_TITLE);
        }
        if (c.length() > MAX_CONTENT) {
            c = c.substring(0, MAX_CONTENT);
        }

        AiPrecheckVo vo = null;
        String user = String.format(USER_PROMPT, t, truncate(c, LLM_CONTENT_CHARS));
        try {
            // 主路径：Agent 工具调用（模型自主决定查重/安全再作答）
            List<Object> tools = java.util.Arrays.asList(aiSafetyTools, aiSimilarityTools);
            AgentResult result = agentRunner.run(AGENT_SYSTEM_PROMPT, user, tools, AGENT_MAX_STEPS);
            if (result.isCompleted() && result.getFinalAnswer() != null) {
                JsonNode root = parseJson(result.getFinalAnswer());
                if (root != null) {
                    vo = fromJson(root);
                    log.info("[AiPrecheck-Agent] steps={}, FINAL 解析成功", result.getSteps());
                }
            } else {
                log.warn("[AiPrecheck-Agent] 未收敛(completed={}, steps={})，降级直答", result.isCompleted(), result.getSteps());
            }
        } catch (Exception e) {
            log.warn("[AiPrecheck-Agent] 循环异常，降级直答", e);
        }

        if (vo == null) {
            // 兜底：一次性结构化调用
            try {
                String raw = org.springframework.ai.chat.client.ChatClient.builder(chatModel).build()
                    .prompt().system(DIRECT_SYSTEM_PROMPT).user(user).call().content();
                JsonNode root = parseJson(raw);
                if (root != null) {
                    vo = fromJson(root);
                }
            } catch (Exception e) {
                log.error("[AiPrecheck] 兜底调用失败", e);
                return null;
            }
        }

        // 兜底补相似预警（无论 Agent 是否已查，双保险且排除自身）
        fillSimilarity(vo, c, articleId);

        // 封面图多模态审核（可选：提供 coverImageUrl 时执行）
        if (coverImageUrl != null && !coverImageUrl.isBlank()) {
            checkCoverImage(vo, t, coverImageUrl);
        }

        vo.setLatencyMs(System.currentTimeMillis() - start);
        log.info("[AiPrecheck] title={}, quality={}, violation={}, tags={}, viaAgent={}, latency={}ms",
            truncate(t, 30), vo.getQualityScore(), vo.getViolation(),
            vo.getTags() == null ? 0 : vo.getTags().size(), vo.getLatencyMs());
        return vo;
    }

    /** 将 FINAL JSON 映射为 VO（容错：字段缺失不报错） */
    private AiPrecheckVo fromJson(JsonNode root) {
        AiPrecheckVo vo = new AiPrecheckVo();
        vo.setViolation(root.path("is_violation").asBoolean(false));
        vo.setViolationType(trimToNull(root.path("violation_type").asText()));
        vo.setViolationReason(trimToNull(root.path("violation_reason").asText()));
        vo.setQualityScore(root.path("quality_score").isInt() ? root.path("quality_score").asInt() : null);
        vo.setTech(root.path("is_tech").asBoolean(true));
        vo.setSuggestions(toStringList(root.get("suggestions")));
        vo.setTags(toStringList(root.get("tags")));
        vo.setSummary(trimToNull(root.path("summary").asText()));
        if (root.hasNonNull("similar_article_id") && root.path("similar_article_id").asLong() > 0) {
            vo.setSimilarArticleId(root.path("similar_article_id").asLong());
            vo.setSimilarTitle(trimToNull(root.path("similar_title").asText()));
            vo.setSimilarity(root.path("similarity").isNumber() ? root.path("similarity").asDouble() : null);
        }
        return vo;
    }

    /** 独立相似度兜底：向量 TopK → 过滤 PUBLISHED/排除自身 → ≥ 阈值填充 */
    private void fillSimilarity(AiPrecheckVo vo, String content, Long articleId) {
        if (vo == null) {
            return;
        }
        try {
            String sample = content.length() > 2000 ? content.substring(0, 2000) : content;
            double[] emb = embeddingService.generateEmbedding(sample);
            if (emb == null) {
                return;
            }
            List<Object[]> hits = embeddingService.findSimilarArticles(emb, 5, 0);
            if (hits == null || hits.isEmpty()) {
                return;
            }
            List<Long> ids = new ArrayList<>();
            for (Object[] h : hits) {
                ids.add((Long) h[0]);
            }
            for (ApArticle a : apArticleMapper.selectBatchIds(ids)) {
                if (a.getStatus() == null || a.getStatus() != Status.PUBLISHED.getCode()) {
                    continue;
                }
                if (articleId != null && articleId.equals(a.getId())) {
                    continue;
                }
                double sim = 0;
                for (Object[] h : hits) {
                    if (a.getId().equals(h[0]) && h.length > 1 && h[1] != null) {
                        sim = (Double) h[1];
                    }
                }
                if (sim >= SIMILAR_ALERT) {
                    vo.setSimilarArticleId(a.getId());
                    vo.setSimilarTitle(a.getTitle());
                    vo.setSimilarity(Math.round(sim * 10000) / 10000.0);
                }
                break;
            }
        } catch (Exception e) {
            log.warn("[AiPrecheck] 相似度兜底失败", e);
        }
    }

    private String sampleContent(AiPrecheckVo vo) {
        // 预检内容不可得时用空（Agent 主路径已提供 content 给工具；此处仅兜底场景退化）
        return "";
    }

    /** 封面图多模态审核：调用 vision 模型判断违规与主题契合度 */
    private void checkCoverImage(AiPrecheckVo vo, String title, String imageUrl) {
        try {
            String prompt = "你是内容平台图片合规审核助手。分析给定图片是否违规，仅输出 JSON（不要多余文字/markdown）：\n"
                + "{\"is_violation\":false,\"violation_type\":\"\",\"reason\":\"\"}\n"
                + "要求：is_violation 仅在图片含色情低俗/暴力血腥/违法广告/政治敏感等违规内容时为 true；"
                + "普通生活/游戏/风景/表情包/截图等均不算违规（图片风格与主题是否契合不做评判）。";
            String raw = dashScopeClient.callVision(prompt, imageUrl, "文章标题：" + title);
            JsonNode root = parseJson(raw);
            if (root == null) {
                log.warn("[AiPrecheck] 封面图审核输出解析失败");
                return;
            }
            vo.setImageUrl(imageUrl);
            vo.setImageViolation(root.path("is_violation").asBoolean(false));
            vo.setImageReason(trimToNull(root.path("reason").asText()));
            log.info("[AiPrecheck] 封面图合规审核完成 violation={}", vo.getImageViolation());
        } catch (Exception e) {
            log.warn("[AiPrecheck] 封面图审核异常", e);
        }
    }

    private JsonNode parseJson(String raw) {
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
            log.warn("[AiPrecheck] JSON 解析失败: {}", truncate(raw, 120));
            return null;
        }
    }

    private List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            String v = item.asText("");
            if (!v.isBlank() && list.size() < 6) {
                list.add(v.trim());
            }
        }
        return list;
    }

    private String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}
