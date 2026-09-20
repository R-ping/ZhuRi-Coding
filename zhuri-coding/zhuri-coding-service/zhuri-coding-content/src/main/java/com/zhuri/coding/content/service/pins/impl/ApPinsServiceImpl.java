package com.zhuri.coding.content.service.pins.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.service.pins.ApPinsService;
import com.zhuri.coding.content.utils.NotificationHelper;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.common.dtos.PageResponseResult;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class ApPinsServiceImpl extends ServiceImpl<ApPinsMapper, ApPins> implements ApPinsService {

    @Autowired(required = false)
    private INotificationClient notificationClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ResponseResult findList(Integer page, Integer size, Byte status) {
        IPage<ApPins> iPage = new Page<>(page, size);
        LambdaQueryWrapper<ApPins> queryWrapper = new LambdaQueryWrapper<>();
        
        if (status != null) {
            queryWrapper.eq(ApPins::getStatus, status);
        }
        
        queryWrapper.orderByDesc(ApPins::getCreatedTime);
        
        IPage<ApPins> resultPage = page(iPage, queryWrapper);
        
        return new PageResponseResult(page, size, (int) resultPage.getTotal(), resultPage.getRecords());
    }

    @Override
    public ResponseResult deleteById(Long id) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        
        boolean deleted = removeById(id);
        
        if (deleted) {
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        }
        
        return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
    }

    @Override
    public ResponseResult updateStatus(Long id, Byte status, String reason) {
        if (id == null || status == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        
        ApPins apPins = getById(id);
        
        if (apPins == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        
        Byte oldStatus = apPins.getStatus();
        apPins.setStatus(status);
        apPins.setReason(reason);
        
        boolean updated = updateById(apPins);
        
        if (updated) {
            // 审核失败时发送通知
            if (status == ApPins.Status.FAIL.getCode()) {
                sendModerationFailNotification(apPins, reason);
            }
            // 审核通过时发送通知
            if (status == ApPins.Status.PUBLISHED.getCode() && oldStatus == ApPins.Status.SUBMIT.getCode()) {
                sendModerationPassNotification(apPins);
            }
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        }
        
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR);
    }

    /**
     * 发送审核失败通知
     */
    private void sendModerationFailNotification(ApPins pins, String reason) {
        NotificationHelper.sendModerationFailNotification(
            notificationClient,
            pins.getAuthorId(),
            String.valueOf(pins.getId()),
            "沸点",
            null,
            reason
        );
    }

    /**
     * 发送审核通过通知
     */
    private void sendModerationPassNotification(ApPins pins) {
        try {
            if (notificationClient == null) {
                log.warn("通知服务不可用，跳过发送沸点审核通过通知, pinsId={}", pins.getId());
                return;
            }

            String message = "你的沸点已通过审核，已成功发布。";

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("pinsId", String.valueOf(pins.getId()));
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");
            contentMap.put("entity_type", "沸点");

            Map<String, Object> params = new HashMap<>();
            params.put("userId", pins.getAuthorId());
            params.put("type", 4); // 系统通知
            params.put("sourceId", String.valueOf(pins.getId()));
            params.put("content", objectMapper.writeValueAsString(contentMap));

            ResponseResult result = notificationClient.createNotification(params);
            if (result != null && result.getCode() == 200) {
                log.info("沸点审核通过通知已发送, pinsId={}, authorId={}", pins.getId(), pins.getAuthorId());
            } else {
                log.warn("沸点审核通过通知发送失败, pinsId={}, result={}", pins.getId(), result);
            }
        } catch (Exception e) {
            log.error("发送沸点审核通过通知异常, pinsId={}", pins.getId(), e);
        }
    }
}