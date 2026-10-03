package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 能力测评成绩单（交卷返回/最近成绩回放，Coding 延展第二层 · Stage B）
 */
@Data
public class CodingAssessmentResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 测评记录ID */
    private Long assessmentId;

    /** 得分（0-100，correct/total 归一化） */
    private Integer score;

    /** 答对题数 */
    private Integer correctCount;

    /** 总题数 */
    private Integer totalCount;

    /** 百分位（样本不足时为 null/0，前端仅展示 >0 的值） */
    private Integer percentile;

    /** 领域分布（{"Redis":{"total":2,"correct":1}}，与答题统计同格式） */
    private Map<String, Object> domainStats;

    /** 逐题解析（卷面顺序） */
    private List<ResultItem> items;

    /** 交卷时间（yyyy-MM-dd HH:mm:ss） */
    private String submittedTime;

    @Data
    public static class ResultItem implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long questionId;

        private String stem;

        /** 用户答案下标（未作答为空列表） */
        private List<Integer> userAnswer;

        /** 正确答案下标 */
        private List<Integer> correctAnswer;

        private Boolean correct;

        private String explanation;

        /** 知识点标签 */
        private List<String> tags;
    }
}