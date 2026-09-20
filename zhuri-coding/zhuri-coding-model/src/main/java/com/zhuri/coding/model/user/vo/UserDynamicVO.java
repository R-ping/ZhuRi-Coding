package com.heima.model.user.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 个人主页动态记录 VO
 * 用于展示用户的行为动态（点赞文章/沸点、关注用户、发布文章/沸点），按时间线降序排列
 */
@Data
public class UserDynamicVO implements Serializable {

    /** 行为记录ID */
    private Long id;

    /** 行为类型编码，如 like_article / follow_user / publish_pins */
    private String behaviorType;

    /** 行为描述，如 点赞了文章 / 关注了用户 / 发布了沸点 */
    private String behaviorDesc;

    /** 动作分类：like-点赞 follow-关注 publish-发布 */
    private String actionCategory;

    /** 目标类型：1-文章 2-沸点 3-用户 */
    private Integer targetType;

    /** 目标ID */
    private Long targetId;

    /** 目标标题（文章标题/沸点内容/用户昵称） */
    private String targetTitle;

    /** 目标封面（文章封面/沸点图片/用户头像） */
    private String targetCover;

    /** 目标跳转地址 */
    private String targetUrl;

    /** 目标附属信息（浏览量等） */
    private String targetMeta;

    /** 行为发生时间 */
    private Date createdTime;
}
