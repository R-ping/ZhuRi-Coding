package com.heima.content.service.pins.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.heima.apis.notification.INotificationClient;
import com.heima.content.mapper.pins.ApPinsCommentAuditTaskMapper;
import com.heima.content.mapper.pins.ApPinsCommentMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.content.service.article.impl.AbstractAuditService;
import com.heima.content.utils.NotificationHelper;
import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditEntityType;
import com.heima.model.audit.AuditResult;
import com.heima.model.audit.pojos.ApPinsCommentAuditTask;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
import com.heima.model.pins.pojos.ApPinsComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 沸点评论异步审核服务（数据库可靠队列版，Step3 沸点接入治理）
 *
 * 与 {@link com.heima.content.service.comment.impl.CommentAuditService}（文章评论）同构，
 * 但独立建表 ap_pins_comment_audit_task / 独立服务，零侵入文章侧已联调链路。
 * （两张源表 id 各自 AUTO 自增会撞号，无法共用文章任务的 comment_id 唯一键。）
 *
 * 审核策略：先展示后审核
 * 1. 沸点评论发布时立即保存到数据库（用户可见），并向沸点作者发送"新评论"通知（创建即通知，既有行为保持）
 * 2. 审核任务持久化到 ap_pins_comment_audit_task 队列，后台异步（延迟约 5-10 秒）执行审核
 * 3. 红线违规 → 物理删除评论 + 给评论者发违规系统通知
 * 4. 温和违规（引战/阴阳/软广）→ is_hidden=1 折叠：全局隐藏（对所有人含本人不可见，数据保留可审计）
 *
 * 可靠性保证：任务落库 + CAS 抢占 + 指数退避重试 + 超限降级放行（系统故障不误删正常评论）。
 */
@Slf4j
@Service
public class PinsCommentAuditService extends AbstractAuditService {

    @Autowired
    private ApPinsCommentMapper pinsCommentMapper;

    @Autowired
    private ApPinsCommentAuditTaskMapper auditTaskMapper;

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
            // 1. 幂等持久化任务（同一条评论仅一条任务，唯一键 comment_id 兜底）
            ApPinsCommentAuditTask task = new ApPinsCommentAuditTask();
            task.setCommentId(commentId);
            task.setCommenterId(context.getUserId() != null ? context.getUserId() : 0);
            task.setCommenterName(context.getAuthorName() != null ? context.getAuthorName() : "");
            task.setContent(context.getContent() != null ? context.getContent() : "");
            task.setTargetType(context.getTargetType() != null ? context.getTargetType() : 2);
            task.setTargetId(context.getTargetId());
            task.setTargetUserId(context.getTargetUserId());
            task.setStatus(ApPinsCommentAuditTask.STATUS_PENDING);
            task.setRetryCount(0);
            task.setNextRetryTime(new Date());
            task.setCreateTime(new Date());
            task.setUpdateTime(new Date());
            try {
                auditTaskMapper.insert(task);
            } catch (DuplicateKeyException e) {
                // 已存在同评论的待审核任务，说明已有一次入队在途，忽略即可
                log.info("沸点评论审核任务已存在，跳过重复入队, commentId={}", commentId);
            }

