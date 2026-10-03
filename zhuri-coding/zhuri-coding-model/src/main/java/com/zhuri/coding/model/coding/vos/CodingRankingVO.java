package com.zhuri.coding.model.coding.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 每日一题榜单条目 VO（Coding 延展第一层）
 *
 * <p>排序口径：答对题数优先 → 正确率 → 平均用时；只统计"当日一题"，练习不计入。</p>
 */
@Data
public class CodingRankingVO implements Serializable {

    /** 名次（从 1 开始） */
    private Integer rank;

    /** 用户ID */
    private Integer userId;

    /** 昵称（用户服务不可用时为空串） */
    private String nickname;

    /** 头像 */
    private String avatar;

    /** 周期内答对题数 */
    private Integer correctCount;

    /** 周期内作答题数 */
    private Integer totalCount;

    /** 正确率（百分比整数，0-100） */
    private Integer accuracy;

    /** 平均用时（秒，缺省用时按最大值兜底后取整） */
    private Integer avgSeconds;

    /** 是否当前登录用户自己（未登录恒为 false） */
    private Boolean isSelf;
}