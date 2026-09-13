package com.heima.content.service.comment.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.heima.content.service.article.impl.AbstractAuditService;
import com.heima.apis.notification.INotificationClient;
import com.heima.content.mapper.comment.ApCommentAuditTaskMapper;
import com.heima.content.mapper.comment.ApCommentMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.content.utils.NotificationHelper;
import com.heima.model.comment.pojos.ApComment;
import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditEntityType;
import com.heima.model.audit.AuditResult;
import com.heima.model.audit.pojos.ApCommentAuditTask;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.DuplicateKeyException;

/**
 * 评论异步审核服务（数据库可靠队列版）
 *
 * 审核策略：先展示后审核
 * 1. 评论发布时立即保存到数据库（用户可见）
 * 2. 审核任务持久化到 ap_comment_audit_task 队列，后台异步（延迟约 5-10 秒）执行审核
 * 3. 审核通过 → 给内容作者发送"评论"通知
 * 4. 审核违规 → 删除评论，给评论者发送"系统通知"
 *
 * 可靠性保证：
 * - 任务落库，服务重启/崩溃后由定时补偿任务（CommentAuditRecoveryTask）重新拉起，审核不丢失
 * - 执行前通过 CAS（PENDING→PROCESSING）抢占，进程内直接执行与定时补偿执行不会重复处理
 * - 处理异常按指数退避重试，重试超限则降级通过，避免系统故障误删正常评论
 */
@Slf4j
@Service
public class CommentAuditService extends AbstractAuditService {

    @Autowired
    private ApCommentMapper apCommentMapper;

    @Autowired
    private ApCommentAuditTaskMapper auditTaskMapper;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    /**
     * Spring AI ChatModel（OpenAI compatible 自动配置）。折叠判定是"尽力而为"的温和治理：
     * 未装配（如未配置模型的环境/单测上下文）或调用异常时一律放行，不影响审核主链路。
     */
    @Autowired(required = false)
    private ChatModel commentChatModel;

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
            // 1. 幂等持久化任务（同一条评论仅一条任务，唯一键 comment_id 兜底）
            ApCommentAuditTask task = new ApCommentAuditTask();
            task.setCommentId(commentId);
            task.setCommenterId(context.getUserId() != null ? context.getUserId() : 0);
            task.setCommenterName(context.getAuthorName() != null ? context.getAuthorName() : "");
            task.setContent(context.getContent() != null ? context.getContent() : "");
            task.setTargetType(context.getTargetType() != null ? context.getTargetType() : 1);
            task.setTargetId(context.getTargetId());
            task.setTargetUserId(context.getTargetUserId());
            task.setStatus(ApCommentAuditTask.STATUS_PENDING);
            task.setRetryCount(0);
            task.setNextRetryTime(new Date());
            task.setCreateTime(new Date());
            task.setUpdateTime(new Date());
            try {
                auditTaskMapper.insert(task);
            } catch (DuplicateKeyException e) {
                // 已存在同评论的待审核任务，说明已有一次入队在途，忽略即可
                log.info("评论审核任务已存在，跳过重复入队, commentId={}", commentId);
            }

