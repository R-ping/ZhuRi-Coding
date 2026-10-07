package com.zhuri.coding.model.admin.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 运营后台 · 圈子条目（人气位清单与候选列表共用）。
 *
 * <p><b>为什么候选项与已配置项共用一个 VO</b>：运营在搜索结果里最需要知道的一件事，
 * 就是"这个圈子是不是已经在人气位上了"—— 不知道就会重复添加，然后得到一个
 * "配置未变化"的报错却想不明白为什么。{@link #displayOrder} 为 {@code null}
 * 表示当前不在人气位，非空表示已在第几位，一个字段同时回答了这两个问题。
 */
@Data
public class AdminOpsCircleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 圈子ID */
    private Long circleId;

    /** 圈子名称 */
    private String name;

    /** 成员数 */
    private Integer memberCount;

    /** 沸点数 */
    private Integer pinsCount;

    /**
     * 人气位次（从 1 开始）。
     *
     * <p>{@code null} = 该圈子当前不在人气位（候选列表里的多数情况）。
     */
    private Integer displayOrder;
}
