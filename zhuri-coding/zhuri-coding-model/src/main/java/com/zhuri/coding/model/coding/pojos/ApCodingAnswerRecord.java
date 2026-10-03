package com.zhuri.coding.model.coding.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 每日一题作答记录（Coding 延展第一层）
 *
 * <p>{@code is_daily=1} 表示"当日一题"（计入榜单与等级分，一天一次）；
 * {@code is_daily=0} 表示自由练习（只沉淀统计，不计分不打卡）。</p>
 */
@Data
@TableName("ap_coding_answer_record")
public class ApCodingAnswerRecord implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 作答用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 题目ID */
    @TableField("question_id")
    private Long questionId;

    /** 作答日期（按天限次与榜单统计维度） */
    @TableField("answer_date")
    private Date answerDate;

    /** 用户答案（JSON数组） */
    @TableField("user_answer")
    private String userAnswer;

    /** 是否答对 1是 0否 */
    @TableField("is_correct")
    private Integer isCorrect;

    /** 作答用时（秒） */
    @TableField("elapsed_seconds")
    private Integer elapsedSeconds;

    /** 是否当日一题 1是 0自由练习 */
    @TableField("is_daily")
    private Integer isDaily;

    /** 本次获得的逐日分 */
    @TableField("score_awarded")
    private Integer scoreAwarded;

    @TableField("created_time")
    private Date createdTime;
}