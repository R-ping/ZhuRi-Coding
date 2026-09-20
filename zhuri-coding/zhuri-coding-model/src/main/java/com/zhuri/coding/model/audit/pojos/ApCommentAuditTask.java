package com.zhuri.coding.model.audit.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 评论异步审核可靠队列表实体
 *
 * 解决【先展示后审核】窗口期：评论发布时立即落库（用户可见），同时持久化一条待审核任务。
 * 若服务重启导致进程内异步任务丢失，定时补偿任务可据此重拉审核，避免违规评论长期滞留前台。
 */
@Data
@TableName("ap_comment_audit_task")
public class ApCommentAuditTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务状态：待审核 */
    public static final int STATUS_PENDING = 0;
    /** 任务状态：审核中（CAS 抢占执行中） */
    public static final int STATUS_PROCESSING = 1;
    /** 任务状态：完成（审核通过） */
    public static final int STATUS_PASSED = 2;
    /** 任务状态：完成（审核违规，评论已删除） */
    public static final int STATUS_VIOLATION = 3;
    /** 任务状态：重试超限，降级通过（避免系统故障误伤） */
    public static final int STATUS_DEGRADED_PASSED = 4;

    /** 最大重试次数：超过即降级通过 */
    public static final int MAX_RETRY = 5;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("comment_id")
    private Long commentId;

    @TableField("commenter_id")
    private Integer commenterId;

    @TableField("commenter_name")
    private String commenterName;

    @TableField("content")
    private String content;

    @TableField("target_type")
    private Integer targetType;

    @TableField("target_id")
    private Long targetId;

    @TableField("target_user_id")
    private Integer targetUserId;

    @TableField("status")
    private Integer status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("next_retry_time")
    private Date nextRetryTime;

    @TableField("audit_time")
    private Date auditTime;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}