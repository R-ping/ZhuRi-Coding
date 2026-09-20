package com.zhuri.coding.content.service.pins.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhuri.coding.content.behavior.service.BehaviorEventBus;
import com.zhuri.coding.content.mapper.pins.ApPinsAuditTaskMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.audit.AuditContext;
import com.zhuri.coding.model.audit.AuditEntityType;
import com.zhuri.coding.model.audit.AuditResult;
import com.zhuri.coding.model.audit.AuditServiceUnavailableException;
import com.zhuri.coding.model.audit.pojos.ApPinsAuditTask;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.user.pojos.ApUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 沸点异步审核服务（数据库可靠队列版）
 *
 * 沸点为"先审后展"：审核任务持久化到 ap_pins_audit_task，即使服务重启/崩溃，
 * 定时补偿任务（PinsAuditRecoveryTask）也能重新拉起审核，避免沸点长期停留在"待审"状态。
 * 执行前通过 CAS（PENDING→PROCESSING）抢占，与补偿任务不会重复处理同一沸点。
 */
@Component
@Slf4j
public class PinsReviewService {

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private ApPinsAuditTaskMapper pinsAuditTaskMapper;

    @Autowired
    private PinsAuditService pinsAuditService;

    @Autowired(required = false)
    private BehaviorEventBus behaviorEventBus;

    /**
     * 沸点入队并触发一次尽快审核（兼容原调用方签名）。
     * 先持久化审核任务（重启不丢），再异步尝试执行一次。
     *
     * @param pins 已入库的沸点（SUBMIT 待审）
     * @param user 发布用户（用于审核通过后的等级积分）
     */
    public void asyncReviewPins(ApPins pins, ApUser user) {
        if (pins == null || pins.getId() == null) {
            log.warn("沸点审核入队失败：沸点或ID为空");
            return;
        }
        try {
            // 1. 幂等持久化任务（同一沸点仅一条待审核任务，唯一键 pins_id 兜底）
            ApPinsAuditTask task = new ApPinsAuditTask();
            task.setPinsId(pins.getId());
            task.setAuthorId(pins.getAuthorId() != null ? pins.getAuthorId().intValue() : 0);
            task.setAuthorName(pins.getAuthorName() != null ? pins.getAuthorName() : "");
            task.setUserId(user != null ? user.getId() : (pins.getAuthorId() != null ? pins.getAuthorId().intValue() : 0));
            task.setContent(pins.getContent() != null ? pins.getContent() : "");
            task.setImageUrls(pins.getImageUrls() != null ? pins.getImageUrls() : "");
            task.setStatus(ApPinsAuditTask.STATUS_PENDING);
            task.setRetryCount(0);
            task.setNextRetryTime(new Date());
            task.setCreateTime(new Date());
            task.setUpdateTime(new Date());
            try {
                pinsAuditTaskMapper.insert(task);
            } catch (DuplicateKeyException e) {
                log.info("沸点审核任务已存在，跳过重复入队, pinsId={}", pins.getId());
            }

            // 2. 立即触发一次审核（进程内触发与定时补偿共用 CAS 抢占）
            processTaskIfPending(task.getId());
            log.info("沸点已加入数据库可靠审核队列, pinsId={}", pins.getId());
        } catch (Exception e) {
            log.error("触发沸点异步审核入队异常, pinsId={}", pins.getId(), e);
        }
    }

