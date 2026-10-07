package com.zhuri.coding.model.admin.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 账号处置记录（读自 {@code ap_admin_audit_log}）。
 *
 * <p>警告没有独立业务表，"警告过几次、都是什么理由"只能从审计台账里读 —— 这也是本 VO 存在的意义：
 * 让运营在决定"要不要升级为封禁"时看得到前情，而不是凭印象。
 *
 * <p>刻意不带 {@code ip} 与 {@code error_msg}：处置记录页要回答的是"这个账号被怎么处置过"，
 * 操作人的网络地址属于安全排查信息，需要时另开审计查询页去看，不必摊在列表里。
 */
@Data
public class AdminUserDispositionRecordVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 台账ID */
    private Long id;

    /** 动作：USER_WARN / USER_BAN / USER_UNBAN */
    private String action;

    /** 处置理由 */
    private String reason;

    /** 变更摘要 */
    private String detail;

    /** 结果：1成功 0失败（失败也记 —— "谁试过但没成功"同样是有效信息） */
    private Integer result;

    /** 操作人账号ID */
    private Integer operatorId;

    /** 操作时间 */
    private Date createdTime;
}
