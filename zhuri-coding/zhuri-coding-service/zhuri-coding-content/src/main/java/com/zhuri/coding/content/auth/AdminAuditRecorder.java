package com.zhuri.coding.content.auth;

import com.zhuri.coding.common.admin.AbstractAdminAuditSink;
import com.zhuri.coding.content.mapper.audit.ApAdminAuditLogMapper;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 运营操作审计记录器（content 侧实现：写入本库的 {@code ap_admin_audit_log}）。
 *
 * <p>契约（同事务 / 独立事务、必须吞掉自身异常、缺表时 fail-closed）见
 * {@link com.zhuri.coding.common.admin.AdminAuditSink}，补齐环境事实与事务语义的公共骨架见
 * {@link AbstractAdminAuditSink}；本类只负责"往 content 库这张表里写"。
 *
 * <p><b>审计表缺失时</b>：成功路径会抛异常导致业务回滚。这是有意的 fail-closed ——
 * 「改了内容但没留痕」对内容治理是不可接受的状态（迁移脚本见
 * {@code db/migrations/add_admin_audit_log.sql}，必须先执行）。
 */
@Component
public class AdminAuditRecorder extends AbstractAdminAuditSink {

    @Autowired
    private ApAdminAuditLogMapper auditLogMapper;

    @Override
    protected void insert(ApAdminAuditLog entry) {
        auditLogMapper.insert(entry);
    }
}
