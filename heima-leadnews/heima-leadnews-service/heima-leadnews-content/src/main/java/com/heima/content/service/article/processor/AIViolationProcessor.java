package com.heima.content.service.article.processor;

import com.heima.content.service.article.BailianAiService;
import com.heima.model.article.pojos.ApArticle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI违规内容检测处理器
 * 负责调用百炼AI平台进行违规内容检测和内容质量分析
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AIViolationProcessor implements ArticleAuditProcessor {

    private final BailianAiService bailianAiService;

    @Override
    public boolean process(ApArticle article, String content, AuditProcessorContext context) {
        if (content == null || content.isEmpty()) {
            log.info("文章内容为空，跳过AI违规检测, articleId={}", article.getId());
            return true;
        }

        // 1. AI违规内容检测
        log.info("开始AI违规内容检测, articleId={}", article.getId());
        try {
            Map<String, Object> violationResult = bailianAiService.checkViolation(article, content);
            if (violationResult != null && Boolean.TRUE.equals(violationResult.get("is_violation"))) {
                String violationType = (String) violationResult.getOrDefault("violation_type", "违规内容");
                String violationReason = (String) violationResult.getOrDefault("violation_reason", "文章内容违反社区规范");
                context.putExtra("failReason", violationType + ": " + violationReason);
                context.putExtra("violationType", violationType);
                context.putExtra("violationReason", violationReason);
                log.info("AI违规检测未通过, articleId={}, type={}, reason={}", article.getId(), violationType, violationReason);
                return false; // 终止审核流程
            }
            log.info("AI违规检测通过, articleId={}", article.getId());
        } catch (Exception e) {
            log.error("AI违规检测异常, articleId={}, 降级通过", article.getId(), e);
        }

        // 2. AI内容质量分析
        log.info("开始AI内容分析, articleId={}", article.getId());
        try {
            Map<String, Object> aiResult = bailianAiService.analyzeArticle(article, content);
            context.setAiAnalysisResult(aiResult);
            if (aiResult != null) {
                log.info("AI内容分析完成, articleId={}, result={}", article.getId(), aiResult);
            }
        } catch (Exception e) {
            log.error("AI内容分析异常, articleId={}, 将降级为仅通过内容安全审核", article.getId(), e);
        }

        return true;
    }
}