package com.zhuri.coding.model.behavior.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 文章推荐曝光记录（数据回流闭环：曝光 → 行为）。
 * <p>
 * 推荐服务下发「推荐分栏」结果时记录每一页的曝光明细；评分阶段据此对
 * 「近期已曝光但未被消费」的文章做降权，形成负反馈闭环。
 * </p>
 */
@Data
@TableName("ap_article_exposure")
public class ApArticleExposure implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID（匿名曝光统一记 0） */
    @TableField("user_id")
    private Long userId;

    /** 文章ID */
    @TableField("article_id")
    private Long articleId;

    /** 推荐渠道：__all__/具体频道ID/follow */
    @TableField("channel")
    private String channel;

    /** 分栏：recommend/latest */
    @TableField("sub_tab")
    private String subTab;

    /** 页码（从 0 起） */
    @TableField("page")
    private Integer page;

    /** 该页内位次（从 0 起） */
    @TableField("position")
    private Integer position;

    /** 会话种子（刷新/分页锚点） */
    @TableField("seed")
    private Long seed;

    /** 曝光时间 */
    @TableField("create_time")
    private Date createTime;
}