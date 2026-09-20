package com.heima.model.ai.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 反馈实体（👍/👎，反馈闭环）
 *
 * <p>幂等：同 user+feature+scene_id+question_hash 唯一，重复反馈走更新。
 */
@Data
@TableName("ap_ai_feedback")
public class AiFeedback implements Serializable {

    private static final long serialVersionUID = 1L;

    /** AI 功能标识：社区问答 */
    public static final String FEATURE_AIASK_GLOBAL = "aiask_global";
    /** AI 功能标识：单篇问答 */
    public static final String FEATURE_AIASK_ARTICLE = "aiask_article";
    /** AI 功能标识：AI 摘要 */
    public static final String FEATURE_SUMMARY = "summary";

    /** 反馈：有帮助 */
    public static final int FEEDBACK_UP = 1;
    /** 反馈：没帮助/有误 */
    public static final int FEEDBACK_DOWN = -1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Integer userId;

    @TableField("feature")
    private String feature;

    @TableField("scene_id")
    private String sceneId;

    @TableField("question_hash")
    private String questionHash;

    @TableField("question")
    private String question;

    @TableField("answer")
    private String answer;

    @TableField("feedback")
    private Integer feedback;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
