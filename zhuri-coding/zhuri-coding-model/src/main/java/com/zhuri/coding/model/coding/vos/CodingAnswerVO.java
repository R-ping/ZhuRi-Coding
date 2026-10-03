package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 作答提交结果 VO（Coding 延展第一层）
 *
 * <p>提交即判分并回放答案与解析：答错给出解析与来源文章链接（导流阅读），
 * 答对回填本次得分与最新连续天数（连续天数来自签到体系，与签到共用一份记录）。</p>
 */
@Data
public class CodingAnswerVO implements Serializable {

    /** 题目ID */
    private Long questionId;

    /** 是否答对 */
    private Boolean isCorrect;

    /** 正确选项下标 */
    private List<Integer> correctAnswer;

    /** 答案解析 */
    private String explanation;

    /** 本次获得的逐日分（答错/练习为 0） */
    private Integer scoreAwarded;

    /** 最新连续天数（仅当日一题答对时触发打卡；练习不回填） */
    private Integer continuousDays;

    /** 来源文章ID（答错时的阅读入口）；字符串下发避免雪花ID在 JS 侧精度丢失 */
    private String sourceArticleId;

    /** 来源文章标题 */
    private String sourceArticleTitle;

    /** 提交后题目累计作答次数 */
    private Integer answerCount;

    /** 提交后题目累计答对次数 */
    private Integer correctCount;
}