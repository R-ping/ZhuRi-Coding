package com.zhuri.coding.model.coding.dtos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 每日一题作答提交（Coding 延展第一层）
 */
@Data
public class CodingAnswerDTO implements Serializable {

    /** 题目ID */
    private Long questionId;

    /** 用户选择的选项下标（单选恰好 1 个；多选至少 1 个） */
    private List<Integer> answers;

    /** 作答用时（秒，可缺省；榜单平局依据） */
    private Integer elapsedSeconds;

    /** 是否"当日一题"：true 计分计榜并触发打卡；缺省/false 为自由练习（只沉淀统计） */
    private Boolean isDaily;
}