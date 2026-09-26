package com.zhuri.coding.content.service.comment.impl;

import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
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
import com.zhuri.coding.model.comment.pojos.ApComment;
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
 * 评论异步审核服务（数据库可靠队列版）
 *
 * 审核策略：先展示后审核
 * 1. 评论发布时立即保存到数据库（用户可见）
 * 2. 审核任务持久化到统一任务表 ap_audit_task（bizType=article_comment），后台异步（延迟约 5-10 秒）执行审核
 * 3. 审核通过 → 给内容作者发送"评论"通知
 * 4. 审核违规 → 删除评论，给评论者发送"系统通知"
 *
 * 可靠性保证：
 * - 任务落库，服务重启/崩溃后由定时补偿任务（CommentAuditRecoveryTask）重新拉起，审核不丢失
 * - 执行前通过 CAS（PENDING→PROCESSING）抢占，进程内直接执行与定时补偿执行不会重复处理
 * - 处理异常按指数退避重试，重试超限则降级通过，避免系统故障误删正常评论
 *
 * <p><b>本类现在只负责"业务差异"，调度机制交给 {@link AuditTaskDispatcher}</b>：
 * CAS 抢占、指数退避、超限降级放行、幂等入队、补偿扫描都已在 Dispatcher 内实现一份。
 * 另外，通过/违规后的业务动作由父类 {@link AbstractAuditService} 的模板方法
 * （{@code handlePassed} / {@code handleFailed}）完成，本类只需额外补"降级放行"这一条路径。
 *
 * <p><b>审核对象语义</b>：本业务读**任务表里的内容快照**（而非回查评论表），
 * 使审核对象是"提交时的内容"；沸点审核则回查业务表取最新内容 —— 两者语义不同，故由各自的 Handler 实现。
 *
 * <p><b>迁移历史</b>：2026-09-26 由独立表 {@code ap_comment_audit_task} 并入统一表；
 * 同期抽出的 Dispatcher 消除了散落在三个 Service 里的重复调度逻辑。
 */
@Slf4j
@Service
public class CommentAuditService extends AbstractAuditService implements AuditTaskHandler {

    /** 本服务负责的业务类型（统一表 ap_audit_task 内按此值路由） */
    private static final String BIZ_TYPE = ApAuditTask.BIZ_ARTICLE_COMMENT;

    /** 尽快执行触发的延迟窗口下界（毫秒）—— 与"先展示后审核"的产品窗口一致 */
    private static final long TRIGGER_DELAY_MIN_MILLIS = 5_000L;
    /** 延迟窗口的随机上浮范围（毫秒），避免同批评论同时触发审核 */
    private static final long TRIGGER_DELAY_JITTER_MILLIS = 5_000L;

    @Autowired
    private ApCommentMapper apCommentMapper;

    @Autowired
    private AuditTaskDispatcher auditTaskDispatcher;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    /**
     * 统一 LLM 出口（安全横切 + token 计量）。折叠判定是"尽力而为"的温和治理：
     * 模型未装配（如未配置 Key 的环境/单测上下文）或调用异常时一律放行，不影响审核主链路。
     * 模型选择交由网关内的 AiModelRouter 按 feature 路由（comment_audit → 低成本模型）。
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
     * 评论入队并触发异步审核（延迟约 5-10 秒）
     * 兼容原有调用方签名；仅持久化任务并请求一次尽快处理。
     *
     * @param context 审核上下文
     */
    @Async
    public void asyncAuditComment(AuditContext context) {
        if (context == null || context.getEntityId() == null) {
            log.warn("评论审核入队失败：上下文或评论ID为空");
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
            task.setTargetType(context.getTargetType() != null ? context.getTargetType() : 1);
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
                    // 池拒绝/任务异常：只记录。真正的兜底是定时补偿（CommentAuditRecoveryTask）
                    log.warn("评论审核尽快执行触发失败，将由定时补偿拉起, commentId={}", commentId, e);
                    return null;
                });

