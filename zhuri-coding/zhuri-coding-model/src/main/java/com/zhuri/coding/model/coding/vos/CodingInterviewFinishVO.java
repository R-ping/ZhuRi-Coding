package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 模拟面试结束返回（Coding 延展第三层 · Stage A）
 *
 * <p>报告解析失败（或生成超时/失败）时 {@code reportReady=false}，前端提示"报告生成失败可重试"——
 * 重试走 finish 幂等回放（服务端检测到报告缺失/不可解析会重新生成）。</p>
 */
@Data
public class CodingInterviewFinishVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 面试记录ID */
    private Long interviewId;

    /** 面试方向 */
    private String direction;

    /** 综合等级（1-5；报告不可用时为 null） */
    private Integer overallScore;

    /** 报告是否可用（结构化解析成功） */
    private Boolean reportReady;

    /** 结束时间（yyyy-MM-dd HH:mm:ss，展示用） */
    private String finishedTime;
}