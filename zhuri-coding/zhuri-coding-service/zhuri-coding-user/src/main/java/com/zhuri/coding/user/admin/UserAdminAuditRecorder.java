package com.zhuri.coding.user.admin;

import com.zhuri.coding.common.admin.AbstractAdminAuditSink;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.user.mapper.ApAdminAuditLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 运营操作审计记录器（user 侧实现：写入本库的 {@code ap_admin_audit_log}）。
 *
 * <p>契约见 {@link com.zhuri.coding.common.admin.AdminAuditSink}，公共骨架见
 * {@link AbstractAdminAuditSink}；本类只负责"往 leadnews_user 库这张表里写"。
 *
 * <p><b>审计表缺失时</b>：{@code recordSuccess} 抛异常 → 账号变更回滚。有意的 fail-closed ——
 * 「封了人但查不到是谁封的」比"这次封禁失败"严重得多（迁移脚本
 * {@code db/migrations/add_admin_audit_log.sql} 必须先执行）。
 */
@Component
public class UserAdminAuditRecorder extends AbstractAdminAuditSink {

    @Autowired
    private ApAdminAuditLogMapper auditLogMapper;

    @Override
    protected void insert(ApAdminAuditLog entry) {
        auditLogMapper.insert(entry);
    }
}
