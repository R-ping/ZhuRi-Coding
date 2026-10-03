package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 模拟面试报告视图（报告接口返回，Coding 延展第三层 · Stage A）
 *
 * <p>评分口径（宁缺勿假）：三维各给 1-5 等级 + 依据（逐题 comment 引用回答事实），
 * 不给百分制总分；{@code overallScore} 为三维均值四舍五入，仅供列表与档案块展示。</p>
 *
 * <p>覆盖度先行：逐题先判 keyPoints 覆盖（covered/missing 清单），再评结构与准确性。</p>
 *
 * <p><b>结构化失败降级</b>：{@code reportReady=false} 时结构化字段为空，{@code rawText} 存模型原文，
 * 前端以纯文本展示（报告不丢）。</p>
 */
@Data
public class CodingInterviewReportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 面试记录ID */
    private Long interviewId;

    /** 面试方向 */
    private String direction;

    /** 难度：1入门 2进阶 3挑战 */
    private Integer difficulty;

    /** 状态：1进行中 2已完成 3已过期 */
    private Integer status;

    /** 综合等级（1-5；结构化失败时为 null） */
    private Integer overallScore;

    /** 报告是否可用（结构化解析成功） */
    private Boolean reportReady;

    /** 主题总数 */
    private Integer totalTopics;

    /** 已答主题数（完成的主题个数，提前结束 < totalTopics） */
    private Integer completedTopics;

    /** 开面时间（yyyy-MM-dd HH:mm:ss，展示用） */
    private String startedTime;

    /** 截止时间（同上） */
    private String deadlineTime;

    /** 结束时间（同上） */
    private String finishedTime;

    /** 真实用时（秒） */
    private Integer durationSeconds;

    /** 逐题点评（结构化可用时） */
    private List<ReportItem> items;

    /** 总评（结构化可用时） */
    private String overall;

    /** 改进建议（结构化可用时） */
    private List<String> suggestions;

    /** 模型原文（结构化失败时展示；成功时为 null） */
    private String rawText;

    /** 全量对话流水（回放用） */
    private List<CodingInterviewSessionVO.TurnItem> turns;

    @Data
    public static class ReportItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 主题名 */
        private String topic;

        /** 回答结构等级（1-5） */
        private Integer structure;

        /** 覆盖度等级（1-5，按 keyPoints 覆盖比例映射） */
        private Integer coverageScore;

        /** 覆盖考点清单（覆盖度先行的依据） */
        private Coverage coverage;

        /** 技术准确性等级（1-5） */
        private Integer accuracy;

        /** 点评（含依据：引用回答事实或覆盖/遗漏清单） */
        private String comment;
    }

    @Data
    public static class Coverage implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 已覆盖的关键考点 */
        private List<String> covered;

        /** 未覆盖的关键考点 */
        private List<String> missing;
    }
}