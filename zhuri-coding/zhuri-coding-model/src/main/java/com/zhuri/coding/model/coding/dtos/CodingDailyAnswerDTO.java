package com.zhuri.coding.model.coding.dtos;

import lombok.Data;

/**
 * 每日一题作答入参（简答）
 */
@Data
public class CodingDailyAnswerDTO {

    /** 题目池ID */
    private Long poolId;

    /** 用户作答文本 */
    private String answerText;

    /** 作答用时（秒，可选） */
    private Integer elapsedSeconds;
}
