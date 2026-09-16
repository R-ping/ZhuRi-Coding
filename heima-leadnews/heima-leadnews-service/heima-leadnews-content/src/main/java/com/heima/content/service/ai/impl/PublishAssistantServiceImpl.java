package com.heima.content.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.common.bailian.DashScopeClient;
import com.heima.content.service.ai.PublishAssistantService;
import com.heima.content.service.ai.agent.AgentResult;
import com.heima.content.service.ai.agent.AgentRunner;
import com.heima.content.service.ai.agent.tools.SimilaritySearchTool;
import com.heima.content.service.ai.agent.workers.CriticExpertWorker;
import com.heima.content.service.ai.agent.workers.QualityExpertWorker;
import com.heima.content.service.ai.agent.workers.SafetyExpertWorker;
import com.heima.content.service.ai.agent.workers.SeoExpertWorker;
import com.heima.content.service.ai.spring.AiSimilarityTools;
import com.heima.content.service.ai.spring.PromptSafetyAdvisor;
import com.heima.content.service.ai.spring.SafetyGuardException;
import com.heima.model.article.dtos.AiPrecheckVo;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AI 发布助手实现（v3：多智能体编排版 — Orchestrator-Workers + Evaluator-Optimizer）
 *
 * <p>主路径：{@link AgentRunner} 主编（Supervisor）ReAct 循环——拆解任务后并行调度
 * 安全/质量/SEO 三位专家 Worker（{@link com.heima.content.service.ai.agent.workers}）与
 * 相似度查重工具，汇总草稿后可调用终审专家（Critic）复审修正，最终输出 FINAL JSON。
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
    private static final int AGENT_MAX_STEPS = 6;

    /**
     * 主编（Supervisor）系统提示词：多智能体编排（Orchestrator-Workers + Evaluator-Optimizer）。
     *
     * <p>主编持有 AgentRunner ReAct 循环，把文章拆解为安全/质量/SEO 三个专家 Worker 任务（可并行），
     * 汇总草稿后可选调终审 Critic（review-optimize）再输出 FINAL JSON。
     */
    private static final String AGENT_SYSTEM_PROMPT =
        "你是内容社区《逐日 Coding》的主编 Agent（多智能体编排）。作者提交文章，你负责拆解任务、调度专家团队协作评审，" +
        "再汇总输出一份发布前预检报告。\n\n" +
        "专家团队（工具，同一轮可并行调用多个）：\n" +
        "- expert_safety(title, content)：安全审查专家 → {\"is_violation\":true/false,\"violation_type\":\"\",\"violation_reason\":\"\"}\n" +
        "- expert_quality(title, content)：质量评审专家 → {\"quality_score\":0,\"is_tech\":true,\"suggestions\":[\"建议1\"]}\n" +
        "- expert_seo(title, content)：SEO/运营专家 → {\"tags\":[\"标签1\"],\"summary\":\"120字内摘要\"}\n" +
        "- expert_critic(draftJson)：总编终审，检查一致性/完整性并输出修正后的同结构 JSON\n" +
        "- search_similar_article(content)：检索社区最相似的已发布文章（articleId/title/similarity）\n\n" +
        "执行方式：你拥有以上全部工具，需要时直接调用（框架自动执行并回传结果）。\n" +
        "工作流：\n" +
        "1. 第一轮尽量在同一回复内并行调用 expert_safety、expert_quality、expert_seo，并调用 search_similar_article 查重；\n" +
        "2. 汇总各专家输出形成预检草稿；如发现矛盾或字段缺失，可调用 expert_critic 做一次终审修正；\n" +
        "3. 输出最终报告（仅输出这一行）：\n" +
        "FINAL: {JSON}\n\n" +
        "FINAL 的 JSON 结构（严格遵守，字段不能缺失）：\n" +
        "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\",\"quality_score\":0,\"is_tech\":true," +
        "\"suggestions\":[\"建议1\"],\"tags\":[\"标签1\"],\"summary\":\"120字内摘要\"," +
        "\"similar_article_id\":null,\"similar_title\":\"\",\"similarity\":null}\n\n" +
        "规则：\n" +
        "1. is_violation 以 expert_safety 裁定为准；客观技术讨论（安全研究/科普/新闻）不算违规。\n" +
        "2. quality_score/suggestions/tags/summary 以对应专家输出为准，仅做格式整理，不得自行改写结论。\n" +
        "3. similar_article_id/similar_title/similarity 仅在相似文章相似度 ≥ 0.7 时填写，否则为 null/空。\n" +
        "4. 禁止编造工具结果，未调用工具不得声称已评审；某专家异常返回 error 时据其余信息合理降级，仍输出完整 FINAL。";

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

    /** 统一 LLM 出口（安全横切 + token 计量） */
    @Autowired
    private com.heima.content.service.ai.AiLlmGateway llmGateway;

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private SafetyExpertWorker safetyExpertWorker;

    @Autowired
    private QualityExpertWorker qualityExpertWorker;

    @Autowired
    private SeoExpertWorker seoExpertWorker;

    @Autowired
    private CriticExpertWorker criticExpertWorker;

    @Autowired
    private AiSimilarityTools aiSimilarityTools;

    @Autowired
    private PromptSafetyAdvisor promptSafetyAdvisor;

    @Autowired
    private SimilaritySearchTool similaritySearchTool;

    /** Prompt 注册表（P2-1 补齐）：主编主 prompt / 兜底直答 prompt 版本化 + 灰度 + 代码兜底；未装配时走代码常量 */
    @Autowired(required = false)
    private com.heima.content.service.ai.AiPromptRegistry promptRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 注册表解析（带 null 兜底）：DB 不可用/未装配时返回代码常量（version=0） */
    private com.heima.content.service.ai.AiPromptRegistry.ResolvedPrompt prompt(
        String key, String fallback, Integer userId) {
        if (promptRegistry == null) {
            return new com.heima.content.service.ai.AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
        try {
            return promptRegistry.resolve(key, fallback, userId);
        } catch (Exception e) {
            return new com.heima.content.service.ai.AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
    }

    /** 当前登录用户 id（未登录/系统内部调用为 null → 灰度分流不生效，走正式版） */
    private Integer currentUserId() {
        try {
            com.heima.model.user.pojos.ApUser u = com.heima.utils.thread.AppThreadLocalUtil.getUser();
            return u == null ? null : u.getId().intValue();
        } catch (Exception e) {
            return null;
        }
    }

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
        // 提示词版本化（P2-1 补齐）：主编主 prompt / 兜底直答 prompt 接入注册表（可灰度 userId、可回滚）；
        // DB 无行/异常/未装配时回落代码常量（version=0），行为零变化
        com.heima.content.service.ai.AiPromptRegistry.ResolvedPrompt agentP =
            prompt("publish_precheck_agent", AGENT_SYSTEM_PROMPT, currentUserId());
        com.heima.content.service.ai.AiPromptRegistry.ResolvedPrompt directP =
            prompt("publish_precheck_direct", DIRECT_SYSTEM_PROMPT, currentUserId());
        // 提示词安全（净化 user + system 加固 + 输出护栏）由 PromptSafetyAdvisor 声明式处理；
        // Agent 主路径走 AgentRunner 内嵌的 Advisor，兜底路径在下方 ChatClient 上注册同一 Advisor。
        try {
            // 主路径：主编 Agent 调度专家团队（安全/质量/SEO 并行 + 查重 + 可选终审 Critic）
            List<Object> tools = java.util.Arrays.asList(safetyExpertWorker, qualityExpertWorker,
                seoExpertWorker, criticExpertWorker, aiSimilarityTools);
            AgentResult result = agentRunner.run(agentP.content, user, tools, AGENT_MAX_STEPS);
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
            // 兜底：一次性结构化调用（安全三层防御由 Advisor 横切处理）
            try {
                String raw = llmGateway.generateOrNull(
                    com.heima.content.service.ai.AiFeatures.PRECHECK, directP.content, user, null, null);
                JsonNode root = parseJson(raw);
                if (root != null) {
                    vo = fromJson(root);
                }
            } catch (SafetyGuardException e) {
                log.warn("[AiPrecheck] 兜底输出护栏命中（顺从短语），丢弃该结果: {}", e.getMessage());
                return null;
            } catch (Exception e) {
                log.error("[AiPrecheck] 兜底调用失败", e);
                return null;
            }
        }

        if (vo == null) {
            log.warn("[AiPrecheck] Agent 与兜底均未产出预检结果，返回 null");
            return null;
        }

        // 兜底补相似预警（无论 Agent 是否已查，双保险且排除自身）
        fillSimilarity(vo, c, articleId);

        // 封面图多模态审核（可选：提供 coverImageUrl 时执行）
        if (coverImageUrl != null && !coverImageUrl.isBlank()) {
            checkCoverImage(vo, t, coverImageUrl);
        }

        vo.setLatencyMs(System.currentTimeMillis() - start);
        // 归因：记录两处注册表 prompt 实际生效版本（workers 在各自 review() 内自行解析，未含于此）
        log.info("[AiPrecheck] title={}, quality={}, violation={}, tags={}, agentPrompt=v{}, directPrompt=v{}, latency={}ms",
            truncate(t, 30), vo.getQualityScore(), vo.getViolation(),
            vo.getTags() == null ? 0 : vo.getTags().size(),
            agentP.version, directP.version, vo.getLatencyMs());
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

    /** 独立相似度兜底：与 Agent 的 search_similar_article 工具共用同一检索实现（排除自身、双保险） */
    private void fillSimilarity(AiPrecheckVo vo, String content, Long articleId) {
        if (vo == null) {
            return;
        }
        try {
            for (SimilaritySearchTool.SimilarArticle hit : similaritySearchTool.searchSimilar(content)) {
                if (articleId != null && articleId.equals(hit.article().getId())) {
                    continue; // 排除自身
                }
                if (hit.similarity() >= SIMILAR_ALERT) {
                    vo.setSimilarArticleId(hit.article().getId());
                    vo.setSimilarTitle(hit.article().getTitle());
                    vo.setSimilarity(Math.round(hit.similarity() * 10000) / 10000.0);
                }
                break; // 与历史行为一致：仅以最相似一篇作为预警
            }
        } catch (Exception e) {
            log.warn("[AiPrecheck] 相似度兜底失败", e);
        }
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