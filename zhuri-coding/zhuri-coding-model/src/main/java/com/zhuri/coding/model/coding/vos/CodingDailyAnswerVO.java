package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 每日一题作答结果（简答）
 *
 * <p>等级与考点清单与模拟面试报告的主题评估同构：结构与准确性两个维度各 1-5，
 * 覆盖度由服务端按 covered/(covered+missing) 重算，不采信模型自评。
 * 评估降级时为「未评估」——{@code level} 为 null、清单为空、feedback 给出说明，
 * 读取方不要把 null 当 0 分。</p>
 */
@Data
public class CodingDailyAnswerVO implements Serializable {

    private Long poolId;

    /** 综合等级 1-5；未评估为 null */
    private Integer level;

    /** 回答结构等级 1-5 */
    private Integer structure;

    /** 考点覆盖等级 1-5 */
    private Integer coverageScore;

    /** 技术准确性等级 1-5 */
    private Integer accuracy;

    /** 已覆盖考点 */
    private List<String> covered;

    /** 未覆盖考点 */
    private List<String> missing;

    /** 点评 */
    private String feedback;

    /** 本次获得的逐日分 */
    private Integer scoreAwarded;

    /** 是否未评估（评估降级） */
    private Boolean pending;
}
