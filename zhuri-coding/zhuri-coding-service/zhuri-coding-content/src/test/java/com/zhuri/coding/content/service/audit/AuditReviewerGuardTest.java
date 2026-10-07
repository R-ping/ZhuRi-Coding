package com.zhuri.coding.content.service.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.content.auth.AdminIdentityResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 申诉终审审核员守卫（AuditReviewerGuard）单测。
 *
 * <p>该守卫是内容治理的**授权**边界：此前终审接口只校验"是否登录"，任何注册用户都能终审他人申诉。
 * 授权事实现在统一来自运营角色表（{@code APPEAL_REVIEW} 权限点），本测试守住两件事：
 * <ul>
 *   <li>只有持有该权限的角色才放行；</li>
 *   <li>无角色 / 角色编码无法识别 / 未登录，一律拒绝（fail-closed）。</li>
 * </ul>
 *
 * <p>▸ 历史背景：本测试原先是"配置白名单"语义（{@code audit.reviewer-user-ids}），
 * 随运营角色表落地改为角色语义。白名单的迁移由 {@code add_admin_role.sql} 完成。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("申诉终审审核员守卫（AuditReviewerGuard）")
class AuditReviewerGuardTest {

    @Mock
    private AdminIdentityResolver adminIdentityResolver;

    @InjectMocks
    private AuditReviewerGuard guard;

    @Test
    @DisplayName("userId 为 null → 拒绝，且不查角色（省一次远程调用）")
    void nullUserDenied() {
        assertFalse(guard.isReviewer(null));
        verify(adminIdentityResolver, never()).resolve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("持有 APPEAL_REVIEW 的角色（AUDITOR）→ 放行")
    void auditorAllowed() {
        when(adminIdentityResolver.resolve(1001)).thenReturn(new AdminIdentity(1001, List.of("AUDITOR")));
        assertTrue(guard.isReviewer(1001));
    }

    @Test
    @DisplayName("OPERATOR 同样持有 APPEAL_REVIEW → 放行")
    void operatorAllowed() {
        when(adminIdentityResolver.resolve(1002)).thenReturn(new AdminIdentity(1002, List.of("OPERATOR")));
        assertTrue(guard.isReviewer(1002));
    }

    @Test
    @DisplayName("无任何角色 → 拒绝（fail-closed）")
    void noRoleDenied() {
        when(adminIdentityResolver.resolve(1003)).thenReturn(AdminIdentity.anonymous(1003));
        assertFalse(guard.isReviewer(1003));
    }

    @Test
    @DisplayName("角色编码无法识别（如库里遗留的旧编码）→ 拒绝，不报错")
    void unknownRoleDenied() {
        when(adminIdentityResolver.resolve(1004))
            .thenReturn(new AdminIdentity(1004, List.of("LEGACY_EDITOR", "typo_role")));
        assertFalse(guard.isReviewer(1004));
    }

    @Test
    @DisplayName("用户服务不可用（解析器返回空身份）→ 拒绝，不放开终审")
    void resolverDownDenied() {
        // AdminIdentityResolver 内部对异常做了 fail-closed 处理，返回空身份
        when(adminIdentityResolver.resolve(1005)).thenReturn(AdminIdentity.anonymous(1005));
        assertFalse(guard.isReviewer(1005));
    }
}
