package com.zhuri.coding.common.admin;

import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;

/**
 * 运营操作审计的写入出口。
 *
 * <p>调用方只需要交代**业务事实**（模块、动作、对象、理由），
 * 环境事实（谁、什么角色、什么 IP、什么时候、结果）由实现从 {@link AdminContext} 与
 * 请求上下文补齐 —— 避免每个调用点各写一遍，写漏一个字段就少一条线索。
 *
 * <p><b>事务语义（实现方必须守住，关键）</b>：
 * <ul>
 *   <li>{@link #recordSuccess} 必须加入**业务事务**（{@code REQUIRED}）。这样"改了内容"与
 *       "留下记录"要么同时成功、要么同时失败，不会出现"内容已被处置但查不到是谁做的"。
 *       ⚠️ 因此调用它的业务方法必须是事务方法（否则该方法会自建事务，业务提交后审计仍可能失败）。</li>
 *   <li>{@link #recordFailure} 必须另起事务（{@code REQUIRES_NEW}）。业务已失败，其事务注定回滚，
 *       失败记录必须独立提交才留得下来；此路径要吞掉自身异常 ——
 *       原始业务错误比审计写入错误更重要，不能让审计问题掩盖真正的失败原因。</li>
 * </ul>
 *
 * <p><b>为什么每个服务各建一份同名表、而不是集中写一张</b>：审计是绝对不能丢的记录。
 * 集中写会引入"审计服务挂了但业务照常执行、于是没留痕"的窗口；本地表则与业务变更同库，
 * 事务能覆盖。代价是查全量要跨库聚合，可以接受。
 */
public interface AdminAuditSink {

    /**
     * 构造一条审计条目（只填业务事实，其余交给实现）。
     *
     * @param module     业务模块，见 {@link ApAdminAuditLog#MODULE_REPORT} 等常量
     * @param action     动作编码，建议用「模块_动作」形式，如 {@code REPORT_TAKE_DOWN}
     * @param targetType 对象类型：ARTICLE / COMMENT / PINS / USER
     * @param targetId   对象ID
     * @param reason     操作理由（必填）
     */
    static ApAdminAuditLog entry(String module, String action, String targetType,
                                 String targetId, String reason) {
        ApAdminAuditLog entry = new ApAdminAuditLog();
        entry.setModule(module);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setReason(reason);
        return entry;
    }

    /** 记录一次成功操作（与业务同事务） */
    void recordSuccess(ApAdminAuditLog entry);

    /** 记录一次失败操作（独立事务，不受业务回滚影响） */
    void recordFailure(ApAdminAuditLog entry, String errorMsg);
}
