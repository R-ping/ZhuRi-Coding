package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 用户社交账号绑定表
 * </p>
 *
 * <pre>
 * DDL:
 * CREATE TABLE ap_user_social (
 *     id              INT UNSIGNED AUTO_INCREMENT COMMENT '主键' PRIMARY KEY,
 *     user_id         INT UNSIGNED     NOT NULL COMMENT '用户ID（关联ap_user.id）',
 *     platform        VARCHAR(20)      NOT NULL COMMENT '平台: github / weibo / wechat',
 *     platform_uid    VARCHAR(128)     NOT NULL COMMENT '三方平台唯一标识（uid/openId）',
 *     platform_name   VARCHAR(64)      NULL     COMMENT '三方平台昵称',
 *     avatar          VARCHAR(512)     NULL     COMMENT '三方平台头像URL',
 *     access_token    VARCHAR(512)     NULL     COMMENT '三方平台access_token（加密存储）',
 *     created_time    DATETIME         NULL     COMMENT '绑定时间',
 *     UNIQUE KEY uk_platform_uid (platform, platform_uid),
 *     KEY idx_user_id (user_id)
 * ) COMMENT '用户社交账号绑定表';
 * </pre>
 *
 * @author itheima
 */
@Data
@TableName("ap_user_social_binding")
public class ApUserSocial implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 用户ID（关联ap_user.id）
     */
    @TableField("user_id")
    private Integer userId;
    @TableField("phone")
    private String phone;
    /**
     * 平台: github / weibo / wechat
     *  github;weibo;wechat;
     */
    @TableField("platform")
    private String platform;

    /**
     * 三方平台唯一标识（uid/openId）
     */
    // 不是数据库字段
    @TableField(exist = false)
    private String platformUid;

    @TableField("git_uid")
    private String gitUid;
    @TableField("weibo_uid")
    private String weiboUid;
    @TableField("open_id")
    private String openId;
    /**
     * 绑定时间
     */
    @TableField("created_time")
    private Date createdTime;
}
