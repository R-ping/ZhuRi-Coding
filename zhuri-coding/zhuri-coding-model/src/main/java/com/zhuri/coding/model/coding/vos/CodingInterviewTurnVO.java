package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 模拟面试轮次结果（SSE done 事件载荷，Coding 延展第三层 · Stage A）
 *
 * <p>kind=followup 时 text 为追问全文；kind=next 时 text 为服务端拼装的下一题
 * （固定过渡语 + 提纲下一主题主问题；主题耗尽时为收尾语且 completed=true）。
 * 前端在 completed=true 时自动调用 finish 生成报告。</p>
 */
@Data
public class CodingInterviewTurnVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本轮面试官完整发言（追问或下一题） */
    private String text;

    /** 发言类型：followup=追问 next=下一题/收尾 */
    private String kind;

    /** 发言所属主题下标（next 为新的主题下标；收尾为最后一个主题） */
    private Integer topicIndex;

    /** 本轮提交后的用户作答轮数（下轮 turnSeq 以此为基准） */
    private Integer turnCount;

    /** 全部主题是否已答完（true 时前端自动调 finish） */
    private Boolean completed;
}