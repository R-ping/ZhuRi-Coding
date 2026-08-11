package com.heima.content.service.pins.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.apis.notification.INotificationClient;
import com.heima.content.mapper.circle.ApCircleMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.mapper.topic.TopicMapper;
import com.heima.model.circle.pojos.ApCircle;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.pins.dtos.PinsPublishDTO;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.topic.pojos.ApTopic;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class PinsPublishService {

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private TopicMapper topicMapper;

    @Autowired
    private ApCircleMapper apCircleMapper;

    @Autowired
    private PinsReviewService pinsReviewService;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(rollbackFor = Exception.class)
    public ResponseResult publish(PinsPublishDTO dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "内容不能为空");
        }

        ApPins pins = getApPins(dto, user);
        apPinsMapper.insert(pins);

        // 更新话题计数
        if (dto.getTopicId() != null) {
            ApTopic topic = topicMapper.selectById(dto.getTopicId());
            if (topic != null) {
                topic.setPostCount((topic.getPostCount() != null ? topic.getPostCount() : 0) + 1);
                topicMapper.updateById(topic);
            }
        }

        // 更新圈子计数
        if (dto.getCircleId() != null) {
            ApCircle circle = apCircleMapper.selectById(dto.getCircleId());
            if (circle != null) {
                circle.setPinsCount((circle.getPinsCount() != null ? circle.getPinsCount() : 0) + 1);
                apCircleMapper.updateById(circle);
            }
        }

        // 发送系统通知（沸点正在审核中）
        sendPinsCreatedNotification(pins);

        // 异步审核（使用独立 Bean 确保 @Async 被 AOP 代理正确拦截）
        pinsReviewService.asyncReviewPins(pins, user);

        return ResponseResult.okResult(pins);
    }

    @NotNull
    private static ApPins getApPins(PinsPublishDTO dto, ApUser user) {
        ApPins pins = new ApPins();
        pins.setUserId(user.getId().longValue());
        pins.setAuthorId(user.getId().longValue());
        pins.setAuthorName(user.getNickname() != null ? user.getNickname() : "");
        pins.setAuthorImage(user.getImage() != null ? user.getImage() : "");
        pins.setUserName(user.getNickname() != null ? user.getNickname() : "");
        pins.setUserAvatar(user.getImage() != null ? user.getImage() : "");
        pins.setContent(dto.getContent());
        pins.setImageUrls(dto.getImageUrls() != null ? dto.getImageUrls() : "");
        pins.setTopicTags(dto.getTopicTags() != null ? dto.getTopicTags() : "");
        pins.setTopicId(dto.getTopicId());
        pins.setCircleId(dto.getCircleId());
        pins.setLinkUrl(dto.getLinkUrl() != null ? dto.getLinkUrl() : "");
        pins.setLinkTitle(dto.getLinkTitle() != null ? dto.getLinkTitle() : "");
        pins.setStatus(ApPins.Status.SUBMIT.getCode());
        pins.setLikes(0);
        pins.setComment(0);
        pins.setShareCount(0);
        pins.setIsDeleted(false);
        pins.setCreatedTime(new Date());
        pins.setPublishTime(new Date());
        return pins;
    }

    public void sendPinsCreatedNotification(ApPins pins) {
        try {
            if (notificationClient == null) {
                log.warn("通知服务不可用，跳过发送沸点发布通知, pinsId={}", pins.getId());
                return;
            }
            String message = "你的沸点已发布，正在审核中。";
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("pinsId", String.valueOf(pins.getId()));
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");
            String contentJson = objectMapper.writeValueAsString(contentMap);
            Map<String, Object> params = new HashMap<>();
            params.put("userId", pins.getAuthorId());
            params.put("type", 4);
            params.put("sourceId", String.valueOf(pins.getId()));
            params.put("content", contentJson);
            ResponseResult result = notificationClient.createNotification(params);
            if (result != null && result.getCode() == 200) {
                log.info("沸点发布通知已发送, pinsId={}, authorId={}", pins.getId(), pins.getAuthorId());
            } else {
                log.warn("沸点发布通知发送失败, pinsId={}, result={}", pins.getId(), result);
            }
        } catch (Exception e) {
            log.error("发送沸点发布通知异常, pinsId={}", pins.getId(), e);
        }
    }
}