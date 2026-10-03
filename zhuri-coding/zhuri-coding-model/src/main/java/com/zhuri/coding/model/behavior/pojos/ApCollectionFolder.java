package com.zhuri.coding.model.behavior.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户收藏夹（F4）
 *
 * <p>收藏可归属到自定义收藏夹；未指定归属的收藏用 {@code ap_collection.folder_id IS NULL}
 * 表示"默认收藏夹"，不在本表落数据。名称 1-20 字、同用户不可重名（唯一索引兜底）。</p>
 */
@Data
@TableName("ap_collection_folder")
public class ApCollectionFolder implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Integer userId;

    /** 收藏夹名称（1-20字） */
    @TableField("name")
    private String name;

    /** 排序值（升序，越小越靠前；新建时取当前最大值+1） */
    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}