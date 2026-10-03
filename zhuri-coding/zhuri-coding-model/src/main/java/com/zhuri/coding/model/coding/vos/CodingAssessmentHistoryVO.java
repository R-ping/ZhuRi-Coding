package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 能力测评历史项（Coding 延展第二层 · Stage B）
 */
@Data
public class CodingAssessmentHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 测评记录ID */
    private Long assessmentId;

    /** 得分（0-100；进行中/已过期无成绩为 0） */
    private Integer score;

    /** 答对题数 */
    private Integer correctCount;

    /** 总题数 */
    private Integer totalCount;

    /** 状态：1进行中 2已提交 3已过期 */
    private Integer status;

    /** 交卷时间（yyyy-MM-dd HH:mm:ss；未提交为空串） */
    private String submittedTime;
}