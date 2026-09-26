package com.zhuri.coding.content.service.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhuri.coding.content.mapper.audit.ApAuditTaskMapper;
import com.zhuri.coding.model.audit.AuditResult;
import com.zhuri.coding.model.audit.AuditServiceUnavailableException;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一审核任务调度器。
 *
 * <p><b>存在理由</b>：三个审核业务原先各写了一份完全相同的调度代码 ——
 * CAS 抢占、指数退避、超限降级放行、补偿扫描，共 4 类逻辑 × 3 份实现。
 * 表合并（{@code ap_audit_task}）只消除了字段/状态机/索引的重复定义，
 * **调度逻辑的重复要靠本类消除**；测试也随之从 3 份变 1 份。
 *
 * <p><b>职责边界</b>：本类只管"怎么调度"，不管"怎么审"。业务的差异通过 {@link AuditTaskHandler}
 * 注入 —— 包括审核对象取快照还是回查业务表、以及终态后的业务动作。
 *
 * <p><b>多实例安全</b>：执行前用单条 CAS（{@code UPDATE ... SET status=PROCESSING WHERE id=? AND status=PENDING}）
 * 抢占，不需要分布式锁；进程内直接触发与定时补偿共用同一抢占，天然去重。
 *
 * <p><b>失败语义</b>：审核执行异常按 {@code 60s × 2ⁿ} 退避重试；重试超过 {@link ApAuditTask#MAX_RETRY}
 * 则**降级放行**（保持内容原状）——系统故障不该造成不可逆的用户伤害。
 * 审核服务不可用（{@link AuditServiceUnavailableException}）走同一路径，**不误标违规**。
 */
@Component
@Slf4j
public class AuditTaskDispatcher {

    /** 单批扫描上限（防止一次捞过多造成长事务/内存压力） */
    static final int MAX_SCAN_LIMIT = 500;

    private final ApAuditTaskMapper auditTaskMapper;

    /**
     * bizType → Handler 索引。
     *
     * <p>刻意采用**自注册**，而不是构造器注入 {@code List<AuditTaskHandler>}：
     * Handler 的实现类（各审核 Service）本身需要用本 Dispatcher 来入队与触发，
     * 若本类反过来在构造期注入 Handler，就会形成循环依赖（Spring Boot 2.6+ 默认拒绝启动）。
     * 自注册保持依赖单向：Service → Dispatcher。
     */
    private final Map<String, AuditTaskHandler> handlerIndex = new ConcurrentHashMap<>();

    @Autowired
    public AuditTaskDispatcher(ApAuditTaskMapper auditTaskMapper) {
        this.auditTaskMapper = auditTaskMapper;
    }

    /**
     * 注册处理器（由各 Handler 在自身 {@code @PostConstruct} 中调用）。
     *
     * <p>同一 bizType 重复注册直接抛异常 —— **启动期 Fail-Fast**，
     * 避免静默覆盖导致任务被错误地交给另一个业务的处理器执行。
     */
    public void register(AuditTaskHandler handler) {
        AuditTaskHandler previous = handlerIndex.putIfAbsent(handler.bizType(), handler);
        if (previous != null) {
            throw new IllegalStateException("Duplicate AuditTaskHandler bizType=" + handler.bizType()
                    + "（已注册 " + previous.getClass().getSimpleName()
                    + "，又注册 " + handler.getClass().getSimpleName() + "）");
        }
        log.info("AuditTaskHandler 已注册: bizType={}, handler={}",
                handler.bizType(), handler.getClass().getSimpleName());
    }

    /**
     * 幂等入队：写入任务表，唯一键 {@code uk_task_key} 冲突视为"同业务已有在途任务"，静默跳过。
     *
     * <p>调用方负责在**业务主事务内**构造并传入任务，使"业务落库 + 任务落库"原子提交。
     */
    public void enqueue(ApAuditTask task) {
        try {
            auditTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            log.info("审核任务已存在，跳过重复入队, taskKey={}", task.getTaskKey());
        }
    }

    /**
     * CAS 抢占后执行审核，并按结果收尾（通过 / 违规 / 退避重试 / 超限降级）。
     * 供进程内直接触发与定时补偿任务共同调用。
     *
     * @param taskId 任务ID
     */
    public void processIfPending(Long taskId) {
        if (taskId == null) {
            return;
        }
        // 声明在 try 之外：异常分支需要按 Handler 触发 onDegraded 回调
        AuditTaskHandler handler = null;
        try {
            if (!casClaim(taskId)) {
                return; // 已被其它执行体抢走
            }
            ApAuditTask task = auditTaskMapper.selectById(taskId);
            if (task == null) {
                return;
            }
            handler = handlerIndex.get(task.getBizType());
            if (handler == null) {
                // 配置错误（任务表里有业务类型但没注册处理器）：按失败走重试/降级，ERROR 暴露
                log.error("[AUDIT] 未找到处理器: bizType={}, taskId={}", task.getBizType(), taskId);
                retryOrDegrade(taskId, null, "No handler for bizType=" + task.getBizType());
                return;
            }

            log.info("开始审核, bizType={}, bizId={}, taskId={}", task.getBizType(), task.getBizId(), taskId);
            AuditResult result = handler.audit(task);

            if (result.isPassed()) {
                markDone(taskId, ApAuditTask.STATUS_PASSED);
                log.info("审核通过, bizType={}, bizId={}", task.getBizType(), task.getBizId());
                safeCallback(handler, task, CallbackKind.PASSED);
            } else {
                markDone(taskId, ApAuditTask.STATUS_VIOLATION);
                log.info("审核违规, bizType={}, bizId={}, reason={}",
                        task.getBizType(), task.getBizId(), result.getReason());
                safeCallback(handler, task, CallbackKind.VIOLATION);
            }
        } catch (AuditServiceUnavailableException e) {
            // 审核服务不可用（fail-closed）：退回待审并退避重试，不误标违规
            log.warn("审核服务不可用，安排退避重试, taskId={}", taskId, e);
            retryOrDegrade(taskId, handler, null);
        } catch (Exception e) {
            log.error("审核执行异常, taskId={}", taskId, e);
            retryOrDegrade(taskId, handler, e.getMessage());
        }
    }

    /**
     * 按业务类型拉取到期待处理任务（供定时补偿）。
     *
     * <p>刻意**按 bizType 隔离**：三类业务共用一张表，若不隔离，
     * 任务量大的业务会把批次配额吃光，导致其它业务的任务迟迟得不到处理。
     */
    public List<ApAuditTask> listPendingDue(String bizType, int limit) {
        return auditTaskMapper.selectList(new LambdaQueryWrapper<ApAuditTask>()
                .eq(ApAuditTask::getBizType, bizType)
                .eq(ApAuditTask::getStatus, ApAuditTask.STATUS_PENDING)
                .and(w -> w.isNull(ApAuditTask::getNextRetryTime)
                        .or().le(ApAuditTask::getNextRetryTime, new Date()))
                .orderByAsc(ApAuditTask::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, MAX_SCAN_LIMIT))));
    }

    /** CAS 抢占：仅当仍是待审核才置为审核中，返回是否抢到 */
    private boolean casClaim(Long taskId) {
        return auditTaskMapper.update(null, new LambdaUpdateWrapper<ApAuditTask>()
                .eq(ApAuditTask::getId, taskId)
                .eq(ApAuditTask::getStatus, ApAuditTask.STATUS_PENDING)
                .set(ApAuditTask::getStatus, ApAuditTask.STATUS_PROCESSING)
                .set(ApAuditTask::getUpdateTime, new Date())) > 0;
    }

    /** 标记任务完成（通过 / 违规） */
    private void markDone(Long taskId, int status) {
        Date now = new Date();
        auditTaskMapper.update(null, new LambdaUpdateWrapper<ApAuditTask>()
                .eq(ApAuditTask::getId, taskId)
                .set(ApAuditTask::getStatus, status)
                .set(ApAuditTask::getAuditTime, now)
                .set(ApAuditTask::getUpdateTime, now));
    }

    /**
     * 失败处理：未达上限则退回待审并按指数退避排程；达到上限则**降级放行**并触发 onDegraded。
     *
     * @param handler     业务处理器；为 null 时只改状态不回调（如未注册处理器的配置错误）
     * @param errorReason 失败原因，仅用于日志（任务表未设 last_error 字段）
     */
    private void retryOrDegrade(Long taskId, AuditTaskHandler handler, String errorReason) {
        ApAuditTask task = auditTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        Date now = new Date();

        if (retry > ApAuditTask.MAX_RETRY) {
            auditTaskMapper.update(null, new LambdaUpdateWrapper<ApAuditTask>()
                    .eq(ApAuditTask::getId, taskId)
                    .set(ApAuditTask::getStatus, ApAuditTask.STATUS_DEGRADED_PASSED)
                    .set(ApAuditTask::getRetryCount, retry)
                    .set(ApAuditTask::getAuditTime, now)
                    .set(ApAuditTask::getUpdateTime, now));
            log.warn("审核重试超限，降级放行, taskId={}, bizType={}, bizId={}, retries={}",
                    taskId, task.getBizType(), task.getBizId(), retry);
            if (handler != null) {
                safeCallback(handler, task, CallbackKind.DEGRADED);
            }
            return;
        }

        // 指数退避：60s -> 120s -> 240s ...
        long backoffMillis = 60_000L * (1L << (retry - 1));
        Date nextRetryTime = new Date(System.currentTimeMillis() + backoffMillis);
        auditTaskMapper.update(null, new LambdaUpdateWrapper<ApAuditTask>()
                .eq(ApAuditTask::getId, taskId)
                .eq(ApAuditTask::getStatus, ApAuditTask.STATUS_PROCESSING)
                .set(ApAuditTask::getStatus, ApAuditTask.STATUS_PENDING)
                .set(ApAuditTask::getRetryCount, retry)
                .set(ApAuditTask::getNextRetryTime, nextRetryTime)
                .set(ApAuditTask::getUpdateTime, now));
        log.warn("审核执行异常，安排退避重试, taskId={}, retryCount={}, reason={}",
                taskId, retry, errorReason);
    }

    /** 回调类型 */
    private enum CallbackKind {
        PASSED, VIOLATION, DEGRADED
    }

    /**
     * 执行终态回调。**回调异常不回滚已落库的终态** ——
     * 任务状态是调度的事实来源，业务动作失败应通过自身的补偿机制修复，而不是让任务卡在中间态。
     */
    private void safeCallback(AuditTaskHandler handler, ApAuditTask task, CallbackKind kind) {
        try {
            switch (kind) {
                case PASSED -> handler.onPassed(task);
                case VIOLATION -> handler.onViolation(task);
                case DEGRADED -> handler.onDegraded(task);
            }
        } catch (Exception e) {
            log.error("[AUDIT] {} 回调异常（终态已落库，不做回滚）: bizType={}, bizId={}, taskId={}",
                    kind, task.getBizType(), task.getBizId(), task.getId(), e);
        }
    }
}
