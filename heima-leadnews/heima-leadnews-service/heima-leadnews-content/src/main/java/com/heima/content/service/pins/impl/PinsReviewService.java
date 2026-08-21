package com.heima.content.service.pins.impl;

import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditEntityType;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.user.pojos.ApUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 沸点异步审核服务
 * 独立 Bean 以确保 @Async 注解被 Spring AOP 代理正确拦截
 */
@Component
@Slf4j
public class PinsReviewService {

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private PinsAuditService pinsAuditService;

    @Autowired(required = false)
    private BehaviorEventBus behaviorEventBus;

    @Async
    public void asyncReviewPins(ApPins pins, ApUser user) {
        try {
            log.info("开始异步审核沸点, pinsId={}", pins.getId());

            // 构建审核上下文
            AuditContext auditContext = new AuditContext(AuditEntityType.PINS, pins.getId(), pins.getAuthorId());
            auditContext.withTitle("")
                .withContent(pins.getContent())
                .withAuthorName(pins.getAuthorName());

            // 提取图片URL列表
            if (pins.getImageUrls() != null && !pins.getImageUrls().isEmpty()) {
                List<String> imageUrls = Arrays.stream(pins.getImageUrls().split(","))
                    .map(String::trim)
                    .filter(url -> !url.isEmpty())
                    .collect(Collectors.toList());
                auditContext.withImageUrls(imageUrls);
            }

            // 执行审核
            com.heima.model.audit.AuditResult result = pinsAuditService.audit(auditContext);

            if (result.isPassed()) {
                log.info("沸点审核通过, pinsId={}", pins.getId());

                // 审核通过后，通过事件总线触发等级积分计算
                if (behaviorEventBus != null && user != null) {
                    try {
                        BehaviorContext behaviorContext = new BehaviorContext(BehaviorType.PUBLISH_PIN, user.getId());
                        behaviorContext.withTarget(2, pins.getId())
                            .withUserInfo(user.getNickname(), user.getImage());
                        behaviorEventBus.execute(behaviorContext);
                        log.info("沸点发布行为已通过事件总线处理, pinsId={}, userId={}", pins.getId(), user.getId());
                    } catch (Exception e) {
                        log.error("沸点发布行为事件处理失败, pinsId={}", pins.getId(), e);
                    }
                }
            } else {
                log.info("沸点审核未通过, pinsId={}, reason={}", pins.getId(), result.getReason());
            }
        } catch (com.heima.model.audit.AuditServiceUnavailableException e) {
            // 审核服务自身不可用（fail-closed）：不标记为内容违规，保持待审核状态，等待人工/后续重审
            log.warn("沸点审核服务不可用，保持待审核, pinsId={}", pins.getId(), e);
        } catch (Exception e) {
            log.error("沸点审核异常, pinsId={}", pins.getId(), e);
            try {
                ApPins updatePins = apPinsMapper.selectById(pins.getId());
                if (updatePins != null) {
                    updatePins.setStatus(ApPins.Status.FAIL.getCode());
                    updatePins.setReason("审核系统异常");
                    apPinsMapper.updateById(updatePins);
                }
            } catch (Exception ex) {
                log.error("更新沸点审核失败状态异常, pinsId={}", pins.getId(), ex);
            }
        }
    }
}