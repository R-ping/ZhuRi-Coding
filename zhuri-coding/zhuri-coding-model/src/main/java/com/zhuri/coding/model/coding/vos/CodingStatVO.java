package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 我的编码统计（简答版）
 *
 * <p>连续签到天数来自签到体系（只读展示，签到是独立入口，答题不代打卡）。
 * 「正确率」这个概念在简答口径下不存在，改为「平均等级」；
 * 「每日一题」与「自由练习」的区分也消失了，只保留一个总数。</p>
 */
@Data
public class CodingStatVO implements Serializable {

    /** 连续签到天数（reward 服务签到体系，不可用时降级为 0） */
    private Integer continuousDays;

    /** 今天是否已作答 */
    private Boolean todayAnswered;

    /** 今天这次作答的等级；未作答或未评估为 null */
    private Integer todayLevel;

    /** 累计作答数 */
    private Integer totalCount;

    /** 平均等级（保留一位小数；无数据为 null） */
    private Double avgLevel;

    /** 当前练习方向 */
    private String direction;

    /** 领域答题分布（原样透出：{"Redis":{"total":3,"levelSum":11}}） */
    private Map<String, Object> tagStats;

    /** 首次答题日期（yyyy-MM-dd，未答过为 null） */
    private String firstAnswerDate;

    /** 最近答题日期（yyyy-MM-dd，未答过为 null） */
    private String lastAnswerDate;
}
