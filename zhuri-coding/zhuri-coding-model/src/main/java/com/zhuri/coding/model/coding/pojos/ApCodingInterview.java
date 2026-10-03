package com.zhuri.coding.model.coding.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 模拟面试记录（Coding 延展第三层 · Stage A）
 *
 * <p>单人单场生命周期：1进行中 → 2已完成 / 3已过期（超时懒过期，到点即废、报告不生成）。</p>
 *
 * <p><b>单表 JSON 快照</b>：{@code planSnapshot} 存开面时刻的提纲（含 keyPoints 关键考点，仅服务端可见），
 * {@code turns} 存全部对话流水，{@code report} 存面试报告——回放 = 读三列重建，
 * 题库/文章后续变更不影响历史场次。</p>
 *
 * <p><b>防重基准</b>：{@code turnCount} 为客户端已见轮数（turnSeq），更新走
 * {@code where id=? and status=1 and turn_count=?} 原子防重；{@code followupCount} 为当前主题
 * 已追问次数，追问上限由服务端强制（不依赖模型自觉）。</p>
 */
@Data
@TableName("ap_coding_interview")
public class ApCodingInterview implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：进行中 */
    public static final int STATUS_ONGOING = 1;
    /** 状态：已完成 */
    public static final int STATUS_FINISHED = 2;
    /** 状态：已过期（超时未结束） */
    public static final int STATUS_EXPIRED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 状态：1进行中 2已完成 3已过期 */
    @TableField("status")
    private Integer status;

    /** 面试方向（技术栈/岗位，如 Java 后端） */
    @TableField("direction")
    private String direction;

    /** 难度：1入门 2进阶 3挑战 */
    @TableField("difficulty")
    private Integer difficulty;

    /** 面试提纲（JSON数组：[{topic,mainQuestion,keyPoints[],tag}]，含关键考点，仅服务端可见） */
    @TableField("plan_snapshot")
    private String planSnapshot;

    /** 对话流水（JSON数组：[{role:interviewer|user, type:question|followup|answer, content, topicIndex, ts}]） */
    @TableField("turns")
    private String turns;

    /** 当前主题下标（从0起） */
    @TableField("current_index")
    private Integer currentIndex;

    /** 当前主题已追问次数（服务端强制上限） */
    @TableField("followup_count")
    private Integer followupCount;

    /** 用户作答轮数（turnSeq 防重基准） */
    @TableField("turn_count")
    private Integer turnCount;

    /** 面试报告（JSON；结构化失败时存模型原文） */
    @TableField("report")
    private String report;

    /** 综合等级（1-5，报告生成后写入） */
    @TableField("overall_score")
    private Integer overallScore;

    /** 开面时间 */
    @TableField("started_time")
    private Date startedTime;

    /** 截止时间（开面+限时） */
    @TableField("deadline_time")
    private Date deadlineTime;

    /** 最近活动时间（每轮刷新） */
    @TableField("last_active_time")
    private Date lastActiveTime;

    /** 结束时间 */
    @TableField("finished_time")
    private Date finishedTime;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}