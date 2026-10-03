package com.zhuri.coding.model.coding.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 编码能力测评记录（Coding 延展第二层）
 *
 * <p>单人单卷生命周期：1进行中 → 2已提交 / 3已过期；冷却（重考间隔）只约束"已提交"，
 * 进行中可续答（deadline 不变），已过期可立即重开。</p>
 *
 * <p><b>快照判分</b>：{@code paperSnapshot} 存开卷时刻的题目内容（含正确答案，仅服务端可见），
 * 判分与成绩单回放都以快照为准——题库题目被编辑/下架不影响历史成绩。</p>
 */
@Data
@TableName("ap_coding_assessment")
public class ApCodingAssessment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：进行中 */
    public static final int STATUS_ONGOING = 1;
    /** 状态：已提交 */
    public static final int STATUS_SUBMITTED = 2;
    /** 状态：已过期（超时未交卷） */
    public static final int STATUS_EXPIRED = 3;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 状态：1进行中 2已提交 3已过期 */
    @TableField("status")
    private Integer status;

    /** 组卷快照（JSON数组：id/stem/type/options/answer/explanation/difficulty/tags） */
    @TableField("paper_snapshot")
    private String paperSnapshot;

    /** 作答明细（JSON数组：questionId/userAnswer/correct） */
    @TableField("answers")
    private String answers;

    /** 得分（0-100，correct/total 归一化） */
    @TableField("score")
    private Integer score;

    /** 答对题数 */
    @TableField("correct_count")
    private Integer correctCount;

    /** 总题数 */
    @TableField("total_count")
    private Integer totalCount;

    /** 领域分布（JSON：{"Redis":{"total":2,"correct":1}}） */
    @TableField("domain_stats")
    private String domainStats;

    /** 百分位（样本不足时为 NULL） */
    @TableField("percentile")
    private Integer percentile;

    /** 总用时（秒） */
    @TableField("duration_seconds")
    private Integer durationSeconds;

    /** 开卷时间 */
    @TableField("started_time")
    private Date startedTime;

    /** 截止时间 */
    @TableField("deadline_time")
    private Date deadlineTime;

    /** 交卷时间 */
    @TableField("submitted_time")
    private Date submittedTime;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}