            // 2. 请求一次尽快执行（延迟窗口配置，与"先展示后审核"窗口保持一致）
            long delay = 5000 + (long) (Math.random() * 5000);
            CompletableFuture.runAsync(
                () -> processTaskIfPending(task.getId()),
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS));

            log.info("评论已加入数据库可靠审核队列, commentId={}, targetType={}", commentId, context.getTargetType());
        } catch (Exception e) {
            log.error("触发评论异步审核入队异常, commentId={}", commentId, e);
        }
    }

    /**
     * 若任务仍处于待审核则抢执行审核（CAS 抢占，避免与定时补偿重复处理）
     * 供进程内直接触发与定时补偿任务共同调用。
     *
     * @param taskId 任务ID
     */
    public void processTaskIfPending(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            // CAS：仅当 status=PENDING 时才允许抢占为 PROCESSING，保证同一任务只会被一个执行体处理
            boolean acquired = auditTaskMapper.update(null, new LambdaUpdateWrapper<ApCommentAuditTask>()
                .eq(ApCommentAuditTask::getId, taskId)
                .eq(ApCommentAuditTask::getStatus, ApCommentAuditTask.STATUS_PENDING)
                .set(ApCommentAuditTask::getStatus, ApCommentAuditTask.STATUS_PROCESSING)
                .set(ApCommentAuditTask::getUpdateTime, new Date())) > 0;
            if (!acquired) {
                return;
            }

            ApCommentAuditTask task = auditTaskMapper.selectById(taskId);
            if (task == null) {
                return;
            }

            // 依据队列数据重建审核上下文
            AuditContext context = new AuditContext(AuditEntityType.COMMENT, task.getCommentId(), task.getCommenterId().longValue());
            context.withTitle("")
                .withContent(task.getContent())
                .withAuthorName(task.getCommenterName())
                .withUserId(task.getCommenterId())
                .withTargetType(task.getTargetType())
                .withTargetId(task.getTargetId())
                .withTargetUserId(task.getTargetUserId());

            log.info("开始审核评论, commentId={}, taskId={}", task.getCommentId(), taskId);
            AuditResult result = audit(context);

            if (result.isPassed()) {
                markDone(taskId, ApCommentAuditTask.STATUS_PASSED);
                log.info("评论审核通过, commentId={}", task.getCommentId());
            } else {
                markDone(taskId, ApCommentAuditTask.STATUS_VIOLATION);
                log.info("评论审核违规, commentId={}, reason={}", task.getCommentId(), result.getReason());
            }
        } catch (Exception e) {
            log.error("评论审核执行异常, taskId={}", taskId, e);
            retryOrDegrade(taskId);
        }
    }

    /** 恢复任务为待审核并按指数退避安排重试时间；超限则降级通过 */
    private void retryOrDegrade(Long taskId) {
        ApCommentAuditTask task = auditTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        if (retry > ApCommentAuditTask.MAX_RETRY) {
            auditTaskMapper.update(null, new LambdaUpdateWrapper<ApCommentAuditTask>()
                .eq(ApCommentAuditTask::getId, taskId)
                .set(ApCommentAuditTask::getStatus, ApCommentAuditTask.STATUS_DEGRADED_PASSED)
                .set(ApCommentAuditTask::getRetryCount, retry)
                .set(ApCommentAuditTask::getAuditTime, new Date())
                .set(ApCommentAuditTask::getUpdateTime, new Date()));
            log.warn("评论审核重试超限，降级通过, taskId={}, commentId={}", taskId, task.getCommentId());
            // 降级通过即评论可见，向作者补发"仅过审"评论通知（与正常通过一致）
            if (task.getTargetUserId() != null && task.getTargetId() != null) {
                NotificationHelper.sendCommentNotification(
                    notificationClient,
                    task.getTargetUserId(),
                    task.getCommenterId(),
                    task.getContent(),
                    task.getTargetType(),
                    task.getTargetId());
            }
            return;
        }
        // 指数退避：60s -> 120s -> 240s ...
        long backoffMillis = 60_000L * (1L << (retry - 1));
        Date next = new Date(System.currentTimeMillis() + backoffMillis);
        auditTaskMapper.update(null, new LambdaUpdateWrapper<ApCommentAuditTask>()
            .eq(ApCommentAuditTask::getId, taskId)
            .eq(ApCommentAuditTask::getStatus, ApCommentAuditTask.STATUS_PROCESSING)
            .set(ApCommentAuditTask::getStatus, ApCommentAuditTask.STATUS_PENDING)
            .set(ApCommentAuditTask::getRetryCount, retry)
            .set(ApCommentAuditTask::getNextRetryTime, next)
            .set(ApCommentAuditTask::getUpdateTime, new Date()));
        log.warn("评论审核执行异常，安排退避重试, taskId={}, retryCount={}", taskId, retry);
    }

    /** 标记任务完成 */
    private void markDone(Long taskId, int status) {
        auditTaskMapper.update(null, new LambdaUpdateWrapper<ApCommentAuditTask>()
            .eq(ApCommentAuditTask::getId, taskId)
            .set(ApCommentAuditTask::getStatus, status)
            .set(ApCommentAuditTask::getAuditTime, new Date())
            .set(ApCommentAuditTask::getUpdateTime, new Date()));
    }

    /** 供定时补偿任务批量拉取待审核任务（按 next_retry_time 到期排序） */
    public java.util.List<ApCommentAuditTask> listPendingDue(int limit) {
        return auditTaskMapper.selectList(new LambdaQueryWrapper<ApCommentAuditTask>()
            .eq(ApCommentAuditTask::getStatus, ApCommentAuditTask.STATUS_PENDING)
            .and(w -> w.isNull(ApCommentAuditTask::getNextRetryTime).or()
                .le(ApCommentAuditTask::getNextRetryTime, new Date()))
            .orderByAsc(ApCommentAuditTask::getId)
            .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
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
        if (commentChatModel == null || content == null || content.isBlank()) {
            return false;
        }
        try {
            String sys = "你是社区评论治理助手。判断评论是否属于需要折叠的破坏性内容："
                + "人身攻击/辱骂、明显引战/挑衅、阴阳怪气、广告或引流(软广)、重复刷屏。"
                + "正常的不同意见、批评、调侃、表情/梗不算。仅输出 JSON：{\"action\":\"pass\"|\"hide\"}";
            String ans = ChatClient.builder(commentChatModel).build()
                .prompt().system(sys).user("评论内容：" + (content.length() > 500 ? content.substring(0, 500) : content))
                .call().content();
            return ans != null && ans.contains("\"hide\"");
        } catch (Exception e) {
            log.warn("评论AI治理判定失败, commentId={}", commentId, e);
            return false; // 判定失败默认放行
        }
    }
}
