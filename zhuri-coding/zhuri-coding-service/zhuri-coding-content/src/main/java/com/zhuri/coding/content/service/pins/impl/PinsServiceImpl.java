package com.zhuri.coding.content.service.pins.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.service.pins.PinsService;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class PinsServiceImpl extends ServiceImpl<ApPinsMapper, ApPins> implements PinsService {

    @Autowired(required = false)
    private INotificationClient notificationClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ResponseResult list(Long authorId, Integer page, Integer size, String status) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = authorId != null ? authorId : user.getId().longValue();
        Page<ApPins> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApPins> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPins::getAuthorId, userId);
        wrapper.eq(ApPins::getIsDeleted, false);
        if (status != null && !status.isEmpty()) {
            Byte statusCode = getStatusCode(status);
            if (statusCode != null) {
                wrapper.eq(ApPins::getStatus, statusCode);
            }
        }
        wrapper.orderByDesc(ApPins::getCreatedTime);
        IPage<ApPins> result = page(pageParam, wrapper);
        Map<String, Object> data = new HashMap<>();
        data.put("list", result.getRecords());
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult statistics(Long authorId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = authorId != null ? authorId : user.getId().longValue();
        Map<String, Object> data = new HashMap<>();
        LambdaQueryWrapper<ApPins> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPins::getAuthorId, userId);
        wrapper.eq(ApPins::getIsDeleted, false);
        data.put("total", count(wrapper));
        wrapper.eq(ApPins::getStatus, ApPins.Status.PUBLISHED.getCode());
        data.put("published", count(wrapper));
        wrapper.clear();
        wrapper.eq(ApPins::getAuthorId, userId);
        wrapper.eq(ApPins::getIsDeleted, false);
        wrapper.eq(ApPins::getStatus, ApPins.Status.SUBMIT.getCode());
        data.put("reviewing", count(wrapper));
        wrapper.clear();
        wrapper.eq(ApPins::getAuthorId, userId);
        wrapper.eq(ApPins::getIsDeleted, false);
        wrapper.eq(ApPins::getStatus, ApPins.Status.FAIL.getCode());
        data.put("rejected", count(wrapper));
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createPins(ApPins pins) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (pins.getContent() == null || pins.getContent().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "内容不能为空");
        }
        pins.setUserId(user.getId().longValue());
        pins.setAuthorId(user.getId().longValue());
        pins.setAuthorName(user.getNickname());
        pins.setAuthorImage(user.getImage());
        pins.setIsDeleted(false);
        pins.setStatus(ApPins.Status.SUBMIT.getCode());
        pins.setLikes(0);
        pins.setComment(0);
        pins.setShareCount(0);
        pins.setCreatedTime(new Date());
        pins.setPublishTime(new Date());
        save(pins);

        // 发送系统通知（best-effort，失败不影响沸点创建流程）
        sendPinsCreatedNotification(pins);

        return ResponseResult.okResult(pins);
    }

    /**
     * 发送沸点发布成功系统通知
     */
    private void sendPinsCreatedNotification(ApPins pins) {
        try {
            if (notificationClient == null) {
                log.warn("通知服务不可用，跳过发送沸点发布通知, pinsId={}", pins.getId());
                return;
            }

            String message = "你的沸点已发布成功，并推荐至'最新'板块。";

            // 构建通知内容JSON
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("pinsId", String.valueOf(pins.getId()));
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");

            String contentJson = objectMapper.writeValueAsString(contentMap);

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("userId", Long.valueOf(pins.getAuthorId()));
            params.put("type", 4); // 系统通知
            params.put("sourceId", String.valueOf(pins.getId()));
            params.put("content", contentJson);

            ResponseResult result = notificationClient.createNotification(params);
            if (result != null && result.getCode() == 200) {
                log.info("沸点发布通知已发送, pinsId={}, authorId={}", pins.getId(), pins.getAuthorId());
            } else {
                log.warn("沸点发布通知发送失败, pinsId={}, result={}", pins.getId(), result);
            }
        } catch (Exception e) {
            log.error("发送沸点发布通知异常, pinsId={}, 不影响沸点创建流程", pins.getId(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult deletePins(Long id) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "id 不能为空");
        }
        ApPins pins = getById(id);
        if (pins == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        if (!pins.getAuthorId().equals(user.getId().longValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        pins.setIsDeleted(true);
        updateById(pins);
        return ResponseResult.okResult();
    }

    private Byte getStatusCode(String status) {
        switch (status) {
            case "published":
                return ApPins.Status.PUBLISHED.getCode();
            case "reviewing":
                return ApPins.Status.SUBMIT.getCode();
            case "rejected":
                return ApPins.Status.FAIL.getCode();
            default:
                return null;
        }
    }
}