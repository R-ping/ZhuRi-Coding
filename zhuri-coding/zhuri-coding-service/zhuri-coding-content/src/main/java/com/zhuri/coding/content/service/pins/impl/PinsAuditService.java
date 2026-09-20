package com.heima.content.service.pins.impl;

import com.heima.content.service.article.impl.AbstractAuditService;
import com.heima.apis.notification.INotificationClient;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.utils.NotificationHelper;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.audit.AuditContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 沸点审核服务
 * 审核流程：AI违规检测 → 图片审核 → 更新状态
 */
@Slf4j
@Service
public class PinsAuditService extends AbstractAuditService {

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Override
    protected void handlePassed(AuditContext context) {
        ApPins pins = apPinsMapper.selectById(context.getEntityId());
        if (pins == null) {
            log.error("沸点不存在, pinsId={}", context.getEntityId());
            return;
        }
        pins.setStatus(ApPins.Status.PUBLISHED.getCode());
        pins.setReviewTime(new Date());
        apPinsMapper.updateById(pins);
        log.info("沸点审核通过, pinsId={}", context.getEntityId());
    }

    @Override
    protected void handleFailed(AuditContext context, String reason) {
        ApPins pins = apPinsMapper.selectById(context.getEntityId());
        if (pins == null) {
            log.error("沸点不存在, pinsId={}", context.getEntityId());
            return;
        }
        pins.setStatus(ApPins.Status.FAIL.getCode());
        pins.setReason(reason);
        apPinsMapper.updateById(pins);

        // 发送审核失败系统通知
        NotificationHelper.sendModerationFailNotification(
            notificationClient,
            pins.getAuthorId(),
            String.valueOf(pins.getId()),
            "沸点",
            null,
            reason
        );
        log.info("沸点审核未通过, pinsId={}, reason={}", context.getEntityId(), reason);
    }
}