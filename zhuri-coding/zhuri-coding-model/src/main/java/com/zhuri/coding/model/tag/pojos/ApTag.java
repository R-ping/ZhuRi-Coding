package com.heima.model.tag.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * <p>
 * 标签表
 * </p>
 *
 * @author itheima
 */
@Data
@TableName("ap_tag")
public class ApTag implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 标签名称
     */
    @TableField("name")
    private String name;

    /**
     * 标签分类(语言方向/技术栈/数据库/其它)
     */
    @TableField("category")
    private String category;

    /**
     * 排序
     */
    @TableField("sort")
    private Integer sort;

    /**
     * 状态 1启用 0禁用
     */
    @TableField("status")
    private Integer status;

    /**
     * 创建时间
     */
    @TableField("created_time")
    private Date createdTime;

    @TableField("post_article_count")
    private Integer postArticleCount;

    @TableField("concern_user_count")
    private Integer concernUserCount;

}