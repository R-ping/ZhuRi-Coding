package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 能力测评试卷（开卷/续答返回，Coding 延展第二层 · Stage B）
 *
 * <p><b>不含答案</b>：正确答案只存于服务端组卷快照，交卷后由成绩单回放。</p>
 */
@Data
public class CodingAssessmentPaperVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 测评记录ID */
    private Long assessmentId;

    /** 题面列表（卷面顺序） */
    private List<PaperQuestion> questions;

    /** 截止时间（yyyy-MM-dd HH:mm:ss，展示用） */
    private String deadlineTime;

    /** 剩余作答秒数（倒计时以服务端为准，避免客户端时钟偏差） */
    private Integer remainingSeconds;

    /** 限时总秒数 */
    private Integer durationSeconds;

    /** true=续答进行中的卷（刷新恢复），false=本次新开卷 */
    private Boolean resumed;

    @Data
    public static class PaperQuestion implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long questionId;

        private String stem;

        /** 题型：1单选 2多选 */
        private Integer questionType;

        /** 选项文本列表 */
        private List<String> options;

        /** 难度：1入门 2进阶 3挑战 */
        private Integer difficulty;

        /** 知识点标签 */
        private List<String> tags;
    }
}