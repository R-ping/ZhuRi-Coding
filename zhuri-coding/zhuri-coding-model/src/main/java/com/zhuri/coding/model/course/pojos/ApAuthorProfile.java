package com.zhuri.coding.model.course.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_author_profile")
public class ApAuthorProfile implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 姓名 */
    @TableField("real_name")
    private String realName;

    /** 个人职位/职业 */
    @TableField("position")
    private String position;

    /** 个人履历/简介 */
    @TableField("resume")
    private String resume;

    /** 申请理由 */
    @TableField("apply_reason")
    private String applyReason;

    /** 联系方式-微信 */
    @TableField("contact_wechat")
    private String contactWechat;

    /** 联系方式-常用邮箱 */
    @TableField("contact_email")
    private String contactEmail;

    /** 掘金账号及其他博客/技术媒体 */
    @TableField("blogs")
    private String blogs;

    /** 个人自我介绍（用于小册作者页展示） */
    @TableField("personal_intro")
    private String personalIntro;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}