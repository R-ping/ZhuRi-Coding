package com.zhuri.coding.content.service.article.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhuri.coding.content.mapper.article.ApArticleAiAnalysisMapper;
import com.zhuri.coding.content.model.ai.ArticleAuditResult;
import com.zhuri.coding.content.model.ai.ViolationCheckResult;
import com.zhuri.coding.content.service.article.BailianAiService;
import com.zhuri.coding.common.bailian.PromptSanitizer;
import com.zhuri.coding.common.bailian.StructuredOutputInvoker;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleAiAnalysis;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class BailianAiServiceImpl implements BailianAiService {

    @Autowired
    private StructuredOutputInvoker structuredOutputInvoker;

    @Autowired
    private ApArticleAiAnalysisMapper aiAnalysisMapper;

    @Autowired
    private PromptSanitizer promptSanitizer;

    // ==================== 提示词模板 ====================

    // Layer 2: 提示词加固 —— 在 system prompt 末尾追加防注入指令
    private static final String SYSTEM_PROMPT =
        "你是一个专业的技术文章审核专家，负责对技术社区的文章进行多维度质量评估。请基于标题和内容，一次性完成以下4项审核，严格按JSON格式输出，不要输出任何额外内容。\n\n" +
            "1. 违规检测：判断是否包含色情低俗、暴力恐怖、政治敏感、违法信息（赌博/毒品/诈骗/传销）、人身攻击/侮辱谩骂/谣言等违规内容。注意：技术文章中讨论安全漏洞、渗透测试、技术政策与行业动态的客观分析属正常内容，不算违规；只有明显违规才标记。\n" +
            "2. 标题相关性：判断标题与内容是否相符、是否标题党。分数0-100，越高越相符（90+精准概括，60以下明显夸大或偏离）。\n" +
            "3. 内容质量：从原创性、逻辑性、表达清晰度三方面评分（各0-100）。综合评分 = 原创性*0.4 + 逻辑性*0.3 + 表达清晰度*0.3。\n" +
            "4. 技术相关性：判断是否属于技术内容（技术硬核/实践/趋势/时政/程序员职业/技术叙事等）。纯游戏攻略、音乐推荐、娱乐八卦、生活分享等不算。\n\n" +
            "请严格按照要求的JSON格式输出分析结果，不要输出任何额外的解释或markdown格式。"+
            "{\"is_violation\": true/false, \"violation_type\": \"\", \"violation_reason\": \"\", \"title_relevance_score\": 0, \"title_relevance_reason\": \"\", \"quality_score\": 0, \"originality_score\": 0, \"logic_score\": 0, \"clarity_score\": 0, \"comment\": \"\", \"is_tech\": true/false, \"confidence\": 0.0, \"tech_reason\": \"\"}";
        // 防注入指令由 StructuredOutputInvoker 在调用时统一追加

    // 综合AI审核提示词（一次调用完成违规检测、标题相关性、内容质量、技术相关性四项审核）
    private static final String COMPREHENSIVE_AUDIT_PROMPT =
//            "你是一位技术社区文章审核专家。请基于标题和内容，一次性完成以下4项审核，严格按JSON格式输出，不要输出任何额外内容。\n\n" +
//            "1. 违规检测：判断是否包含色情低俗、暴力恐怖、政治敏感、违法信息（赌博/毒品/诈骗/传销）、人身攻击/侮辱谩骂/谣言等违规内容。注意：技术文章中讨论安全漏洞、渗透测试、技术政策与行业动态的客观分析属正常内容，不算违规；只有明显违规才标记。\n" +
//            "2. 标题相关性：判断标题与内容是否相符、是否标题党。分数0-100，越高越相符（90+精准概括，60以下明显夸大或偏离）。\n" +
//            "3. 内容质量：从原创性、逻辑性、表达清晰度三方面评分（各0-100）。综合评分 = 原创性*0.4 + 逻辑性*0.3 + 表达清晰度*0.3。\n" +
//            "4. 技术相关性：判断是否属于技术内容（技术硬核/实践/趋势/时政/程序员职业/技术叙事等）。纯游戏攻略、音乐推荐、娱乐八卦、生活分享等不算。\n\n" +
//            "输出JSON：\n" +
//            "{\"is_violation\": true/false, \"violation_type\": \"\", \"violation_reason\": \"\", \"title_relevance_score\": 0, \"title_relevance_reason\": \"\", \"quality_score\": 0, \"originality_score\": 0, \"logic_score\": 0, \"clarity_score\": 0, \"comment\": \"\", \"is_tech\": true/false, \"confidence\": 0.0, \"tech_reason\": \"\"}\n\n" +
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
            ArticleAuditResult auditResult = structuredOutputInvoker.invoke(
                SYSTEM_PROMPT, auditPrompt, ArticleAuditResult.class,
                AppHttpCodeEnum.SERVER_ERROR, "AI综合审核失败：", "综合审核", log);

            // 违规检测
            result.put("is_violation", Boolean.TRUE.equals(auditResult.getIsViolation()));
            result.put("violation_type", auditResult.getViolationType() != null ? auditResult.getViolationType() : "");
            result.put("violation_reason", auditResult.getViolationReason() != null ? auditResult.getViolationReason() : "");
            // 标题相关性
            result.put("titleRelevanceScore", auditResult.getTitleRelevanceScore() != null ? auditResult.getTitleRelevanceScore() : 0);
            // 内容质量
            result.put("qualityScore", auditResult.getQualityScore() != null ? auditResult.getQualityScore() : 0);
            // 技术相关性
            result.put("isTechContent", Boolean.TRUE.equals(auditResult.getIsTech()));

            // 持久化综合审核结果
            saveComprehensiveAudit(article.getId(), auditResult);

            log.info("Comprehensive AI audit completed for articleId={}, is_violation={}, qualityScore={}, isTech={}",
                    article.getId(), result.get("is_violation"), result.get("qualityScore"), result.get("isTechContent"));
            // 仅在获取到有效审核结果时标记成功；否则保持 success=false 走 fail-closed，防止故障时违规内容放行
            result.put("success", true);
        } catch (Exception e) {
            // fail-closed：AI 审核服务不可用时保持 success=false，绝不降级为"通过"
            log.error("Comprehensive AI audit failed, fail-closed, articleId={}: {}", article.getId(), e.getMessage(), e);
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
            ViolationCheckResult violationResult = structuredOutputInvoker.invoke(
                SYSTEM_PROMPT, violationPrompt, ViolationCheckResult.class,
                AppHttpCodeEnum.SERVER_ERROR, "AI违规检测失败：", "违规检测", log);

            result.put("is_violation", Boolean.TRUE.equals(violationResult.getIsViolation()));
            result.put("violation_type", violationResult.getViolationType() != null ? violationResult.getViolationType() : "");
            result.put("violation_reason", violationResult.getViolationReason() != null ? violationResult.getViolationReason() : "");

            log.info("AI violation check completed for entityId={}, is_violation={}, type={}",
                    entityId, result.get("is_violation"), result.get("violation_type"));
            // 仅在获取到有效审核结果时标记成功；否则保持 success=false 走 fail-closed
            result.put("success", true);
        } catch (Exception e) {
            // fail-closed：AI 审核服务不可用时保持 success=false，绝不降级为"通过"
            log.error("AI violation check failed, fail-closed, entityId={}: {}", entityId, e.getMessage(), e);
        }

        return result;
    }

    /**
     * 保存综合审核结果到AI分析表（先删旧记录再插入，覆盖当次完整审核数据）
     */
    private void saveComprehensiveAudit(Long articleId, ArticleAuditResult result) {
        try {
            // 先删除旧记录
            QueryWrapper<ApArticleAiAnalysis> deleteWrapper = new QueryWrapper<>();
            deleteWrapper.eq("article_id", articleId);
            aiAnalysisMapper.delete(deleteWrapper);

            ApArticleAiAnalysis analysis = new ApArticleAiAnalysis();
            analysis.setArticleId(articleId);
            analysis.setCreatedTime(new Date());

            // 违规检测
            analysis.setIsViolation(result.getIsViolation());
            analysis.setViolationType(result.getViolationType());
            analysis.setViolationReason(result.getViolationReason());
            // 标题相关性
            analysis.setTitleRelevanceScore(result.getTitleRelevanceScore());
            analysis.setTitleRelevanceReason(result.getTitleRelevanceReason());
            // 内容质量
            analysis.setQualityScore(result.getQualityScore());
            analysis.setOriginalityScore(result.getOriginalityScore());
            analysis.setLogicScore(result.getLogicScore());
            analysis.setClarityScore(result.getClarityScore());
            analysis.setQualityComment(result.getComment());
            // 技术相关性
            analysis.setIsTechContent(result.getIsTech());
            if (result.getConfidence() != null) {
                analysis.setTechConfidence(BigDecimal.valueOf(result.getConfidence()));
            }
            // 原始响应：以结构化 DTO 序列化结果落库，保留当次审核结论
            analysis.setRawResponse(JSON.toJSONString(result));

            aiAnalysisMapper.insert(analysis);
        } catch (Exception e) {
            log.error("Failed to save comprehensive audit for articleId={}: {}", articleId, e.getMessage());
        }
    }
}