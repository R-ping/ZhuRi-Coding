package com.zhuri.coding.model.coding.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户编码统计（Coding 延展第一层 · 简答）
 *
 * <p><b>连续答题天数不存本表</b>：签到体系（reward 服务 {@code user_checkin_state.continuous_days}）
 * 是连续记录的唯一致源；本表只沉淀做题维度（总题数、领域分布、练习方向）——
 * 前者是可变的聚合值，后者是流水，分开存。</p>
 *
 * <p>{@code tag_stats} 口径（简答版）：{@code {"Redis":{"total":3,"levelSum":11}}} ——
 * 累计等级而非正确率。字段名与 JSON 形状保留，是为了让能力档案的领域分布块
 * 与面试提纲的「薄弱方向」输入继续读同一列，只是排序口径从"正确率升序"换成"平均等级升序"。</p>
 */
@Data
@TableName("ap_coding_user_stat")
public class ApCodingUserStat implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 用户选择的练习方向（每日一题按此抽题） */
    @TableField("direction")
    private String direction;

    /** 累计作答数 */
    @TableField("total_count")
    private Integer totalCount;

    /** 领域答题分布（JSON：{"Redis":{"total":3,"levelSum":11}}） */
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
