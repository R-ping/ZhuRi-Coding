package com.heima.model.aigc.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AIGC 水文检测记录（内容诚信治理，Step4）
 *
 * 每次检测落一条明细：综合疑似分 + 各信号值（signals_json 可审计、支撑申诉与阈值调优）。
 * 处置语义：只标不删——flagged 关闭打赏/不入 RAG 向量库/课程禁售，作者可申诉人工复核。
 */
@Data
@TableName("ap_aigc_record")
public class AigcRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 内容类型：1-文章 2-沸点 3-课程小节 */
    public static final int TYPE_ARTICLE = 1;
    public static final int TYPE_PINS = 2;
    public static final int TYPE_CHAPTER = 3;

    /** 记录状态：仅记录（低/中疑似，不做处置） */
    public static final int STATUS_RECORD = 0;
    /** 记录状态：已 flagged（高疑似，已执行处置） */
    public static final int STATUS_FLAGGED = 1;
    /** 记录状态：申诉中 */
    public static final int STATUS_APPEALING = 2;
    /** 记录状态：人工复核放行（误伤纠正） */
    public static final int STATUS_CLEARED = 3;
    /** 记录状态：人工确认水文 */
    public static final int STATUS_CONFIRMED = 4;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("content_type")
    private Integer contentType;

    @TableField("content_id")
    private Long contentId;

    @TableField("author_id")
    private Integer authorId;

    /** 综合疑似分 0-100（越高越疑似 AI 水文） */
    @TableField("score")
    private Integer score;

    /** 信号明细 JSON：burst/repeat/template/anchor/author_cos/llm_verdict */
    @TableField("signals_json")
    private String signalsJson;

    /** 检测版本/方法（stat_v1 等，便于特征迭代后对比） */
    @TableField("method")
    private String method;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
