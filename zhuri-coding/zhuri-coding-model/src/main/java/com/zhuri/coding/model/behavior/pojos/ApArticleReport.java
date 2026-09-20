package com.zhuri.coding.model.behavior.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 文章举报记录
 */
@Data
@TableName("ap_article_report")
public class ApArticleReport implements Serializable {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 举报人ID */
    @TableField("user_id")
    private Integer userId;

    /** 被举报文章ID */
    @TableField("article_id")
    private Long articleId;

    /** 被举报文章作者ID */
    @TableField("author_id")
    private Long authorId;

    /** 举报原因 */
    @TableField("reason")
    private String reason;

    /** 补充说明（≤100字） */
    @TableField("description")
    private String description;

    /** 举报图片URL（逗号分隔，最多4张） */
    @TableField("image_urls")
    private String imageUrls;

    /** 处理状态：0待处理 1已处理 */
    @TableField("status")
    private Integer status;

    /** 创建时间 */
    @TableField("created_time")
    private Date createdTime;
}
