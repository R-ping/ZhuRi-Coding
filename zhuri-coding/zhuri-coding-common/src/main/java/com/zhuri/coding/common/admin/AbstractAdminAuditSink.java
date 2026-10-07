package com.zhuri.coding.common.admin;

import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;

/**
 * 审计写入的公共骨架：把「环境事实」补全与两套事务语义收在一处，子类只负责往自己库里插一行。
 *
 * <p><b>为什么是抽象类而不是让每个服务各写一遍</b>：真正容易写错、且写错了不容易发现的不是
 * {@code insert}，而是 {@link #recordSuccess} 用 {@code REQUIRED}、{@link #recordFailure} 用
 * {@code REQUIRES_NEW} 这个约定（理由见 {@link AdminAuditSink}）。复制一份出来，
 * 将来只会改其中一侧 —— 于是某个服务的失败记录会跟着业务事务一起回滚，
 * 表现为"被拒绝的操作没留痕"，而这恰恰是审计最该有的那一半信息。
 *
 * <p>子类只实现 {@link #insert(ApAdminAuditLog)}，注入自己库的 mapper 即可。
 */
@Slf4j
public abstract class AbstractAdminAuditSink implements AdminAuditSink {

    /**
     * 往本服务的审计表插入一行。**不要**在这里加 {@code @Transactional}：
     * 事务语义由本类的两个公开方法决定，子类自己再标一次只会让人看不懂到底按谁生效。
     */
    protected abstract void insert(ApAdminAuditLog entry);

    /** 记录一次成功操作（加入业务事务，保证"改了内容"与"留下记录"同生共死） */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void recordSuccess(ApAdminAuditLog entry) {
        fillAmbient(entry);
        entry.setResult(ApAdminAuditLog.RESULT_SUCCESS);
        entry.setErrorMsg(null);
        insert(entry);
        log.info("[AdminAudit] {} {} target={}:{} by={} roles={}",
            entry.getModule(), entry.getAction(), entry.getTargetType(), entry.getTargetId(),
            entry.getUserId(), entry.getRoleCodes());
    }

    /** 记录一次失败操作（独立事务，不受业务回滚影响） */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(ApAdminAuditLog entry, String errorMsg) {
        try {
            fillAmbient(entry);
            entry.setResult(ApAdminAuditLog.RESULT_FAIL);
            entry.setErrorMsg(truncate(errorMsg, 500));
            insert(entry);
            log.warn("[AdminAudit] {} {} 失败 target={}:{} by={} err={}",
                entry.getModule(), entry.getAction(), entry.getTargetType(), entry.getTargetId(),
                entry.getUserId(), errorMsg);
        } catch (Exception e) {
            // 业务已经失败，审计写不进去只能告警，绝不能吞掉原始错误
            log.error("[AdminAudit] 失败记录写入异常，该次失败操作未留痕, module={}, action={}",
                entry.getModule(), entry.getAction(), e);
        }
    }

    /**
     * 补齐环境事实：操作人、角色快照、IP、时间。
     *
     * <p>身份从 {@link AdminContext} 取（由拦截器写入）。取不到就留空而不报错 ——
     * 非运营请求线程（如异步、单元测试）里没有这个上下文是正常情况。
     */
    private void fillAmbient(ApAdminAuditLog entry) {
        AdminIdentity identity = AdminContext.get();
        if (identity != null) {
            entry.setUserId(identity.getUserId());
            entry.setRoleCodes(identity.roleCodesAsString());
        }
        entry.setIp(currentIp());
        entry.setCreatedTime(new Date());
    }

    /** 取当前请求 IP；不在请求线程内时返回 null（审计不因取不到 IP 而失败） */
    private String currentIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest request = attrs.getRequest();
                String forwarded = request.getHeader("X-Forwarded-For");
                if (forwarded != null && !forwarded.isBlank()) {
                    // XFF 可能是链式列表，第一个才是客户端；统一截断到列宽以内
                    return truncate(forwarded.split(",")[0].trim(), 64);
                }
                return truncate(request.getRemoteAddr(), 64);
            }
        } catch (Exception e) {
            log.debug("获取审计 IP 失败", e);
        }
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
