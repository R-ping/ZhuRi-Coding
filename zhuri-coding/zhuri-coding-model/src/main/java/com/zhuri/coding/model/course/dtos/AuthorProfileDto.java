package com.zhuri.coding.model.course.dtos;

import lombok.Data;

/**
 * 作者基础信息 DTO：用于申请小册时保存/回填作者个人基础信息。
 * 不含 id/userId/时间字段（由服务端维护）。
 */
@Data
public class AuthorProfileDto {
    /** 姓名 */
    private String realName;
    /** 个人职位/职业 */
    private String position;
    /** 个人履历/简介 */
    private String resume;
    /** 申请理由 */
    private String applyReason;
    /** 联系方式-微信 */
    private String contactWechat;
    /** 联系方式-常用邮箱 */
    private String contactEmail;
    /** 掘金账号及其他博客/技术媒体 */
    private String blogs;
    /** 个人自我介绍（用于小册作者页展示） */
    private String personalIntro;
}