package com.zhuri.coding.content.service.article.impl;

import com.zhuri.coding.common.aliyun.util.GreenImageScanPlusForOss;
import com.zhuri.coding.content.service.article.AuditService;
import com.zhuri.coding.content.service.article.BailianAiService;
import com.zhuri.coding.model.audit.AuditContext;
import com.zhuri.coding.model.audit.AuditResult;
import com.zhuri.coding.model.audit.AuditServiceUnavailableException;
import com.zhuri.coding.model.audit.ImageScanResult;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 抽象审核模板类
 *
 * 模板方法模式：定义审核流程骨架
 * 审核流程：
 *   1. AI违规内容检测（文本）
 *   2. 图片审核
 *   3. 子类实现审核通过/失败回调
 */
@Slf4j
public abstract class AbstractAuditService implements AuditService {

    @Autowired
    private BailianAiService bailianAiService;

    @Autowired(required = false)
//    private GreenImageScanPlus greenImageScan;
    private GreenImageScanPlusForOss greenImageScanPlusForOss;

    @Override
    public AuditResult audit(AuditContext context) {
        if (context == null || context.getEntityId() == null) {
            return AuditResult.failed("审核参数不完整");
        }

        log.info("开始审核: entityType={}, entityId={}", context.getEntityType(), context.getEntityId());

        // Step 1: AI违规内容检测
        if (context.hasContent()) {
            AuditResult violationResult = checkViolation(context);
            if (!violationResult.isPassed()) {
                log.warn("AI违规检测未通过: entityType={}, entityId={}, reason={}",
                    context.getEntityType(), context.getEntityId(), violationResult.getReason());
                handleFailed(context, violationResult.getReason());
                return violationResult;
            }
            log.info("AI违规检测通过: entityType={}, entityId={}", context.getEntityType(), context.getEntityId());
        }

        // Step 2: 图片审核
        if (context.hasImages() && greenImageScanPlusForOss != null) {
            AuditResult imageResult = checkImages(context);
            if (!imageResult.isPassed()) {
                log.warn("图片审核未通过: entityType={}, entityId={}, reason={}",
                    context.getEntityType(), context.getEntityId(), imageResult.getReason());
                handleFailed(context, imageResult.getReason());
                return imageResult;
            }
            log.info("图片审核通过: entityType={}, entityId={}", context.getEntityType(), context.getEntityId());
        }

        // Step 3: 审核通过，子类回调
        log.info("审核通过: entityType={}, entityId={}", context.getEntityType(), context.getEntityId());
        handlePassed(context);
        return AuditResult.passed();
    }

    /**
     * AI违规内容检测
     *
     * 安全策略：故障关闭。AI 检测服务不可用时直接抛出异常（由调用方按重试/降级/失败处理），
     * 绝不将"检测异常"降级为"通过"，防止审核系统故障时违规内容绕过审核直接上架。
     */
    private AuditResult checkViolation(AuditContext context) {
        try {
            Map<String, Object> result = bailianAiService.checkViolation(
                context.getEntityId(), context.getTitle(), context.getContent());

            // fail-closed：AI 服务不可用或无有效审核结果时抛出异常（由调用方重试/处理），
            // 绝不将"检测异常"降级为"通过"，防止审核系统故障时违规内容放行
            if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
                throw new AuditServiceUnavailableException("内容审核服务暂不可用，请稍后重试");
            }

            if (Boolean.TRUE.equals(result.get("is_violation"))) {
                String violationType = (String) result.getOrDefault("violation_type", "违规内容");
                String violationReason = (String) result.getOrDefault("violation_reason", "内容违反社区规范");
                return AuditResult.failed(violationType, violationType + ": " + violationReason);
            }
            return AuditResult.passed();
        } catch (Exception e) {
            log.error("AI违规检测异常, entityType={}, entityId={}, 故障关闭(fail-closed)",
                context.getEntityType(), context.getEntityId(), e);
            throw new AuditServiceUnavailableException("内容审核服务暂不可用，请稍后重试", e);
        }
    }

    /**
     * 图片审核
     */
    private AuditResult checkImages(AuditContext context) {
        try {
            List<String> imageUrls = context.getImageUrls();
            for (String imageUrl : imageUrls) {
                if (imageUrl == null || imageUrl.isEmpty()) continue;
                ImageScanResult result = toImageScanResult(greenImageScanPlusForOss.imageScan(imageUrl));
                if (result.isHighRisk()) {
                    return AuditResult.failed("图片违规", "图片存在违规内容");
                }
                if (result.isMediumRisk()) {
                    return AuditResult.failed("图片违规", "图片存在不确定内容");
                }
            }
            return AuditResult.passed();
        } catch (Exception e) {
            log.error("图片审核异常, entityType={}, entityId={}", context.getEntityType(), context.getEntityId(), e);
            return AuditResult.failed("图片审核异常");
        }
    }

    /**
     * 将 GreenImageScanPlus 的 Map 结果转换为 ImageScanResult 值对象
     */
    @SuppressWarnings("unchecked")
    private ImageScanResult toImageScanResult(java.util.Map map) {
        if (map == null) {
            return new ImageScanResult(null);
        }
        Object level = map.get("level");
        return new ImageScanResult(level != null ? level.toString() : null);
    }

    // ==================== 子类回调方法 ====================

    /**
     * 审核通过回调（子类实现）
     */
    protected abstract void handlePassed(AuditContext context);

    /**
     * 审核失败回调（子类实现）
     */
    protected abstract void handleFailed(AuditContext context, String reason);
}