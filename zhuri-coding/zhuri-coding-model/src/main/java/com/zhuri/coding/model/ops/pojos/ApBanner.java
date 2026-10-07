package com.zhuri.coding.model.ops.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 运营位 Banner（首页轮播）。
 *
 * <p><b>为什么标题是运营备注而不是展示文案</b>：Banner 的信息在图里，列表上一排
 * "banner1/banner2" 运营自己都分不清；展示语义由图承担，{@code title} 负责"这条是谁、投的什么"。
 *
 * <p><b>时间窗为什么可空</b>：Banner 大多长期挂着，手动启停足够；时间窗是给
 * "节日活动 Banner"这种到点自动上下线的场景用的，两个方向都必须能"不限"。
 * （弹窗则相反：投放语义必须有截止，见 {@link ApPopup}。）
 */
@Data
@TableName("ap_banner")
public class ApBanner implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：停用（含草稿——新建一律停用，启用是独立动作） */
    public static final int STATUS_DISABLED = 0;
    /** 状态：启用 */
    public static final int STATUS_ENABLED = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 运营备注名（列表辨认用，不是展示文案） */
    @TableField("title")
    private String title;

    @TableField("image_url")
    private String imageUrl;

    /** 跳转地址：站内路由（/ 开头）或 http(s) 外链；其他协议在服务层被拒（防 javascript: 注入） */
    @TableField("link_url")
    private String linkUrl;

    /** 展示顺序，小的在前 */
    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("status")
    private Integer status;

    /** 生效开始；NULL = 不限 */
    @TableField("start_time")
    private Date startTime;

    /** 生效结束；NULL = 不限 */
    @TableField("end_time")
    private Date endTime;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}
