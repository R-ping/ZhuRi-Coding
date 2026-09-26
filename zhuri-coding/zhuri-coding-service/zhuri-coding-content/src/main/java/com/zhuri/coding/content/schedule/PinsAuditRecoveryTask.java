package com.zhuri.coding.content.schedule;

import com.zhuri.coding.content.service.pins.impl.PinsReviewService;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 沸点异步审核补偿任务
 *
 * 作为统一审核任务表 {@code ap_audit_task}（bizType=pins）的兜底扫描器：
 * - 服务重启/崩溃导致审核未执行时，这里重新拉起仍待审核的沸点
 * - 处理异常进入退避重试的任务，到期后也由这里重新执行
 * 与进程内直接触发共用【CAS 抢占】，不会重复处理同一沸点。
 *
 * <p>迁移说明（2026-09-26）：原扫描独立表 {@code ap_pins_audit_task}，
 * 现按 bizType 过滤统一表，与其他业务的扫描器互不干扰。
 */
@Slf4j
@Component
public class PinsAuditRecoveryTask {

    @Autowired
    private PinsReviewService pinsReviewService;

    /** 单批扫描上限 */
    private static final int BATCH = 50;

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void recoverPendingAuditTasks() {
        try {
            List<ApAuditTask> tasks = pinsReviewService.listPendingDue(BATCH);
            if (tasks == null || tasks.isEmpty()) {
                return;
            }
            log.info("沸点审核补偿扫描到待审核任务 {} 条", tasks.size());
            for (ApAuditTask task : tasks) {
                try {
                    pinsReviewService.processTaskIfPending(task.getId());
                } catch (Exception e) {
                    log.error("沸点审核补偿处理异常, taskId={}", task.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("沸点审核补偿任务扫描异常", e);
        }
    }
}
