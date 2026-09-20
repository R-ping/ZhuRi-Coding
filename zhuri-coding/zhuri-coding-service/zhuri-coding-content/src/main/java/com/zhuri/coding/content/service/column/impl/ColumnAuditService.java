package com.heima.content.service.column.impl;

import com.heima.content.service.article.impl.AbstractAuditService;
import com.heima.apis.notification.INotificationClient;
import com.heima.content.mapper.column.ApColumnMapper;
import com.heima.content.utils.NotificationHelper;
import com.heima.model.column.pojos.ApColumn;
import com.heima.model.audit.AuditContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 专栏审核服务
 * 审核流程：AI违规检测 → 图片审核 → 更新状态
 */
@Slf4j
@Service
public class ColumnAuditService extends AbstractAuditService {

    @Autowired
    private ApColumnMapper apColumnMapper;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Override
    protected void handlePassed(AuditContext context) {
        ApColumn column = apColumnMapper.selectById(context.getEntityId());
        if (column == null) {
            log.error("专栏不存在, columnId={}", context.getEntityId());
            return;
        }
        column.setStatus(ApColumn.Status.PUBLISHED.getCode());
        apColumnMapper.updateById(column);
        log.info("专栏审核通过, columnId={}", context.getEntityId());
    }

    @Override
    protected void handleFailed(AuditContext context, String reason) {
        ApColumn column = apColumnMapper.selectById(context.getEntityId());
        if (column == null) {
            log.error("专栏不存在, columnId={}", context.getEntityId());
            return;
        }
        column.setStatus(ApColumn.Status.FAIL.getCode());
        apColumnMapper.updateById(column);

        // 发送审核失败通知
        sendModerationFailNotification(column, reason);
        log.info("专栏审核未通过, columnId={}, reason={}", context.getEntityId(), reason);
    }

    private void sendModerationFailNotification(ApColumn column, String reason) {
        NotificationHelper.sendModerationFailNotification(
            notificationClient,
            column.getAuthorId(),
            String.valueOf(column.getId()),
            "专栏",
            column.getTitle(),
            reason
        );
    }
}