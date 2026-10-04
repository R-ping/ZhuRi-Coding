package com.zhuri.coding.model.coding.dtos;

import lombok.Data;

import java.io.Serializable;

/**
 * 模拟面试轮次作答入参（Coding 延展第三层 · Stage A）
 *
 * <p>turnSeq = 客户端已见的用户作答轮数（服务端 turn_count），用于原子防重：
 * 重复提交/双击（turnSeq 与 DB 不一致）会被拒绝，避免重复扣费与对话错乱。</p>
 */
@Data
public class CodingInterviewTurnDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 面试记录ID */
    private Long interviewId;

    /** 本轮回答文本 */
    private String answer;

    /** 客户端已见轮数（服务端 turn_count 防重基准） */
    private Integer turnSeq;
}