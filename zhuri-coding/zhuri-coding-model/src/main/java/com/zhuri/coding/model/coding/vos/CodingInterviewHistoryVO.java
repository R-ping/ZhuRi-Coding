package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 模拟面试历史条目（历史分页返回，Coding 延展第三层 · Stage A）
 */
@Data
public class CodingInterviewHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 面试记录ID */
    private Long interviewId;

    /** 面试方向 */
    private String direction;

    /** 难度：1入门 2进阶 3挑战 */
    private Integer difficulty;

    /** 综合等级（1-5；未完成/报告不可用时为 null） */
    private Integer overallScore;

    /** 状态：1进行中 2已完成 3已过期 */
    private Integer status;

    /** 主题总数 */
    private Integer totalTopics;

    /** 开面时间（yyyy-MM-dd HH:mm:ss，展示用） */
    private String startedTime;

    /** 结束时间（同上） */
    private String finishedTime;
}