package com.heima.model.outbox.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 本地消息表（Transactional Outbox）。
 *
 * <p>核心保证：业务主事务与「事件写入」在同一数据库事务内提交 —— 主事务回滚则事件消失，
 * 主事务提交则事件必然存在，Dispatcher 只负责把已存在的事件最终执行成功（At-Least-Once，
 * 由 event_key 唯一键 + Handler 幂等收敛为 Effectively-Once）。
 *
 * <p>状态机：
 * <pre>
 *   PENDING(0) --CAS 抢占--> PROCESSING(3) --成功--> DONE(1)
 *                                    --失败--> PENDING(0)（retry_count++，next_retry_at 指数退避）
 *                                    --重试超限--> DEAD(2)（人工介入，指标告警）
 *   PROCESSING(3) 执行中崩溃 → updated_time 超时回收，重新变回可分发
 * </pre>
 */
@Data
@TableName("ap_outbox_event")
public class OutboxEvent implements Serializable {

    /** 待分发 */
    public static final int STATUS_PENDING = 0;
    /** 已完成 */
    public static final int STATUS_DONE = 1;
    /** 死信（重试超限，需人工介入） */
    public static final int STATUS_DEAD = 2;
    /** 执行中（CAS 抢占后、结果落库前） */
    public static final int STATUS_PROCESSING = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务幂等键（如 PAY_REWARD:{orderNo}），同一事件只写一次 */
    @TableField("event_key")
    private String eventKey;

    /** 事件类型 = OutboxHandler 路由键 */
    @TableField("event_type")
    private String eventType;

    /** JSON 载荷 */
    @TableField("payload")
    private String payload;

    /** 见类注释状态机 */
    @TableField("status")
    private Integer status;

    /** 已重试次数 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 重试上限，达到即置 DEAD */
    @TableField("max_retries")
    private Integer maxRetries;

    /** 下次重试时间（指数退避）；PENDING 且到期才被分发 */
    @TableField("next_retry_at")
    private Date nextRetryAt;

    /** 最近一次失败原因（截断到 500 字符） */
    @TableField("last_error")
    private String lastError;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}
