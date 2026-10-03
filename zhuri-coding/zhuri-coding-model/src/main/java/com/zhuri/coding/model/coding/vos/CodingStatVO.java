package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 我的编码统计 VO（Coding 延展第一层）
 *
 * <p>连续天数来自签到体系（唯一来源，与签到共用一份记录）；
 * 其余为做题维度的聚合值（每日一题与自由练习分开统计）。</p>
 */
@Data
public class CodingStatVO implements Serializable {

    /** 连续签到/答题天数（reward 服务签到体系，不可用时降级为 0） */
    private Integer continuousDays;

    /** 今日一题是否已作答 */
    private Boolean todayAnswered;

    /** 今日一题是否答对（未答为 null） */
    private Boolean todayCorrect;

    /** 每日一题累计作答数 */
    private Integer totalCount;

    /** 每日一题累计答对数 */
    private Integer correctCount;

    /** 每日一题正确率（百分比整数，0-100） */
    private Integer accuracy;

    /** 自由练习累计作答数 */
    private Integer practiceCount;

    /** 自由练习累计答对数 */
    private Integer practiceCorrectCount;

    /** 自由练习正确率（百分比整数，0-100） */
    private Integer practiceAccuracy;

    /** 领域答题分布（原样返回 JSON 解析结果：{"Redis":{"total":3,"correct":2}}） */
    private Map<String, Object> tagStats;

    /** 首次答题日期（yyyy-MM-dd，未答过为 null） */
    private String firstAnswerDate;

    /** 最近答题日期（yyyy-MM-dd，未答过为 null） */
    private String lastAnswerDate;
}