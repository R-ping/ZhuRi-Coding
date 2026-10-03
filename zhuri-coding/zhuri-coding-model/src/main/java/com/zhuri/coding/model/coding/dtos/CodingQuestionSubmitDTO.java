package com.zhuri.coding.model.coding.dtos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 作者投稿题目（Coding 延展第一层 · 题库第二条来源）
 *
 * <p>投稿走"格式校验 → 题干查重 → 一次 AI 质检"后自动上架或驳回，
 * articleId 可选：填写时须为投稿人自己的已发布文章（题目标注来源文章与出题人）。</p>
 */
@Data
public class CodingQuestionSubmitDTO implements Serializable {

    /** 题干 */
    private String stem;

    /** 题型：1单选 2多选 */
    private Integer questionType;

    /** 选项文本数组（2~8 个） */
    private List<String> options;

    /** 正确选项下标数组（单选恰好 1 个；多选至少 2 个） */
    private List<Integer> answer;

    /** 答案解析（可选） */
    private String explanation;

    /** 难度：1入门 2进阶 3挑战 */
    private Integer difficulty;

    /** 知识点标签（逗号分隔，可选） */
    private String tags;

    /** 来源文章ID（可选，须为投稿人自己的已发布文章） */
    private Long articleId;
}