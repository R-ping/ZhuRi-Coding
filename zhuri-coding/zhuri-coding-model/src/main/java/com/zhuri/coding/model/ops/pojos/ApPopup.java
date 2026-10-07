package com.zhuri.coding.model.ops.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 运营位弹窗公告（全站弹窗）。
 *
 * <p><b>时间窗为什么 NOT NULL（与 Banner 相反）</b>：弹窗是投放语义 —— 到点出现、到点消失，
 * "永久弹窗"等于骚扰；而且用户关闭记录（Redis）的 TTL 上限就取 {@code end_time}，
 * 没有截止就没有 TTL。所以弹窗必须有窗口，Banner 才允许不限。
 *
 * <p><b>content 为什么是纯文本、不收 HTML</b>：弹窗不是富文本场景（没有排版需求，
 * 只有"一段话 + 可选配图 + 可选按钮"）。收 HTML 就要在出参处为转义负责 ——
 * 运营粘进一段带 script 的内容，受害的是全站用户。不收，就没有这层责任要背。
 */
@Data
@TableName("ap_popup")
public class ApPopup implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：停用（含草稿） */
    public static final int STATUS_DISABLED = 0;
    /** 状态：启用 */
    public static final int STATUS_ENABLED = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("title")
    private String title;

    /** 正文纯文本；可空（纯图弹窗） */
    @TableField("content")
    private String content;

    /** 可选配图 */
    @TableField("image_url")
    private String imageUrl;

    /** 动作按钮文案；NULL = 只有关闭 */
    @TableField("button_text")
    private String buttonText;

    /** 动作按钮跳转；站内路由（/ 开头）或 http(s) 外链 */
    @TableField("link_url")
    private String linkUrl;

    @TableField("status")
    private Integer status;

    /** 生效开始（NOT NULL） */
    @TableField("start_time")
    private Date startTime;

    /** 生效结束（NOT NULL；关闭记录 TTL 上限） */
    @TableField("end_time")
    private Date endTime;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}
