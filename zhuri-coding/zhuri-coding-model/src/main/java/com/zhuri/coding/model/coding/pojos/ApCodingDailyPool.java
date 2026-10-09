package com.zhuri.coding.model.coding.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 每日一题题目池（Coding 延展第一层 · 简答）
 *
 * <p><b>为什么题干和关键考点是人工维护的</b>：简答题没有唯一答案，评估必须有锚点。
 * 由模型同时出题和判分，等于"自己出题自己批"，评分可信度最低。
 * 所以 {@code key_points} 是这一层的生命线，LLM 只负责「对照考点判断讲到没讲到」。</p>
 *
 * <p><b>为什么不怕题目被答完</b>：简答题重复练仍有价值（这次讲得比上次清楚才算真会）。
 * 因此题库不需要无限大，几十道轮着来即可 —— 这与选择题时代完全不同，
 * 也是 {@code use_count} 只做轮转、不做"未答过优先"的原因。</p>
 */
@Data
@TableName("ap_coding_daily_pool")
public class ApCodingDailyPool implements Serializable {

    /** 难度：入门 */
    public static final int DIFFICULTY_EASY = 1;
    /** 难度：进阶 */
    public static final int DIFFICULTY_MEDIUM = 2;
    /** 难度：挑战 */
    public static final int DIFFICULTY_HARD = 3;

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 1;
    /** 状态：停用 */
    public static final int STATUS_DISABLED = 0;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 方向（与模拟面试 direction 同口径） */
    @TableField("direction")
    private String direction;

    /** 题干 */
    @TableField("stem")
    private String stem;

    /** 关键考点（JSON 字符串数组，评分锚点） */
    @TableField("key_points")
    private String keyPoints;

    /** 参考答案要点（仅喂模型参考，不下发用户） */
    @TableField("reference_answer")
    private String referenceAnswer;

    /** 难度（池子自带，用户不选） */
    @TableField("difficulty")
    private Integer difficulty;

    /** 知识点标签（逗号分隔，用于领域分布累计） */
    @TableField("tags")
    private String tags;

    /** 状态：1启用 0停用 */
    @TableField("status")
    private Integer status;

    /** 被抽次数（轮转用，越小越优先） */
    @TableField("use_count")
    private Integer useCount;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}
