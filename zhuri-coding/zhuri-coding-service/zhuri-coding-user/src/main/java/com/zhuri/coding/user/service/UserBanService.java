package com.zhuri.coding.user.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 运营侧用户处置：警告 / 封禁 / 解封。
 *
 * <p><b>这个能力此前完全缺失</b>：内容侧有举报处置与折叠，但违规主体是"账号"时无处下手 ——
 * 下架文章只能治一篇，账号继续发下一篇。补上它，内容治理才闭环。
 *
 * <p><b>三种动作的"记录在哪"不一样</b>，这是刻意的：
 * <ul>
 *   <li><b>警告</b>不建业务表。警告没有任何持久化的状态，它的产物就是"一条通知 + 一条台账"，
 *       而审计表（{@code ap_admin_audit_log}，module=USER / action=USER_WARN / target=USER:{id}）
 *       恰好就是这个台账 —— 再建一张 {@code ap_user_warning} 只是把同样的字段抄一遍，
 *       还会多出一处"两张表对不上"的可能。警告次数、历史都由审计表按索引查出来。</li>
 *   <li><b>封禁 / 解封</b>必须落到 {@code ap_user} 上，因为要让登录真的拦得住（见 {@code UserBanChecker}）。</li>
 * </ul>
 *
 * <p>动作编码沿用「模块_动作」约定（同 content 侧的 {@code REPORT_TAKE_DOWN} 等），
 * 供审计表的 {@code action} 列使用。
 */
public interface UserBanService {

    /** 审计动作：警告 */
    String ACTION_WARN = "USER_WARN";
    /** 审计动作：封禁 */
    String ACTION_BAN = "USER_BAN";
    /** 审计动作：解封 */
    String ACTION_UNBAN = "USER_UNBAN";
    /** 审计对象类型：账号（与 content 侧的 ARTICLE / COMMENT / PINS 同一套命名空间） */
    String TARGET_USER = "USER";

    /** 封禁名单每页上限，拦住前端误传 */
    int MAX_PAGE_SIZE = 50;
    /** 列表默认每页条数 */
    int DEFAULT_PAGE_SIZE = 20;

    /**
     * 封禁名单（当前仍在封禁中的账号）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，超出上限按上限
     */
    ResponseResult page(Integer page, Integer size);

    /**
     * 某账号的处置记录（警告/封禁/解封的完整流水，含被拒的尝试）。
     *
     * @param userId 账号ID
     */
    ResponseResult records(Integer userId, Integer page, Integer size);

    /**
     * 警告账号：发一条站内信告知原因，并在审计表留一条台账。
     *
     * <p><b>通知是 fail-closed 的</b>：警告的产物只有这条通知，发不出去就等于没警告，
     * 所以通知失败时整体失败（不写台账），让运营看到失败去重试。
     *
     * @param reason 警告理由，必填
     * @return data 含 {@code warnCount}（含本次的累计警告次数），便于运营判断是否该升级为封禁
     */
    ResponseResult warn(Integer userId, String reason);

    /**
     * 封禁账号。
     *
     * <p><b>通知是 best-effort 的</b>：封禁的产物是账号状态，与警告相反 ——
     * 通知服务抖动不该让封禁回滚。且被封的人登不进来，站内信本就不是他的权威告知渠道。
     *
     * @param days 封禁天数；null 表示永久封禁
     * @return data 含 {@code banUntil} 与 {@code permanent}
     */
    ResponseResult ban(Integer userId, String reason, Integer days);

    /**
     * 解封账号：清空封禁字段，账号立即可以正常登录。
     *
     * <p>用条件更新做闸口（{@code WHERE ban_until > now}），并发下只有一个人能解开，
     * 另一个人会收到"当前未被封禁"而不是一条静默的成功。
     */
    ResponseResult unban(Integer userId, String reason);
}