            // 2. 请求一次尽快执行（延迟窗口配置，与"先展示后审核"窗口保持一致）
            long delay = 5000 + (long) (Math.random() * 5000);
            CompletableFuture.runAsync(
                () -> processTaskIfPending(task.getId()),
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS));

            log.info("沸点评论已加入数据库可靠审核队列, commentId={}, pinsId={}", commentId, context.getTargetId());
        } catch (Exception e) {
            log.error("触发沸点评论异步审核入队异常, commentId={}", commentId, e);
        }
    }

    /**
     * 若任务仍处于待审核则抢执行审核（CAS 抢占，避免与定时补偿重复处理）
     * 供进程内直接触发与定时补偿任务共同调用。
     */
    public void processTaskIfPending(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            // CAS：仅当 status=PENDING 时才允许抢占为 PROCESSING，保证同一任务只会被一个执行体处理
            boolean acquired = auditTaskMapper.update(null, new LambdaUpdateWrapper<ApPinsCommentAuditTask>()
                .eq(ApPinsCommentAuditTask::getId, taskId)
                .eq(ApPinsCommentAuditTask::getStatus, ApPinsCommentAuditTask.STATUS_PENDING)
                .set(ApPinsCommentAuditTask::getStatus, ApPinsCommentAuditTask.STATUS_PROCESSING)
                .set(ApPinsCommentAuditTask::getUpdateTime, new Date())) > 0;
            if (!acquired) {
                return;
            }

            ApPinsCommentAuditTask task = auditTaskMapper.selectById(taskId);
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

            log.info("开始审核沸点评论, commentId={}, taskId={}", task.getCommentId(), taskId);
            AuditResult result = audit(context);

            if (result.isPassed()) {
                markDone(taskId, ApPinsCommentAuditTask.STATUS_PASSED);
                log.info("沸点评论审核通过, commentId={}", task.getCommentId());
            } else {
                markDone(taskId, ApPinsCommentAuditTask.STATUS_VIOLATION);
                log.info("沸点评论审核违规, commentId={}, reason={}", task.getCommentId(), result.getReason());
            }
        } catch (Exception e) {
            log.error("沸点评论审核执行异常, taskId={}", taskId, e);
            retryOrDegrade(taskId);
        }
    }

    /** 恢复任务为待审核并按指数退避安排重试时间；超限则降级通过 */
    private void retryOrDegrade(Long taskId) {
        ApPinsCommentAuditTask task = auditTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        if (retry > ApPinsCommentAuditTask.MAX_RETRY) {
            auditTaskMapper.update(null, new LambdaUpdateWrapper<ApPinsCommentAuditTask>()
                .eq(ApPinsCommentAuditTask::getId, taskId)
                .set(ApPinsCommentAuditTask::getStatus, ApPinsCommentAuditTask.STATUS_DEGRADED_PASSED)
                .set(ApPinsCommentAuditTask::getRetryCount, retry)
                .set(ApPinsCommentAuditTask::getAuditTime, new Date())
                .set(ApPinsCommentAuditTask::getUpdateTime, new Date()));
            log.warn("沸点评论审核重试超限，降级通过, taskId={}, commentId={}", taskId, task.getCommentId());
            // 降级即评论保持可见；沸点作者通知为"创建即通知"，审核链路不补发通知
            return;
        }
        // 指数退避：60s -> 120s -> 240s ...
        long backoffMillis = 60_000L * (1L << (retry - 1));
        Date next = new Date(System.currentTimeMillis() + backoffMillis);
        auditTaskMapper.update(null, new LambdaUpdateWrapper<ApPinsCommentAuditTask>()
            .eq(ApPinsCommentAuditTask::getId, taskId)
            .eq(ApPinsCommentAuditTask::getStatus, ApPinsCommentAuditTask.STATUS_PROCESSING)
            .set(ApPinsCommentAuditTask::getStatus, ApPinsCommentAuditTask.STATUS_PENDING)
            .set(ApPinsCommentAuditTask::getRetryCount, retry)
            .set(ApPinsCommentAuditTask::getNextRetryTime, next)
            .set(ApPinsCommentAuditTask::getUpdateTime, new Date()));
        log.warn("沸点评论审核执行异常，安排退避重试, taskId={}, retryCount={}", taskId, retry);
    }

    /** 标记任务完成 */
    private void markDone(Long taskId, int status) {
        auditTaskMapper.update(null, new LambdaUpdateWrapper<ApPinsCommentAuditTask>()
            .eq(ApPinsCommentAuditTask::getId, taskId)
            .set(ApPinsCommentAuditTask::getStatus, status)
            .set(ApPinsCommentAuditTask::getAuditTime, new Date())
            .set(ApPinsCommentAuditTask::getUpdateTime, new Date()));
    }

    /** 供定时补偿任务批量拉取待审核任务（按 next_retry_time 到期排序） */
    public List<ApPinsCommentAuditTask> listPendingDue(int limit) {
        return auditTaskMapper.selectList(new LambdaQueryWrapper<ApPinsCommentAuditTask>()
            .eq(ApPinsCommentAuditTask::getStatus, ApPinsCommentAuditTask.STATUS_PENDING)
            .and(w -> w.isNull(ApPinsCommentAuditTask::getNextRetryTime).or()
                .le(ApPinsCommentAuditTask::getNextRetryTime, new Date()))
            .orderByAsc(ApPinsCommentAuditTask::getId)
            .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
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
            log.warn("沸点评论AI治理判定失败, commentId={}", commentId, e);
            return false; // 判定失败默认放行
        }
    }
}
