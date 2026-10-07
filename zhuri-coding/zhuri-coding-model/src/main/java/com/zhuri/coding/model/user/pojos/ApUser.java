package com.zhuri.coding.model.user.pojos;

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
    @TableId(value = "id", type = IdType.AUTO)
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

    // ==================== 运营封禁字段 ====================
    //
    // 为什么另开一组字段、不复用 status：
    //   status=0 在系统里的语义是「注销/锁定」—— getValidUserIds 按它把账号从"批量投递"
    //   名单里剔除。把封禁塞进 status，被封用户看起来就跟自己注销了一样，
    //   运营既分不清是"自己注销的"还是"被平台封的"，也就无从解封。
    //
    // 为什么用"截止时间"而不是布尔位：
    //   ban_until > now 就是判定，到期自动失效；永久封禁写入远期时间（见 UserBanServiceImpl），
    //   于是永远不需要一个定时任务去把到期账号挨个解开 —— 少一个会漏跑的组件。

    /**
     * 封禁截止时间：{@code > now} 表示当前处于封禁态；null 表示未被运营封禁。
     *
     * <p>永久封禁写远期时间而不是留空，这样"是否封禁中"永远只是一次比较。
     */
    @TableField("ban_until")
    private Date banUntil;

    /**
     * 封禁理由（对用户展示，上限 500 与审计表 reason 列等长）。
     *
     * <p>解封时会被清空 —— 它描述的是"当前这次封禁"，历史留在 {@code ap_admin_audit_log} 里。
     */
    @TableField("ban_reason")
    private String banReason;

    /** 本次封禁的操作时间（不是"最近更新时间"） */
    @TableField("ban_time")
    private Date banTime;

    /** 执行封禁的运营账号ID */
    @TableField("ban_operator_id")
    private Integer banOperatorId;

    // ==================== 辅助方法 ====================

    /**
     * 账户是否正常（未锁定）
     */
    public boolean isActive() {
        return Boolean.TRUE.equals(this.status);
    }

    /**
     * 账户是否已锁定
     */
    public boolean isLocked() {
        return !Boolean.TRUE.equals(this.status);
    }

    /**
     * 是否为普通用户
     */
    public boolean isNormalUser() {
        return this.flag == null || this.flag == 0;
    }

    /**
     * 是否为自媒体人
     */
    public boolean isMediaUser() {
        return this.flag != null && this.flag == 1;
    }

    /**
     * 是否为大V用户
     */
    public boolean isVipUser() {
        return this.flag != null && this.flag == 2;
    }

    /**
     * 在指定时刻是否处于封禁态。
     *
     * <p>判定只有这一份（运营侧的封禁名单 SQL 与登录侧的内存校验共用同一语义，
     * 见 {@code UserBanServiceImpl}）—— 两处各写一遍迟早在"到期那一秒算不算封禁"上分叉。
     *
     * @param now 判定基准时刻；传 null 取当前时间
     */
    public boolean isBannedAt(Date now) {
        if (this.banUntil == null) {
            return false;
        }
        return this.banUntil.after(now == null ? new Date() : now);
    }

}