package com.zhuri.coding.content.service.outbox.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhuri.coding.content.mapper.outbox.OutboxEventMapper;
import com.zhuri.coding.content.service.outbox.OutboxService;
import com.zhuri.coding.model.outbox.pojos.OutboxEvent;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class OutboxServiceImpl implements OutboxService {

    /** 指数退避封顶间隔（分钟） */
    static final long MAX_BACKOFF_MINUTES = 60;

    /** last_error 入库最大长度 */
    private static final int MAX_ERROR_LENGTH = 500;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Override
    public boolean record(String eventKey, String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventKey(eventKey);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setStatus(OutboxEvent.STATUS_PENDING);
        event.setRetryCount(0);
        event.setMaxRetries(defaultMaxRetries());
        event.setCreatedTime(new Date());
        event.setUpdatedTime(new Date());
        try {
            outboxEventMapper.insert(event);
            log.info("Outbox 事件已写入(同事务): eventKey={}, eventType={}", eventKey, eventType);
            return true;
        } catch (DuplicateKeyException e) {
            // uk_event_key 冲突 = 同一业务事件已写过（支付宝重复回调等），幂等短路不阻断主流程
            log.info("Outbox 事件幂等短路（event_key 已存在）: eventKey={}", eventKey);
            return false;
        }
    }

    @Override
    public void markDone(Long eventId) {
        outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, eventId)
                .set(OutboxEvent::getStatus, OutboxEvent.STATUS_DONE)
                .set(OutboxEvent::getUpdatedTime, new Date()));
    }

    @Override
    public void markFailed(OutboxEvent event, String errorReason) {
        int current = event.getRetryCount() == null ? 0 : event.getRetryCount();
        int max = event.getMaxRetries() == null || event.getMaxRetries() <= 0
                ? defaultMaxRetries() : event.getMaxRetries();
        int next = current + 1;
        Date now = new Date();

        if (next >= max) {
            // 重试超限 → 死信，人工介入（Dispatcher 侧同时打 ERROR 与指标）
            outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                    .eq(OutboxEvent::getId, event.getId())
                    .set(OutboxEvent::getStatus, OutboxEvent.STATUS_DEAD)
                    .set(OutboxEvent::getRetryCount, next)
                    .set(OutboxEvent::getLastError, truncate(errorReason))
                    .set(OutboxEvent::getUpdatedTime, now));
            log.error("[OUTBOX-DEAD] 事件重试超限置为死信，需人工介入: id={}, eventKey={}, type={}, retries={}/{}",
                    event.getId(), event.getEventKey(), event.getEventType(), next, max);
            return;
        }

        // 指数退避：2^next 分钟，封顶 60 分钟（1,2,4,8,...,60,60,...）
        long backoffMinutes = (long) Math.min(Math.pow(2, next), MAX_BACKOFF_MINUTES);
        Date nextRetryAt = new Date(now.getTime() + backoffMinutes * 60_000L);
        outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, event.getId())
                .set(OutboxEvent::getStatus, OutboxEvent.STATUS_PENDING)
                .set(OutboxEvent::getRetryCount, next)
                .set(OutboxEvent::getNextRetryAt, nextRetryAt)
                .set(OutboxEvent::getLastError, truncate(errorReason))
                .set(OutboxEvent::getUpdatedTime, now));
        log.warn("Outbox 事件执行失败，第 {} 次重试已排程(+{}min): id={}, eventKey={}",
                next, backoffMinutes, event.getId(), event.getEventKey());
    }

    /** 写库时统一缺省重试上限（与建表 DEFAULT 5 一致） */
    static int defaultMaxRetries() {
        return 5;
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
    }
}
