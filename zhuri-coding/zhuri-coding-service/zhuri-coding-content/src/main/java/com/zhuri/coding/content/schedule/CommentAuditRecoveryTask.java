package com.heima.content.schedule;

import com.heima.content.service.comment.impl.CommentAuditService;
import com.heima.model.audit.pojos.ApCommentAuditTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 评论异步审核补偿任务
 *
 * 作为 ap_comment_audit_task 可靠队列的兜底扫描器：
 * - 服务重启/崩溃导致进程内异步任务丢失时，这里负责重新拉起仍未审核的评论
 * - 处理异常进入退避重试的任务，到期后也由这里重新执行
 * 与进程内直接触发共用【CAS 抢占】，不会重复处理同一评论。
 */
@Slf4j
@Component
public class CommentAuditRecoveryTask {

    @Autowired
    private CommentAuditService commentAuditService;

    /** 单批扫描上限 */
    private static final int BATCH = 50;

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void recoverPendingAuditTasks() {
        try {
            List<ApCommentAuditTask> tasks = commentAuditService.listPendingDue(BATCH);
            if (tasks == null || tasks.isEmpty()) {
                return;
            }
            log.info("评论审核补偿扫描到待审核任务 {} 条", tasks.size());
            for (ApCommentAuditTask task : tasks) {
                try {
                    commentAuditService.processTaskIfPending(task.getId());
                } catch (Exception e) {
                    log.error("评论审核补偿处理异常, taskId={}", task.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("评论审核补偿任务扫描异常", e);
        }
    }
}