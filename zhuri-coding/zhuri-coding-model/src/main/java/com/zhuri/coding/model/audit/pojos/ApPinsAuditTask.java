package com.heima.model.audit.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 沸点异步审核可靠队列表实体
 *
 * 解决沸点异步审核任务因服务重启丢失导致长期停留在"待审"状态的问题：
 * 发布时持久化一条待审核任务，定时补偿任务可据此重拉审核。
 */
@Data
@TableName("ap_pins_audit_task")
public class ApPinsAuditTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务状态：待审核 */
    public static final int STATUS_PENDING = 0;
    /** 任务状态：审核中（CAS 抢占执行中） */
    public static final int STATUS_PROCESSING = 1;
    /** 任务状态：完成（审核通过） */
    public static final int STATUS_PASSED = 2;
    /** 任务状态：完成（审核违规） */
    public static final int STATUS_VIOLATION = 3;
    /** 任务状态：重试超限，降级通过 */
    public static final int STATUS_DEGRADED_PASSED = 4;

    /** 最大重试次数 */
    public static final int MAX_RETRY = 5;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pins_id")
    private Long pinsId;

    @TableField("author_id")
    private Integer authorId;

    @TableField("author_name")
    private String authorName;

    @TableField("user_id")
    private Integer userId;

    @TableField("content")
    private String content;

    @TableField("image_urls")
    private String imageUrls;

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