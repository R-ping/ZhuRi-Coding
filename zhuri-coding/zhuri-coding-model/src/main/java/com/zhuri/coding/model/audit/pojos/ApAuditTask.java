package com.zhuri.coding.model.audit.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 统一异步审核任务表实体（数据库可靠队列）。
 *
 * <p><b>合并来源</b>：原先 {@code ap_comment_audit_task} / {@code ap_pins_audit_task} /
 * {@code ap_pins_comment_audit_task} 三张表的字段、状态值、唯一键、索引完全一致（同一模板复制三遍），
 * 且各自配了一个补偿扫描器。现统一到本表，由 {@link #bizType} 区分业务，
 * 调度机制（CAS 抢占 / 指数退避 / 超限降级放行 / 补偿扫描）只保留一份实现。
 *
 * <p><b>与本地消息表（{@code ap_outbox_event}）的区别</b>：本表存的是「执行状态」（这件事做到哪一步了），
 * 不是「待投递的消息」；消费方是本服务内的审核处理器，不存在跨系统投递的原子性问题。
 * 它借鉴的是 Outbox 的可靠投递手法（同事务落库 → CAS 抢占 → 退避重试 → 终态），但不从属于 Outbox 框架。
 *
 * <p><b>两点刻意保持一致</b>：
 * <ul>
 *   <li>状态值仍为 0/1/2/3/4 —— 三个业务的判定逻辑无需改动；</li>
 *   <li>幂等键为 {@code {bizType}:{bizId}} —— 配合唯一索引 {@code uk_task_key}，重复入队由数据库拒绝，
 *       不需要「先查再插」。</li>
 * </ul>
 */
@Data
@TableName("ap_audit_task")
public class ApAuditTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型：文章评论 */
    public static final String BIZ_ARTICLE_COMMENT = "article_comment";
    /** 业务类型：沸点 */
    public static final String BIZ_PINS = "pins";
    /** 业务类型：沸点评论 */
    public static final String BIZ_PINS_COMMENT = "pins_comment";

    /** 任务状态：待审核 */
    public static final int STATUS_PENDING = 0;
    /** 任务状态：审核中（CAS 抢占执行中） */
    public static final int STATUS_PROCESSING = 1;
    /** 任务状态：完成（审核通过） */
    public static final int STATUS_PASSED = 2;
    /** 任务状态：完成（审核违规） */
    public static final int STATUS_VIOLATION = 3;
    /** 任务状态：重试超限，降级通过（系统故障不误伤正常内容） */
    public static final int STATUS_DEGRADED_PASSED = 4;

    /** 最大重试次数（与旧表 MAX_RETRY 保持一致） */
    public static final int MAX_RETRY = 5;

    /**
     * 幂等键：同一业务仅允许一条任务。
     *
     * <p>统一在这里拼接，避免各处手拼字符串导致格式漂移（旧表分别是裸 {@code comment_id} /
     * {@code pins_id}，无法表达「不同业务的 id 撞车」这种情况）。
     *
     * @param bizType 业务类型，取 {@link #BIZ_ARTICLE_COMMENT} 等常量
     * @param bizId   业务ID（评论ID 或 沸点ID）
     */
    public static String taskKey(String bizType, Long bizId) {
        return bizType + ":" + bizId;
    }

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("task_key")
    private String taskKey;

    @TableField("biz_type")
    private String bizType;

    @TableField("biz_id")
    private Long bizId;

    /** 行为用户ID：审核通过后发等级积分用（沸点为发布者，评论为评论者） */
    @TableField("actor_user_id")
    private Integer actorUserId;

    @TableField("author_id")
    private Integer authorId;

    @TableField("author_name")
    private String authorName;

    /** 待审内容快照（文章评论审核直接读快照；沸点审核复核时回查业务表） */
    @TableField("content")
    private String content;

    @TableField("image_urls")
    private String imageUrls;

    /** 目标内容类型：1-文章 2-沸点（审核通过后发通知用） */
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
