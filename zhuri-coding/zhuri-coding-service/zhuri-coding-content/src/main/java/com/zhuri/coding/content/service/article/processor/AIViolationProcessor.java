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
@org.springframework.core.annotation.Order(1)
public class AIViolationProcessor implements ArticleAuditProcessor {

    private final BailianAiService bailianAiService;

    @Override
    public boolean process(ApArticle article, String content, AuditProcessorContext context) {
        if (content == null || content.isEmpty()) {
            log.info("文章内容为空，跳过AI综合审核, articleId={}", article.getId());
            return true;
        }

        // 一次性综合AI审核：违规检测 + 标题相关性 + 内容质量 + 技术相关性
        // 相比原多次调用，仅传输一次标题与内容，显著降低token消耗
        log.info("开始AI综合审核, articleId={}", article.getId());
        try {
            Map<String, Object> auditResult = bailianAiService.comprehensiveAudit(article, content);
            // 异常分类：
            // - success=false（AI 服务不可用/网络中断/超时/连接满等瞬时故障）→ 抛 AuditRetryableException
            //   由编排方有界重试，避免把「服务抖动」误判成「内容违规」拒绝文章；未真正调用审核接口也无 token 损耗。
            // - is_violation=true（真实违规判定）→ 返回 false 正常驳回，不重试（重试同样内容仍会违规，浪费 token）。
            if (auditResult == null || !Boolean.TRUE.equals(auditResult.get("success"))) {
                String reason = "内容审核服务暂不可用，请稍后重试";
                log.warn("AI综合审核服务不可用，转入可重试处理, articleId={}", article.getId());
                throw new AuditRetryableException(reason + ", articleId=" + article.getId());
            }
            // 违规检测不通过则终止审核流程
            if (Boolean.TRUE.equals(auditResult.get("is_violation"))) {
                String violationType = (String) auditResult.getOrDefault("violation_type", "违规内容");
                String violationReason = (String) auditResult.getOrDefault("violation_reason", "文章内容违反社区规范");
                context.putExtra("failReason", violationType + ": " + violationReason);
                context.putExtra("violationType", violationType);
                context.putExtra("violationReason", violationReason);
                log.info("AI综合审核未通过(违规), articleId={}, type={}, reason={}", article.getId(), violationType, violationReason);
                return false; // 终止审核流程
            }
            // 审核通过，保存分析结果到上下文供后续处理器使用
            context.setAiAnalysisResult(auditResult);
            log.info("AI综合审核通过, articleId={}, qualityScore={}, isTech={}",
                    article.getId(), auditResult.get("qualityScore"), auditResult.get("isTechContent"));
        } catch (AuditRetryableException e) {
            throw e;
        } catch (Exception e) {
            // 非预期异常同样视为瞬时故障，可重试
            log.error("AI综合审核异常，转入可重试处理, articleId={}", article.getId(), e);
            throw new AuditRetryableException("内容审核服务异常: " + e.getMessage() + ", articleId=" + article.getId(), e);
        }

        return true;
    }
}