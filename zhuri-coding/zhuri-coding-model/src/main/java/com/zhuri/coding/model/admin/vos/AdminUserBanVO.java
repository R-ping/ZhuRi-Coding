package com.zhuri.coding.model.admin.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 封禁名单 / 处置对象视图。
 *
 * <p>刻意只带出「昵称 + 头像 + 封禁四要素」，不带手机号、邮箱 —— 封禁是内容治理动作，
 * 看过账号是否违规就够，没有理由把联系方式摊在运营后台的列表页上（那是最容易被截屏外传的地方）。
 */
@Data
public class AdminUserBanVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 账号ID */
    private Integer userId;

    private String nickname;

    private String avatar;

    /** 封禁截止时间；永久封禁为远期时间 */
    private Date banUntil;

    /**
     * 是否永久封禁。
     *
     * <p>单列一个布尔位而不是让前端去判断"年份是不是 9999"：远期时间是实现细节，
     * 前端不该知道它长什么样，否则换个实现方式（比如改成 null）前端就得跟着改。
     */
    private boolean permanent;

    /** 封禁理由 */
    private String banReason;

    /** 封禁时间 */
    private Date banTime;

    /** 执行封禁的运营账号ID */
    private Integer banOperatorId;

    /** 封禁操作人昵称（列表展示用；查不到时为 null） */
    private String banOperatorNickname;
}
