package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 每日一题/练习题展示 VO（Coding 延展第一层）
 *
 * <p>安全口径：{@code correctAnswer / explanation / userAnswer / isCorrect} 只在
 * "用户已作答"时回填（今日题当天已答，或答题提交的即时响应）；题库列表一律不回填，
 * 避免把答案直接暴露给未作答的用户。</p>
 */
@Data
public class CodingQuestionVO implements Serializable {

    /** 题目ID */
    private Long id;

    /** 题干 */
    private String stem;

    /** 题型：1单选 2多选 */
    private Integer questionType;

    /** 选项文本数组 */
    private List<String> options;

    /** 难度：1入门 2进阶 3挑战 */
    private Integer difficulty;

    /** 知识点标签 */
    private List<String> tags;

    /** 来源：0平台 1文章AI生成 2作者投稿 */
    private Integer sourceType;

    /** 来源文章ID（解析后可跳转阅读，双向导流）；字符串下发避免雪花ID在 JS 侧精度丢失 */
    private String sourceArticleId;

    /** 来源文章标题 */
    private String sourceArticleTitle;

    /** 题目累计作答次数 */
    private Integer answerCount;

    /** 题目累计答对次数 */
    private Integer correctCount;

    /** 当前用户是否已作答（题库列表为"练过"，今日题为"今天答过"） */
    private Boolean answered;

    /** 用户作答（已作答时回填） */
    private List<Integer> userAnswer;

    /** 是否答对（已作答时回填） */
    private Boolean isCorrect;

    /** 正确选项下标（已作答时回填） */
    private List<Integer> correctAnswer;

    /** 答案解析（已作答时回填） */
    private String explanation;

    /** 作答用时（秒，已作答时回填） */
    private Integer elapsedSeconds;

    /** 本次获得的逐日分（已作答时回填） */
    private Integer scoreAwarded;
}