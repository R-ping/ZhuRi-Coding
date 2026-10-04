package com.zhuri.coding.model.coding.dtos;

import lombok.Data;

import java.io.Serializable;

/**
 * 模拟面试结束入参（Coding 延展第三层 · Stage A）
 *
 * <p>结束（正常或提前）触发报告生成；重复调用幂等回放已生成的报告。</p>
 */
@Data
public class CodingInterviewFinishDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 面试记录ID */
    private Long interviewId;
}