    /**
     * 若任务仍处于待审核则抢占执行审核（CAS，避免与定时补偿重复处理）。
     * 供进程内直接触发与定时补偿任务共同调用。
     *
     * @param taskId 任务ID
     */
    public void processTaskIfPending(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            // CAS：仅当 status=PENDING 才抢占为 PROCESSING，保证同一沸点只被一个执行体处理
            boolean acquired = pinsAuditTaskMapper.update(null, new LambdaUpdateWrapper<ApPinsAuditTask>()
                .eq(ApPinsAuditTask::getId, taskId)
                .eq(ApPinsAuditTask::getStatus, ApPinsAuditTask.STATUS_PENDING)
                .set(ApPinsAuditTask::getStatus, ApPinsAuditTask.STATUS_PROCESSING)
                .set(ApPinsAuditTask::getUpdateTime, new Date())) > 0;
            if (!acquired) {
                return;
            }

            ApPinsAuditTask task = pinsAuditTaskMapper.selectById(taskId);
            if (task == null) {
                return;
            }

            log.info("开始审核沸点, pinsId={}, taskId={}", task.getPinsId(), taskId);
            AuditResult result = doAudit(task.getPinsId(), task.getUserId());

            if (result.isPassed()) {
                markDone(taskId, ApPinsAuditTask.STATUS_PASSED);
                log.info("沸点审核通过, pinsId={}", task.getPinsId());
            } else {
                markDone(taskId, ApPinsAuditTask.STATUS_VIOLATION);
                log.info("沸点审核违规, pinsId={}, reason={}", task.getPinsId(), result.getReason());
            }
        } catch (AuditServiceUnavailableException e) {
            // 审核服务不可用（fail-closed）：恢复正常任务状态并退避重试，不误标违规
            log.warn("沸点审核服务不可用，安排退避重试, taskId={}", taskId, e);
            retryOrDegrade(taskId);
        } catch (Exception e) {
            log.error("沸点审核执行异常, taskId={}", taskId, e);
            retryOrDegrade(taskId);
        }
    }

    /** 依据队列数据执行一次真实审核，返回审核结果 */
    private AuditResult doAudit(Long pinsId, Integer userId) {
        ApPins pins = apPinsMapper.selectById(pinsId);
        if (pins == null) {
            log.warn("沸点不存在, pinsId={}", pinsId);
            return AuditResult.failed("沸点不存在");
        }

        AuditContext auditContext = new AuditContext(AuditEntityType.PINS, pinsId, pins.getAuthorId());
        auditContext.withTitle("")
            .withContent(pins.getContent())
            .withAuthorName(pins.getAuthorName());

        if (pins.getImageUrls() != null && !pins.getImageUrls().isEmpty()) {
            List<String> imageUrls = Arrays.stream(pins.getImageUrls().split(","))
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .collect(Collectors.toList());
            auditContext.withImageUrls(imageUrls);
        }

        return pinsAuditService.audit(auditContext);
    }

    /** 恢复任务为待审核并按指数退避安排重试时间；超限则降级通过 */
    private void retryOrDegrade(Long taskId) {
        ApPinsAuditTask task = pinsAuditTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        if (retry > ApPinsAuditTask.MAX_RETRY) {
            pinsAuditTaskMapper.update(null, new LambdaUpdateWrapper<ApPinsAuditTask>()
                .eq(ApPinsAuditTask::getId, taskId)
                .set(ApPinsAuditTask::getStatus, ApPinsAuditTask.STATUS_DEGRADED_PASSED)
                .set(ApPinsAuditTask::getRetryCount, retry)
                .set(ApPinsAuditTask::getAuditTime, new Date())
                .set(ApPinsAuditTask::getUpdateTime, new Date()));
            log.warn("沸点审核重试超限，降级通过, taskId={}, pinsId={}", taskId, task.getPinsId());
            return;
        }
        // 指数退避：60s -> 120s -> 240s ...
        long backoffMillis = 60_000L * (1L << (retry - 1));
        Date next = new Date(System.currentTimeMillis() + backoffMillis);
        pinsAuditTaskMapper.update(null, new LambdaUpdateWrapper<ApPinsAuditTask>()
            .eq(ApPinsAuditTask::getId, taskId)
            .eq(ApPinsAuditTask::getStatus, ApPinsAuditTask.STATUS_PROCESSING)
            .set(ApPinsAuditTask::getStatus, ApPinsAuditTask.STATUS_PENDING)
            .set(ApPinsAuditTask::getRetryCount, retry)
            .set(ApPinsAuditTask::getNextRetryTime, next)
            .set(ApPinsAuditTask::getUpdateTime, new Date()));
        log.warn("沸点审核执行异常，安排退避重试, taskId={}, retryCount={}", taskId, retry);
    }

    /** 标记任务完成 */
    private void markDone(Long taskId, int status) {
        pinsAuditTaskMapper.update(null, new LambdaUpdateWrapper<ApPinsAuditTask>()
            .eq(ApPinsAuditTask::getId, taskId)
            .set(ApPinsAuditTask::getStatus, status)
            .set(ApPinsAuditTask::getAuditTime, new Date())
            .set(ApPinsAuditTask::getUpdateTime, new Date()));

        ApPinsAuditTask task = pinsAuditTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        // 审核通过后触发等级积分（PinsAuditService.handlePassed 已把沸点置为 PUBLISHED）
        if (status == ApPinsAuditTask.STATUS_PASSED && behaviorEventBus != null && task.getUserId() != null) {
            try {
                ApPins pins = apPinsMapper.selectById(task.getPinsId());
                BehaviorContext behaviorContext = new BehaviorContext(BehaviorType.PUBLISH_PIN, task.getUserId());
                behaviorContext.withTarget(2, task.getPinsId())
                    .withUserInfo(pins != null ? pins.getAuthorName() : null, pins != null ? pins.getAuthorImage() : null);
                behaviorEventBus.execute(behaviorContext);
                log.info("沸点发布行为已通过事件总线处理, pinsId={}, userId={}", task.getPinsId(), task.getUserId());
            } catch (Exception e) {
                log.error("沸点发布行为事件处理失败, pinsId={}", task.getPinsId(), e);
            }
        }
    }

    /** 供定时补偿任务批量拉取待审核任务（按 next_retry_time 到期排序） */
    public List<ApPinsAuditTask> listPendingDue(int limit) {
        return pinsAuditTaskMapper.selectList(new LambdaQueryWrapper<ApPinsAuditTask>()
            .eq(ApPinsAuditTask::getStatus, ApPinsAuditTask.STATUS_PENDING)
            .and(w -> w.isNull(ApPinsAuditTask::getNextRetryTime).or()
                .le(ApPinsAuditTask::getNextRetryTime, new Date()))
            .orderByAsc(ApPinsAuditTask::getId)
            .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }
}