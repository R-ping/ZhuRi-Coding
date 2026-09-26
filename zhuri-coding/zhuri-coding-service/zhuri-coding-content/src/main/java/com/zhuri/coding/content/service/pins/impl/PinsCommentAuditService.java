package com.zhuri.coding.content.service.pins.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.mapper.pins.ApPinsCommentMapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.article.impl.AbstractAuditService;
import com.zhuri.coding.content.service.audit.AuditTaskDispatcher;
import com.zhuri.coding.content.service.audit.AuditTaskHandler;
import com.zhuri.coding.content.utils.NotificationHelper;
import com.zhuri.coding.model.audit.AuditContext;
import com.zhuri.coding.model.audit.AuditEntityType;
import com.zhuri.coding.model.audit.AuditResult;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import com.zhuri.coding.model.behavior.pojos.UserBehaviorRecord;
import com.zhuri.coding.model.pins.pojos.ApPinsComment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 沸点评论异步审核服务（数据库可靠队列版）
 *
 * 与 {@link com.zhuri.coding.content.service.comment.impl.CommentAuditService}（文章评论）同构，
 * 差异在终态动作：沸点评论的"新评论"通知是**创建即通知**（评论对作者可见时已发送），
 * 审核链路不补发任何通知，因此无需实现 {@link AuditTaskHandler#onDegraded}。
 *
 * 审核策略：先展示后审核
 * 1. 沸点评论发布时立即保存到数据库（用户可见），并向沸点作者发送"新评论"通知（创建即通知，既有行为保持）
 * 2. 审核任务持久化到统一任务表 ap_audit_task（bizType=pins_comment），后台异步（延迟约 5-10 秒）执行审核
 * 3. 红线违规 → 物理删除评论 + 给评论者发违规系统通知
 * 4. 温和违规（引战/阴阳/软广）→ is_hidden=1 折叠：全局隐藏（对所有人含本人不可见，数据保留可审计）
 *
 * 可靠性保证：任务落库 + CAS 抢占 + 指数退避重试 + 超限降级放行（系统故障不误删正常评论）
 * —— 这些调度语义现由 {@link AuditTaskDispatcher} 统一提供，三个审核业务共用一份实现。
 *
 * <p><b>审核对象语义</b>：读**任务表里的内容快照**（与文章评论一致，与沸点审核的"回查业务表"不同）。
 *
 * <p><b>迁移历史</b>：2026-09-26 由独立表 {@code ap_pins_comment_audit_task} 并入统一表。
 */
@Slf4j
@Service
public class PinsCommentAuditService extends AbstractAuditService implements AuditTaskHandler {

    /** 本服务负责的业务类型（统一表 ap_audit_task 内按此值路由） */
    private static final String BIZ_TYPE = ApAuditTask.BIZ_PINS_COMMENT;

    /** 尽快执行触发的延迟窗口下界（毫秒） */
    private static final long TRIGGER_DELAY_MIN_MILLIS = 5_000L;
    /** 延迟窗口的随机上浮范围（毫秒），避免同批评论同时触发审核 */
    private static final long TRIGGER_DELAY_JITTER_MILLIS = 5_000L;

    @Autowired
    private ApPinsCommentMapper pinsCommentMapper;

    @Autowired
    private AuditTaskDispatcher auditTaskDispatcher;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    /**
     * 统一 LLM 出口（安全横切 + token 计量）。折叠判定是"尽力而为"的温和治理：
     * 模型未装配（如未配置 Key 的环境/单测上下文）或调用异常时一律放行，不影响审核主链路。
     * 模型选择交由网关内的 AiModelRouter 按 feature 路由（pins_comment_audit → 低成本模型）。
     */
    @Autowired
    private AiLlmGateway llmGateway;

    /**
     * 「尽快执行」触发专用池。
     * 原先直接使用 {@code CompletableFuture.delayedExecutor(delay, unit)}，其任务体会落到 JVM 公共
     * ForkJoinPool，而审核任务内部含 LLM 调用（秒级阻塞），会拖累公共池里的其它任务。
     */
    @Autowired
    @Qualifier("aiAuditTriggerExecutor")
    private Executor auditTriggerExecutor;

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
     * 沸点评论入队并触发异步审核（延迟约 5-10 秒）
     * 兼容原有调用方签名；仅持久化任务并请求一次尽快处理。
     */
    @Async
    public void asyncAuditComment(AuditContext context) {
        if (context == null || context.getEntityId() == null) {
            log.warn("沸点评论审核入队失败：上下文或评论ID为空");
            return;
        }
        Long commentId = context.getEntityId();
        try {
            // 1. 幂等持久化任务（同一条评论仅一条任务，唯一键 uk_task_key 兜底）
            ApAuditTask task = new ApAuditTask();
            task.setTaskKey(ApAuditTask.taskKey(BIZ_TYPE, commentId));
            task.setBizType(BIZ_TYPE);
            task.setBizId(commentId);
            task.setAuthorId(context.getUserId() != null ? context.getUserId() : 0);
            task.setAuthorName(context.getAuthorName() != null ? context.getAuthorName() : "");
            task.setContent(context.getContent() != null ? context.getContent() : "");
            task.setTargetType(context.getTargetType() != null ? context.getTargetType() : 2);
            task.setTargetId(context.getTargetId());
            task.setTargetUserId(context.getTargetUserId());
            task.setStatus(ApAuditTask.STATUS_PENDING);
            task.setRetryCount(0);
            task.setNextRetryTime(new Date());
            task.setCreateTime(new Date());
            task.setUpdateTime(new Date());
            auditTaskDispatcher.enqueue(task);

            // 2. 请求一次尽快执行（延迟窗口配置，与"先展示后审核"窗口保持一致）
            //    延迟仍由 delayedExecutor 负责，仅把任务体投给专用有界池（不再落到 JVM 公共池）
            long delay = TRIGGER_DELAY_MIN_MILLIS + (long) (Math.random() * TRIGGER_DELAY_JITTER_MILLIS);
            final Long taskId = task.getId();
            CompletableFuture.runAsync(
                () -> auditTaskDispatcher.processIfPending(taskId),
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, auditTriggerExecutor))
                .exceptionally(e -> {
                    // 池拒绝/任务异常：只记录。真正的兜底是定时补偿（PinsCommentAuditRecoveryTask）
                    log.warn("沸点评论审核尽快执行触发失败，将由定时补偿拉起, commentId={}", commentId, e);
                    return null;
                });

            log.info("沸点评论已加入数据库可靠审核队列, commentId={}, pinsId={}", commentId, context.getTargetId());
        } catch (Exception e) {
            log.error("触发沸点评论异步审核入队异常, commentId={}", commentId, e);
        }
    }

    /**
     * 依据队列数据重建审核上下文并执行审核。
     *
     * <p>通过 / 违规后的业务动作（折叠判定、删评论、撤销行为记录、发通知）由父类模板方法完成，
     * 本方法只负责把任务表里的字段还原成 {@link AuditContext}。
     */
    @Override
    public AuditResult audit(ApAuditTask task) {
        AuditContext context = new AuditContext(
                AuditEntityType.COMMENT, task.getBizId(),
                task.getAuthorId() == null ? null : task.getAuthorId().longValue());
        context.withTitle("")
                .withContent(task.getContent())
                .withAuthorName(task.getAuthorName())
                .withUserId(task.getAuthorId())
                .withTargetType(task.getTargetType())
                .withTargetId(task.getTargetId())
                .withTargetUserId(task.getTargetUserId());
        return audit(context);
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

    @Override
    protected void handlePassed(AuditContext context) {
        // 温和治理：红线违规已在 audit 中删除；此处对"通过"评论追加温和判定（引战/阴阳/软广）→ 折叠隐藏
        if (judgeCommentHidden(context.getEntityId(), context.getContent())) {
            ApPinsComment c = pinsCommentMapper.selectById(context.getEntityId());
            if (c != null) {
                c.setIsHidden(1);
                pinsCommentMapper.updateById(c);
                log.info("沸点评论AI社区治理折叠, commentId={}", context.getEntityId());
            }
            return;
        }
        // 沸点评论通知为"创建即通知"（评论对沸点作者可见时已发送），审核回调无需再发任何通知
        log.info("沸点评论审核通过(可见): commentId={}, pinsId={}", context.getEntityId(), context.getTargetId());
    }

    @Override
    protected void handleFailed(AuditContext context, String reason) {
        // 红线违规：物理删除评论 + 撤销行为记录 + 发送违规系统通知给评论者
        ApPinsComment comment = pinsCommentMapper.selectById(context.getEntityId());
        if (comment == null) {
            return;
        }

        pinsCommentMapper.deleteById(comment.getId());

        // 更新行为记录状态为已撤销（沸点评论行为类型固定 comment_pin，targetType=2）
        if (context.getUserId() != null) {
            try {
                LambdaQueryWrapper<UserBehaviorRecord> query = new LambdaQueryWrapper<>();
                query.eq(UserBehaviorRecord::getUserId, context.getUserId());
                query.eq(UserBehaviorRecord::getBehaviorType, "comment_pin");
                query.eq(UserBehaviorRecord::getTargetId, context.getTargetId());
                query.eq(UserBehaviorRecord::getStatus, 1);
                UserBehaviorRecord record = behaviorRecordMapper.selectOne(query);
                if (record != null) {
                    record.setStatus(0);
                    behaviorRecordMapper.updateById(record);
                }
            } catch (Exception e) {
                log.warn("撤销沸点评论行为记录异常, commentId={}", comment.getId(), e);
            }
        }

        // 发送系统通知给评论者
        NotificationHelper.sendViolationNotification(
            notificationClient,
            comment.getUserId().longValue(),
            comment.getId(),
            comment.getContent(),
            reason
        );
    }

    /** 沸点评论温和治理判定：引战/人身攻击/阴阳怪气/软广/刷屏 → true(折叠隐藏)。正常批评与讨论不折叠。 */
    private boolean judgeCommentHidden(Long commentId, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            String sys = "你是社区评论治理助手。判断评论是否属于需要折叠的破坏性内容："
                + "人身攻击/辱骂、明显引战/挑衅、阴阳怪气、广告或引流(软广)、重复刷屏。"
                + "正常的不同意见、批评、调侃、表情/梗不算。仅输出 JSON：{\"action\":\"pass\"|\"hide\"}";
            // 模型按 feature(pins_comment_audit) 经 AiModelRouter 路由：未装配时网关返回 null → 走下方"判定失败默认放行"
            String ans = llmGateway.generateOrNull(
                com.zhuri.coding.content.service.ai.AiFeatures.PINS_COMMENT_AUDIT,
                sys, "评论内容：" + (content.length() > 500 ? content.substring(0, 500) : content), null, null);
            return ans != null && ans.contains("\"hide\"");
        } catch (Exception e) {
            log.warn("沸点评论AI治理判定失败, commentId={}", commentId, e);
            return false; // 判定失败默认放行
        }
    }
}
