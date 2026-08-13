package com.heima.content.service.article.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.heima.content.mapper.article.ApArticleAiAnalysisMapper;
import com.heima.content.service.article.BailianAiService;
import com.heima.common.bailian.DashScopeClient;
import com.heima.common.bailian.PromptSanitizer;
import com.heima.common.bailian.PromptSecurityConstants;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleAiAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class BailianAiServiceImpl implements BailianAiService {

    @Autowired
    private DashScopeClient dashScopeClient;

    @Autowired
    private ApArticleAiAnalysisMapper aiAnalysisMapper;

    @Autowired
    private PromptSanitizer promptSanitizer;

    // ==================== 提示词模板 ====================

    // Layer 2: 提示词加固 —— 在 system prompt 末尾追加防注入指令
    private static final String SYSTEM_PROMPT =
        "你是一个专业的技术文章审核专家，负责对技术社区的文章进行多维度质量评估。请严格按照要求的JSON格式输出分析结果，不要输出任何额外的解释或markdown格式。"
        + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;

    // 综合AI审核提示词（一次调用完成违规检测、标题相关性、内容质量、技术相关性四项审核）
    private static final String COMPREHENSIVE_AUDIT_PROMPT =
            "你是一位技术社区文章审核专家。请基于标题和内容，一次性完成以下4项审核，严格按JSON格式输出，不要输出任何额外内容。\n\n" +
            "1. 违规检测：判断是否包含色情低俗、暴力恐怖、政治敏感、违法信息（赌博/毒品/诈骗/传销）、人身攻击/侮辱谩骂/谣言等违规内容。注意：技术文章中讨论安全漏洞、渗透测试、技术政策与行业动态的客观分析属正常内容，不算违规；只有明显违规才标记。\n" +
            "2. 标题相关性：判断标题与内容是否相符、是否标题党。分数0-100，越高越相符（90+精准概括，60以下明显夸大或偏离）。\n" +
            "3. 内容质量：从原创性、逻辑性、表达清晰度三方面评分（各0-100）。综合评分 = 原创性*0.4 + 逻辑性*0.3 + 表达清晰度*0.3。\n" +
            "4. 技术相关性：判断是否属于技术内容（技术硬核/实践/趋势/时政/程序员职业/技术叙事等）。纯游戏攻略、音乐推荐、娱乐八卦、生活分享等不算。\n\n" +
            "输出JSON：\n" +
            "{\"is_violation\": true/false, \"violation_type\": \"\", \"violation_reason\": \"\", \"title_relevance_score\": 0, \"title_relevance_reason\": \"\", \"quality_score\": 0, \"originality_score\": 0, \"logic_score\": 0, \"clarity_score\": 0, \"comment\": \"\", \"is_tech\": true/false, \"confidence\": 0.0, \"tech_reason\": \"\"}\n\n" +
            "标题：%s\n\n内容：%s";

    // 违规内容检测提示词
    private static final String VIOLATION_CHECK_PROMPT =
            "请对以下文章内容进行违规内容检测，判断是否包含违规信息。\n\n" +
            "违规类型包括但不限于：\n" +
            "1. 色情低俗：包含色情描写、性暗示、低俗图片描述、不雅用语等\n" +
            "2. 暴力恐怖：包含暴力血腥描写、恐怖主义内容、极端行为描述等\n" +
            "3. 政治敏感：包含政治敏感话题、攻击性言论、危害国家安全的内容等\n" +
            "4. 违法信息：包含赌博、毒品、诈骗、传销等违法内容\n" +
            "5. 其他违规：人身攻击、侮辱谩骂、散布谣言、侵犯隐私等\n\n" +
            "请以JSON格式输出：\n" +
            "{\"is_violation\": true/false, \"violation_type\": \"<违规类型，无违规时为空字符串>\", \"violation_reason\": \"<违规原因，100字以内，无违规时为空字符串>\"}\n\n" +
            "注意：\n" +
            "- 技术文章中讨论安全漏洞、渗透测试等内容属于正常技术讨论，不属于违规\n" +
            "- 对技术政策、行业动态的客观分析属于正常内容\n" +
            "- 只有明显违规的内容才标记为违规\n" +
            "- 请严格以JSON格式输出，不要添加任何额外说明\n\n" +
            "标题：%s\n\n内容：%s";

    @Override
    public Map<String, Object> comprehensiveAudit(ApArticle article, String content) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("is_violation", false);
        result.put("violation_type", "");
        result.put("violation_reason", "");
        result.put("titleRelevanceScore", 0);
        result.put("qualityScore", 0);
        result.put("isTechContent", true);

        if (content == null || content.isEmpty()) {
            log.warn("Article content is empty for comprehensive audit, articleId={}", article.getId());
            result.put("success", true);
            return result;
        }

        // 截断内容，避免token超限
        String truncatedContent = content.length() > 4000 ? content.substring(0, 4000) : content;
        String rawTitle = article.getTitle() != null ? article.getTitle() : "";

        // Layer 1: 输入净化 —— 清洗用户输入中的注入内容
        String safeTitle = promptSanitizer.sanitize(rawTitle);
        String safeContent = promptSanitizer.sanitize(truncatedContent);

        // Layer 1: UUID 动态分隔符包裹 —— 防止攻击者伪造边界标签
        String wrappedTitle = promptSanitizer.wrapWithDelimiters("title", safeTitle);
        String wrappedContent = promptSanitizer.wrapWithDelimiters("article", safeContent);

        try {
            // 一次调用完成违规检测、标题相关性、内容质量、技术相关性四项审核
            log.info("Starting comprehensive AI audit for articleId={}", article.getId());
            String auditPrompt = String.format(COMPREHENSIVE_AUDIT_PROMPT, wrappedTitle, wrappedContent);
            String auditResponse = dashScopeClient.callGeneration(SYSTEM_PROMPT, auditPrompt);

            if (auditResponse != null) {
                JSONObject auditJson = parseJsonResponse(auditResponse);
                if (auditJson != null) {
                    // 违规检测
                    Boolean isViolation = auditJson.getBoolean("is_violation");
                    result.put("is_violation", isViolation != null && isViolation);
                    result.put("violation_type", auditJson.getString("violation_type") != null ? auditJson.getString("violation_type") : "");
                    result.put("violation_reason", auditJson.getString("violation_reason") != null ? auditJson.getString("violation_reason") : "");

                    // 标题相关性
                    result.put("titleRelevanceScore", auditJson.getInteger("title_relevance_score") != null ? auditJson.getInteger("title_relevance_score") : 0);

                    // 内容质量
                    result.put("qualityScore", auditJson.getInteger("quality_score") != null ? auditJson.getInteger("quality_score") : 0);

                    // 技术相关性
                    Boolean isTech = auditJson.getBoolean("is_tech");
                    result.put("isTechContent", isTech != null && isTech);

                    // 持久化综合审核结果
                    saveComprehensiveAudit(article.getId(), auditJson, auditResponse);

                    log.info("Comprehensive AI audit completed for articleId={}, is_violation={}, qualityScore={}, isTech={}",
                            article.getId(), result.get("is_violation"), result.get("qualityScore"), result.get("isTechContent"));
                }
            }
            result.put("success", true);
        } catch (Exception e) {
            log.error("Comprehensive AI audit failed for articleId={}: {}", article.getId(), e.getMessage(), e);
            // 审核失败不阻塞流程，降级通过
            result.put("success", true);
            result.put("is_violation", false);
        }

        return result;
    }

    @Override
    public Map<String, Object> checkViolation(Long entityId, String title, String content) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("is_violation", false);
        result.put("violation_type", "");
        result.put("violation_reason", "");

        if (content == null || content.isEmpty()) {
            log.warn("Content is empty for violation check, entityId={}", entityId);
            result.put("success", true);
            return result;
        }

        String truncatedContent = content.length() > 4000 ? content.substring(0, 4000) : content;
        String rawTitle = title != null ? title : "";

        // Layer 1: 输入净化
        String safeTitle = promptSanitizer.sanitize(rawTitle);
        String safeContent = promptSanitizer.sanitize(truncatedContent);
        String wrappedContent = promptSanitizer.wrapWithDelimiters("check-content", safeContent);

        try {
            log.info("Starting AI violation check for entityId={}", entityId);
            String violationPrompt = String.format(VIOLATION_CHECK_PROMPT, safeTitle, wrappedContent);
            String violationResponse = dashScopeClient.callGeneration(SYSTEM_PROMPT, violationPrompt);

            if (violationResponse != null) {
                JSONObject violationJson = parseJsonResponse(violationResponse);
                if (violationJson != null) {
                    Boolean isViolation = violationJson.getBoolean("is_violation");
                    String violationType = violationJson.getString("violation_type");
                    String violationReason = violationJson.getString("violation_reason");

                    result.put("is_violation", isViolation != null && isViolation);
                    result.put("violation_type", violationType != null ? violationType : "");
                    result.put("violation_reason", violationReason != null ? violationReason : "");

                    log.info("AI violation check completed for entityId={}, is_violation={}, type={}",
                            entityId, result.get("is_violation"), result.get("violation_type"));
                }
            }
            result.put("success", true);
        } catch (Exception e) {
            log.error("AI violation check failed for entityId={}: {}", entityId, e.getMessage(), e);
            result.put("success", true);
            result.put("is_violation", false);
        }

        return result;
    }

    /**
     * 从AI响应中提取JSON
     */
    private JSONObject parseJsonResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        try {
            // 尝试直接解析
            String cleaned = response.trim();
            // 移除可能的markdown代码块标记
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            }
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();

            // 尝试提取JSON对象
            Pattern pattern = Pattern.compile("\\{[^{}]*\\}");
            Matcher matcher = pattern.matcher(cleaned);
            if (matcher.find()) {
                return JSON.parseObject(matcher.group());
            }
            return JSON.parseObject(cleaned);
        } catch (Exception e) {
            log.warn("Failed to parse AI response as JSON: {}", response.substring(0, Math.min(200, response.length())));
            return null;
        }
    }

    /**
     * 保存综合审核结果到AI分析表（先删旧记录再插入，覆盖当次完整审核数据）
     */
    private void saveComprehensiveAudit(Long articleId, JSONObject json, String rawResponse) {
        try {
            // 先删除旧记录
            QueryWrapper<ApArticleAiAnalysis> deleteWrapper = new QueryWrapper<>();
            deleteWrapper.eq("article_id", articleId);
            aiAnalysisMapper.delete(deleteWrapper);

            ApArticleAiAnalysis analysis = new ApArticleAiAnalysis();
            analysis.setArticleId(articleId);
            analysis.setCreatedTime(new Date());

            // 违规检测
            analysis.setIsViolation(json.getBoolean("is_violation"));
            analysis.setViolationType(json.getString("violation_type"));
            analysis.setViolationReason(json.getString("violation_reason"));
            // 标题相关性
            analysis.setTitleRelevanceScore(json.getInteger("title_relevance_score"));
            analysis.setTitleRelevanceReason(json.getString("title_relevance_reason"));
            // 内容质量
            analysis.setQualityScore(json.getInteger("quality_score"));
            analysis.setOriginalityScore(json.getInteger("originality_score"));
            analysis.setLogicScore(json.getInteger("logic_score"));
            analysis.setClarityScore(json.getInteger("clarity_score"));
            analysis.setQualityComment(json.getString("comment"));
            // 技术相关性
            analysis.setIsTechContent(json.getBoolean("is_tech"));
            if (json.get("confidence") != null) {
                analysis.setTechConfidence(BigDecimal.valueOf(json.getDouble("confidence")));
            }
            // 原始响应
            analysis.setRawResponse(rawResponse);

            aiAnalysisMapper.insert(analysis);
        } catch (Exception e) {
            log.error("Failed to save comprehensive audit for articleId={}: {}", articleId, e.getMessage());
        }
    }
}