            log.info("评论已加入数据库可靠审核队列, commentId={}, targetType={}", commentId, context.getTargetType());
        } catch (Exception e) {
            log.error("触发评论异步审核入队异常, commentId={}", commentId, e);
        }
    }

    /**
     * 依据队列数据重建审核上下文并执行审核。
     *
     * <p>通过 / 违规后的业务动作（折叠判定、发通知、删评论、撤销行为记录）由父类模板方法完成，
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
     * 降级放行后的业务动作：评论继续可见，向内容作者补发"仅过审"评论通知，
     * 使**降级路径与正常通过路径在用户可见结果上一致**（否则内容作者会觉得评论被吞了）。
     *
     * <p>注意这里刻意不做折叠判定 —— 系统故障期间不应叠加额外的主观治理动作。
     */
    @Override
    public void onDegraded(ApAuditTask task) {
        if (task.getTargetUserId() == null || task.getTargetId() == null) {
            return;
        }
        NotificationHelper.sendCommentNotification(
                notificationClient,
                task.getTargetUserId(),
                task.getAuthorId(),
                task.getContent(),
                task.getTargetType(),
                task.getTargetId());
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
        // AI 社区治理：红线违规已在 audit 中删除；此处对"通过"评论追加温和判定（引战/阴阳/软广）→ 折叠隐藏
        if (judgeCommentHidden(context.getEntityId(), context.getContent())) {
            ApComment c = apCommentMapper.selectById(context.getEntityId());
            if (c != null) {
                c.setIsHidden(1);
                apCommentMapper.updateById(c);
                log.info("评论AI社区治理折叠, commentId={}", context.getEntityId());
            }
            return; // 折叠评论不向作者发"新评论"通知
        }
        // "仅过审通知"：文章评论审核通过后，才向内容作者发送评论通知
        //（评论创建时不再经行为总线发送，避免未过审/违规评论也通知作者）。
        ApComment comment = apCommentMapper.selectById(context.getEntityId());
        if (comment == null) {
            log.warn("评论不存在, commentId={}", context.getEntityId());
            return;
        }
        if (context.getTargetUserId() != null) {
            NotificationHelper.sendCommentNotification(
                notificationClient,
                context.getTargetUserId(),
                context.getUserId(),
                comment.getContent(),
                context.getTargetType(),
                context.getTargetId()
            );
        }
        log.info("评论审核通过: commentId={}, targetType={}", context.getEntityId(), context.getTargetType());
    }

    @Override
    protected void handleFailed(AuditContext context, String reason) {
        // 审核违规：软删除评论 + 发送系统通知给评论者
        ApComment comment = apCommentMapper.selectById(context.getEntityId());
        if (comment == null) {
            return;
        }

        // 软删除评论（通过 is_deleted 字段，但 ap_comment 表没有 is_deleted 字段）
        // 直接物理删除
        apCommentMapper.deleteById(comment.getId());

        // 更新行为记录状态为已撤销
        if (context.getUserId() != null) {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserBehaviorRecord> query =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            query.eq(UserBehaviorRecord::getUserId, context.getUserId());
            query.eq(UserBehaviorRecord::getBehaviorType,
                context.getTargetType() == 1 ? "comment_article" : "comment_pin");
            query.eq(UserBehaviorRecord::getTargetId, context.getTargetId());
            query.eq(UserBehaviorRecord::getStatus, 1);
            UserBehaviorRecord record = behaviorRecordMapper.selectOne(query);
            if (record != null) {
                record.setStatus(0);
                behaviorRecordMapper.updateById(record);
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

    /** 评论温和治理判定：引战/人身攻击/阴阳怪气/软广/刷屏 → true(折叠隐藏)。正常批评与讨论不折叠。 */
    private boolean judgeCommentHidden(Long commentId, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            String sys = "你是社区评论治理助手。判断评论是否属于需要折叠的破坏性内容："
                + "人身攻击/辱骂、明显引战/挑衅、阴阳怪气、广告或引流(软广)、重复刷屏。"
                + "正常的不同意见、批评、调侃、表情/梗不算。仅输出 JSON：{\"action\":\"pass\"|\"hide\"}";
            // 模型按 feature(comment_audit) 经 AiModelRouter 路由：未装配时网关返回 null → 走下方"判定失败默认放行"
            String ans = llmGateway.generateOrNull(
                com.zhuri.coding.content.service.ai.AiFeatures.COMMENT_AUDIT,
                sys, "评论内容：" + (content.length() > 500 ? content.substring(0, 500) : content), null, null);
            return ans != null && ans.contains("\"hide\"");
        } catch (Exception e) {
            log.warn("评论AI治理判定失败, commentId={}", commentId, e);
            return false; // 判定失败默认放行
        }
    }
}
