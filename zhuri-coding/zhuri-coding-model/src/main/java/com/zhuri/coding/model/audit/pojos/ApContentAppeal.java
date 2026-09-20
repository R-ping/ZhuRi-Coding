package com.zhuri.coding.model.audit.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 内容治理申诉实体（AI 预审 + 人工终审）
 *
 * <p>让治理误伤可纠正：评论折叠（is_hidden）/ 文章 AIGC 误标（is_aigc）的归属者
 * 可发起申诉；AI 预审只给建议（ai_verdict，不终决），终审权在人工/运营。
 */
@Data
@TableName("ap_content_appeal")
public class ApContentAppeal implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 申诉对象：评论折叠 */
    public static final int TYPE_COMMENT_HIDDEN = 1;
    /** 申诉对象：文章 AIGC 误标 */
    public static final int TYPE_ARTICLE_AIGC = 2;

    /** 状态：待人工终审 */
    public static final int STATUS_PENDING = 0;
    /** 状态：已解除（allow） */
    public static final int STATUS_ALLOWED = 1;
    /** 状态：已驳回（uphold 维持原处置） */
    public static final int STATUS_REJECTED = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("appeal_type")
    private Integer appealType;

    @TableField("content_id")
    private Long contentId;

    @TableField("applicant_id")
    private Integer applicantId;

    @TableField("reason")
    private String reason;

    /** AI 预审 JSON：{"suggest":"allow"|"uphold","score":0-100,"reason":"..."} */
    @TableField("ai_verdict")
    private String aiVerdict;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
