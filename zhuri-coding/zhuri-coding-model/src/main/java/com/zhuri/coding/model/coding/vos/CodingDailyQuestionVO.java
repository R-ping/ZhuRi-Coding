package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 每日一题题面（简答）
 *
 * <p><b>不下发关键考点</b>：考点是评分锚点，提前给出去等于给了答案提纲。
 * 只有当天已作答回放时才带上等级与考点清单。</p>
 */
@Data
public class CodingDailyQuestionVO implements Serializable {

    /** 题目池ID */
    private Long id;

    /** 题干 */
    private String stem;

    /** 方向 */
    private String direction;

    /** 难度 1入门 2进阶 3挑战 */
    private Integer difficulty;

    /** 知识点标签 */
    private List<String> tags;

    /** 今天是否已作答 */
    private Boolean answered;

    // ---- 以下仅当日已答回放时填充 ----

    /** 我的作答 */
    private String userAnswer;

    /** 综合等级 1-5 */
    private Integer level;

    /** 点评 */
    private String feedback;

    /** 已覆盖考点 */
    private List<String> covered;

    /** 未覆盖考点 */
    private List<String> missing;

    /** 作答用时（秒） */
    private Integer elapsedSeconds;

    /** 当日获得的逐日分 */
    private Integer scoreAwarded;
}
