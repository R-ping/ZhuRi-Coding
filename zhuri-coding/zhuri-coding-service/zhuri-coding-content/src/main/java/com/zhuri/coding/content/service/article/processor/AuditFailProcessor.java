package com.zhuri.coding.content.service.article.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.AuditRecordService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 审核失败处理器
 * 负责处理审核失败时的后续操作：更新文章状态、写入审计记录、发送通知
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditFailProcessor {

    private final ApArticleMapper apArticleMapper;
    private final AuditRecordService auditRecordService;
    private final INotificationClient notificationClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 系统级审核异常（辅助环节重试耗尽或未预期异常）的终态失败原因，用于告知作者重新提交 */
    public static final String SYSTEM_ERROR_REASON = "系统审核异常，审核未完成，请稍后重新提交";

    /** 兜底失败原因：处理器返回 false 却未写入 failReason 时使用，避免“已判定不通过但文章停在审核中” */
    private static final String DEFAULT_FAIL_REASON = "内容未通过审核";

    /** 业务违规驳回（准入前置命中，或审核判定不通过）：给作者的文案即为“违反社区规范” */
    public void handleFail(ApArticle article, String failReason) {
        recordFail(article, failReason, false);
    }

    /**
     * 系统异常导致审核未完成（辅助环节重试耗尽 / 未预期异常）。
     *
     * <p><b>为什么必须与 {@link #handleFail} 分开</b>：两者都落 {@code Status.FAIL} 终态，但
     * **面向作者的文案必须不同**。原先两条路径共用同一段硬编码文案
     * “因违反社区规范已被删除”，结果是 AI 服务抖动导致重试耗尽时，作者被误告知“我的文章违规了”——
     * 这正是“技术异常被感知为误判”的缺口所在。
     */
    public void handleSystemErrorFail(ApArticle article, String failReason) {
        recordFail(article, failReason, true);
    }

    /**
     * 处理审核失败
     *
     * @param article     文章实体
     * @param failReason  失败原因
     * @param systemError true=系统异常（非作者责任），false=业务违规驳回
     */
    private void recordFail(ApArticle article, String failReason, boolean systemError) {
        if (article == null) {
            return;
        }
        // 注意：failReason 为空时不再直接 return —— 否则“处理器返回 false 但没写失败原因”会让文章
        // 永久停留在 SUBMIT（既非发布也非失败），属于会阻塞审核的隐藏路径，这里退化为通用原因兜底。
        String reason = failReason != null && !failReason.isBlank() ? failReason : DEFAULT_FAIL_REASON;

        article.setStatus(Status.FAIL.getCode());
        article.setReason(reason);

        // 更新文章状态
        apArticleMapper.updateById(article);

        // 写入审计记录（失败轨迹，内容由审计服务兜底查询）
        auditRecordService.record(article, null, ArticleConstants.AUDIT_STATUS_FAIL, reason);

        // 发送系统通知（按失败类别选择文案）
        sendModerationFailNotification(article, reason, systemError);
    }

    /**
     * 发送审核失败系统通知
     *
     * @param systemError true=系统异常文案（明确“未被删除、请重新提交”）；false=违规驳回文案
     */
    private void sendModerationFailNotification(ApArticle article, String reason, boolean systemError) {
        try {
            if (notificationClient == null) {
                log.warn("通知服务不可用，跳过发送审核失败通知, articleId={}", article.getId());
                return;
            }

            String articleTitle = article.getTitle() != null ? article.getTitle() : "无标题";
            String violationDetails = reason != null ? reason : "违反社区规范";

            // 系统异常：绝不能出现“违规 / 已被删除”字样，否则作者会被误导为内容违规
            String message = systemError
                ? String.format(
                    "你的文章《%s》本次审核未完成（系统繁忙），文章未被删除，请稍后重新提交。原因：%s",
                    articleTitle, violationDetails)
                : String.format(
                    "你的文章《%s》因违反社区规范已被删除。详细规则请见《社区规范》。文章内容: %s",
                    articleTitle, violationDetails);

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("articleId", String.valueOf(article.getId()));
            contentMap.put("title", articleTitle);
            contentMap.put("reason", violationDetails);
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");
            // 供前端区分展示：系统异常不是违规处置
            contentMap.put("auditFailType", systemError ? "SYSTEM_ERROR" : "VIOLATION");

            String contentJson = objectMapper.writeValueAsString(contentMap);

            Map<String, Object> params = new HashMap<>();
            params.put("userId", article.getAuthorId());
            params.put("type", ArticleConstants.NOTIFICATION_TYPE_SYSTEM);
            params.put("sourceId", String.valueOf(article.getId()));
            params.put("content", contentJson);

            ResponseResult result = notificationClient.createNotification(params);
            if (result != null && result.getCode() == 200) {
                log.info("审核失败通知已发送, articleId={}, authorId={}", article.getId(), article.getAuthorId());
            } else {
                log.warn("审核失败通知发送失败, articleId={}, result={}", article.getId(), result);
            }
        } catch (Exception e) {
            log.error("发送审核失败通知异常, articleId={}, 不影响审核流程", article.getId(), e);
        }
    }
}