package com.zhuri.coding.content.service.pins.impl;

import com.zhuri.coding.content.behavior.service.BehaviorEventBus;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.service.audit.AuditTaskDispatcher;
import com.zhuri.coding.content.service.audit.AuditTaskHandler;
import com.zhuri.coding.model.audit.AuditContext;
import com.zhuri.coding.model.audit.AuditEntityType;
import com.zhuri.coding.model.audit.AuditResult;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.user.pojos.ApUser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 沸点异步审核服务（数据库可靠队列版）。
 *
 * <p>沸点为"先审后展"：审核任务持久化到统一任务表 {@code ap_audit_task}（bizType=pins），
 * 即使服务重启/崩溃，定时补偿任务（PinsAuditRecoveryTask）也能重新拉起审核，
 * 避免沸点长期停留在"待审"状态。
 *
 * <p><b>本类现在只负责"业务差异"，调度机制交给 {@link AuditTaskDispatcher}</b>：
 * CAS 抢占、指数退避、超限降级放行、幂等入队、补偿扫描都已在 Dispatcher 内实现一份，
 * 三个审核业务共用。这里只提供三件业务相关的事：
 * <ol>
 *   <li>{@link #audit(ApAuditTask)} —— 沸点审核**回查沸点表取最新内容**
 *       （与评论类读任务表快照的做法不同，故任务表里的 content 对本业务仅为留档）；</li>
 *   <li>{@link #onPassed(ApAuditTask)} —— 通过后触发"发布沸点"等级积分事件；</li>
 *   <li>{@link #asyncReviewPins} —— 入队并尽快触发一次。</li>
 * </ol>
 *
 * <p><b>迁移历史</b>：2026-09-26 由独立表 {@code ap_pins_audit_task} 并入统一表；
 * 同期抽出的 Dispatcher 消除了原先散落在三个 Service 里的重复调度逻辑。
 */
@Component
@Slf4j
public class PinsReviewService implements AuditTaskHandler {

    /** 本服务负责的业务类型（统一表 ap_audit_task 内按此值路由） */
    private static final String BIZ_TYPE = ApAuditTask.BIZ_PINS;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private AuditTaskDispatcher auditTaskDispatcher;

    @Autowired
    private PinsAuditService pinsAuditService;

    @Autowired(required = false)
    private BehaviorEventBus behaviorEventBus;

    /** 自注册到调度器：保持依赖单向（Service → Dispatcher），避免与 Dispatcher 的集合注入形成循环依赖 */
    @PostConstruct
    void registerSelf() {
        auditTaskDispatcher.register(this);
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }

    /**
     * 沸点入队并触发一次尽快审核（兼容原调用方签名）。
     * 先持久化审核任务（重启不丢），再尝试执行一次。
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
            ApAuditTask task = new ApAuditTask();
            task.setTaskKey(ApAuditTask.taskKey(BIZ_TYPE, pins.getId()));
            task.setBizType(BIZ_TYPE);
            task.setBizId(pins.getId());
            task.setAuthorId(pins.getAuthorId() != null ? pins.getAuthorId().intValue() : 0);
            task.setAuthorName(pins.getAuthorName() != null ? pins.getAuthorName() : "");
            task.setActorUserId(user != null
                    ? user.getId()
                    : (pins.getAuthorId() != null ? pins.getAuthorId().intValue() : 0));
            task.setContent(pins.getContent() != null ? pins.getContent() : "");
            task.setImageUrls(pins.getImageUrls() != null ? pins.getImageUrls() : "");
            task.setStatus(ApAuditTask.STATUS_PENDING);
            task.setRetryCount(0);
            task.setNextRetryTime(new Date());
            task.setCreateTime(new Date());
            task.setUpdateTime(new Date());

            // 幂等写入（同一沸点仅一条任务，唯一键 uk_task_key 兜底）→ 立即触发一次
            auditTaskDispatcher.enqueue(task);
            auditTaskDispatcher.processIfPending(task.getId());
            log.info("沸点已加入数据库可靠审核队列, pinsId={}", pins.getId());
        } catch (Exception e) {
            log.error("触发沸点异步审核入队异常, pinsId={}", pins.getId(), e);
        }
    }

    /**
     * 执行一次沸点审核。
     *
     * <p>刻意**回查沸点表**取最新内容（而非读任务表里的 content 快照）：
     * 沸点内容在待审窗口内可能被作者编辑，审核对象应是当前内容。
     */
    @Override
    public AuditResult audit(ApAuditTask task) {
        Long pinsId = task.getBizId();
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

    /**
     * 审核通过后的业务动作：触发"发布沸点"等级积分事件。
     * （审核服务本身已把沸点置为 PUBLISHED，这里只处理积分联动）
     */
    @Override
    public void onPassed(ApAuditTask task) {
        if (behaviorEventBus == null || task.getActorUserId() == null) {
            return;
        }
        try {
            ApPins pins = apPinsMapper.selectById(task.getBizId());
            BehaviorContext behaviorContext =
                    new BehaviorContext(BehaviorType.PUBLISH_PIN, task.getActorUserId());
            behaviorContext.withTarget(2, task.getBizId())
                    .withUserInfo(pins != null ? pins.getAuthorName() : null,
                            pins != null ? pins.getAuthorImage() : null);
            behaviorEventBus.execute(behaviorContext);
            log.info("沸点发布行为已通过事件总线处理, pinsId={}, userId={}",
                    task.getBizId(), task.getActorUserId());
        } catch (Exception e) {
            log.error("沸点发布行为事件处理失败, pinsId={}", task.getBizId(), e);
        }
    }

    /**
     * 供定时补偿任务批量拉取待审核任务（委托 Dispatcher，保留原签名以减少调用方改动）。
     * Dispatcher 内部按 bizType 隔离，避免与其他业务抢占批次配额。
     */
    public List<ApAuditTask> listPendingDue(int limit) {
        return auditTaskDispatcher.listPendingDue(BIZ_TYPE, limit);
    }

    /**
     * 触发一次审核（委托 Dispatcher）。
     * 供进程内直接触发与定时补偿任务共同调用，CAS 抢占保证不重复处理。
     */
    public void processTaskIfPending(Long taskId) {
        auditTaskDispatcher.processIfPending(taskId);
    }
}
