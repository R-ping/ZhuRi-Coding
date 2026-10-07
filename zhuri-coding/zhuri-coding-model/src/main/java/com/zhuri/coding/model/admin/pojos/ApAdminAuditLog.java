package com.zhuri.coding.model.admin.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 运营操作审计日志（ap_admin_audit_log，库：leadnews_article）。
 *
 * <p>记录「谁在何时对什么做了什么、为什么」。运营动作会直接改变他人内容或账号状态，
 * 必须可追溯。**每个服务本地各建一份同名表**，不走跨服务集中写入——审计是绝对不能丢的记录，
 * 集中写会引入"审计服务挂了但业务照常执行、于是没留痕"的窗口。
 *
 * <p>注意 {@code reason} 与 {@code result} 两个字段的取舍：
 * <ul>
 *   <li>{@code reason} 必填（NOT NULL）：理由事后补不回来，操作当时不写，事后就不会写。</li>
 *   <li>{@code result} 失败也记：审计的价值有一半在"谁试过但没成功"。</li>
 * </ul>
 */
@Data
@TableName("ap_admin_audit_log")
public class ApAdminAuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模块：举报 */
    public static final String MODULE_REPORT = "REPORT";
    /** 模块：内容 */
    public static final String MODULE_CONTENT = "CONTENT";
    /** 模块：用户 */
    public static final String MODULE_USER = "USER";
    /** 模块：运营位 */
    public static final String MODULE_OPS = "OPS";
    /** 模块：运营账号与角色（登录会话、角色授予回收） */
    public static final String MODULE_ADMIN = "ADMIN";
    /**
     * 模块：访问控制。
     *
     * <p>用于记录「尝试做什么但被权限挡住」。它不是业务动作，但审计价值不低于业务动作：
     * 一个反复尝试下架却屡屡被拒的账号，本身就是需要被看到的信息。
     */
    public static final String MODULE_ACCESS = "ACCESS";

    /** 动作：因权限不足被拒 */
    public static final String ACTION_ACCESS_DENIED = "ACCESS_DENIED";

    /** 结果：成功 */
    public static final int RESULT_SUCCESS = 1;
    /** 结果：失败 */
    public static final int RESULT_FAIL = 0;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 操作人**运营账号**ID（{@code ap_admin_account.id}）。
     *
     * <p>本表的每一行都只可能由运营请求写入，所以这个字段的取值全部来自运营账号的 ID 空间，
     * 与 C 端账号 ID 没有交集 —— 查这张表时不必再去区分"这个 id 是人还是运营"。
     * 列名沿用历史命名 {@code user_id}。
     */
    @TableField("user_id")
    private Integer userId;

    /** 操作时的角色快照（逗号分隔）——角色可能事后被回收，故固化当时身份 */
    @TableField("role_codes")
    private String roleCodes;

    /** 业务模块：REPORT / CONTENT / USER / OPS */
    @TableField("module")
    private String module;

    /** 动作编码，如 REPORT_IGNORE / REPORT_TAKE_DOWN */
    @TableField("action")
    private String action;

    /** 对象类型：ARTICLE / COMMENT / PINS / USER */
    @TableField("target_type")
    private String targetType;

    /** 对象ID（字符串以兼容不同主键形态） */
    @TableField("target_id")
    private String targetId;

    /** 操作理由（必填） */
    @TableField("reason")
    private String reason;

    /** 变更摘要（不含敏感明文） */
    @TableField("detail")
    private String detail;

    /** 结果：1成功 0失败 */
    @TableField("result")
    private Integer result;

    /** 失败原因 */
    @TableField("error_msg")
    private String errorMsg;

    /** 操作来源IP */
    @TableField("ip")
    private String ip;

    /** 耗时（毫秒） */
    @TableField("cost_ms")
    private Integer costMs;

    /** 操作时间 */
    @TableField("created_time")
    private Date createdTime;
}
