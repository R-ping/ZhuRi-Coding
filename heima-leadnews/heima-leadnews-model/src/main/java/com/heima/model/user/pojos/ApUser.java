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
 * APP用户信息表（重新设计，支持社交登录 + BCrypt加密）
 * </p>
 *
 * <pre>
 * DDL:
 * CREATE TABLE ap_user (
 *     id                          INT UNSIGNED AUTO_INCREMENT COMMENT '主键' PRIMARY KEY,
 *     name                        VARCHAR(20)      NULL COMMENT '用户名',
 *     password                    VARCHAR(128)     NULL COMMENT '密码（BCrypt加密）',
 *     phone                       VARCHAR(11)      NULL COMMENT '手机号',
 *     email                       VARCHAR(50)      NULL COMMENT '邮箱',
 *     image                       VARCHAR(255)     NULL COMMENT '头像',
 *     sex                         TINYINT UNSIGNED NULL COMMENT '0 男 1 女 2 未知',
 *     is_certification            TINYINT UNSIGNED NULL COMMENT '0 未 1 是',
 *     is_identity_authentication  TINYINT(1)       NULL COMMENT '是否身份认证',
 *     status                      TINYINT UNSIGNED NULL COMMENT '1正常 0锁定',
 *     flag                        TINYINT UNSIGNED NULL COMMENT '0 普通用户 1 自媒体人 2 大V',
 *     created_time                DATETIME         NULL COMMENT '注册时间'
 * ) COMMENT '用户信息表';
 * </pre>
 *
 * CREATE TABLE ap_user_social_binding (
 *      id                          INT UNSIGNED AUTO_INCREMENT COMMENT '主键' PRIMARY KEY,
 *      userId                      INT UNSIGNED     NULL COMMENT '用户ID',
 *      platform                    VARCHAR(20)      NULL COMMENT '平台',
 *      gitUid                     varchar(100)    null comment '用户在三方平台的唯一身份标识，通常github等需用户授权+回调的平台会使用'
 *      weiboUid                    VARCHAR(100)     NULL COMMENT '用户在微博平台的唯一身份标识，通常微博平台使用',
 *     openId                      VARCHAR(100)     NULL COMMENT '开放ID,通常微信公众号平台使用',
 *     createdTime                 DATETIME         NULL COMMENT '创建时间'
 * ) COMMENT '用户社交绑定表';
 *  }
 * @author itheima
 */
@Data
@TableName("ap_user")
public class ApUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Integer id;
    /**
     * 昵称
     */
    @TableField("nickname")
    private String nickname;

    /**
     * 密码（BCryptPasswordEncoder加密，长度128）
     */
    @TableField("password")
    private String password;

    /**
     * 用户名、手机号（全局唯一，用于登录和绑定）
     */
    @TableField("phone")
    private String phone;

    /**
     * 邮箱
     */
    @TableField("email")
    private String email;
    /**
     * 头像
     */
    @TableField("image")
    private String image;

    /**
     * 0 男
     * 1 女
     * 2 未知
     */
    @TableField("sex")
    private Boolean sex;

    /**
     * 0 未
     * 1 是
     */
    @TableField("is_certification")
    private Boolean certification;

    /**
     * 是否身份认证
     */
    @TableField("is_identity_authentication")
    private Boolean identityAuthentication;

    /**
     * 1正常
     * 0锁定
     */
    @TableField("status")
    private Boolean status;

    /**
     * 0 普通用户
     * 1 自媒体人
     * 2 大V
     */
    @TableField("flag")
    private Short flag;

    /**
     * 注册时间
     */
    @TableField("created_time")
    private Date createdTime;

}