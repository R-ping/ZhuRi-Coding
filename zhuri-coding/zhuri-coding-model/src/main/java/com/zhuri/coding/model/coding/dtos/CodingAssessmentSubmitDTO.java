package com.zhuri.coding.model.coding.dtos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 能力测评交卷入参（Coding 延展第二层 · Stage B）
 *
 * <p>answers 为逐题作答明细；未作答的题可以缺失（按错题计）。下标越界的选项会被忽略。</p>
 */
@Data
public class CodingAssessmentSubmitDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 测评记录ID */
    private Long assessmentId;

    /** 逐题作答明细 */
    private List<Item> answers;

    @Data
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 题目ID */
        private Long questionId;

        /** 用户选择的选项下标（单选 1 个，多选多个） */
        private List<Integer> userAnswer;
    }
}