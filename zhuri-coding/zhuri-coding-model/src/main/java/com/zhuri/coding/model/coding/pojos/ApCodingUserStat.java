package com.zhuri.coding.model.coding.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户编码统计（Coding 延展第一层）
 *
 * <p><b>连续答题天数不存本表</b>：签到体系（reward 服务 user_checkin_state.continuous_days）
 * 是连续记录的唯一致源，答题正确视为当日完成并触发打卡，口径与签到完全一致；
 * 本表只沉淀做题维度（总题数、正确率、领域分布）——与既有的每日进度/等级数据分开存储，
 * 前者是可变的聚合值，后者是流水。</p>
 */
@Data
@TableName("ap_coding_user_stat")
public class ApCodingUserStat implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 每日一题累计作答数 */
    @TableField("total_count")
    private Integer totalCount;

    /** 每日一题累计答对数 */
    @TableField("correct_count")
    private Integer correctCount;

    /** 自由练习累计作答数 */
    @TableField("practice_count")
    private Integer practiceCount;

    /** 自由练习累计答对数 */
    @TableField("practice_correct_count")
    private Integer practiceCorrectCount;

    /** 领域答题分布（JSON：{"Redis":{"total":3,"correct":2}}） */
    @TableField("tag_stats")
    private String tagStats;

    /** 首次答题日期 */
    @TableField("first_answer_date")
    private Date firstAnswerDate;

    /** 最近答题日期 */
    @TableField("last_answer_date")
    private Date lastAnswerDate;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}