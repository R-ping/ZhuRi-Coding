package com.zhuri.coding.content.service.audit;

import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.content.auth.AdminIdentityResolver;
import com.zhuri.coding.model.admin.AdminPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 内容治理「人工终审」审核员守卫。
 *
 * <p><b>为什么需要</b>：申诉终审（{@code allow} 解除折叠 / {@code allow} 撤销 AIGC 标注）会直接改变
 * 内容治理结论。原先该接口只校验"是否登录"，任何注册用户都能终审他人申诉 —— 等于绕过整个治理闭环。
 * 本类补上缺失的<b>授权</b>判定（原实现只有认证，没有授权）。
 *
 * <p><b>授权事实来自运营账号角色表</b>（{@code ap_admin_account_role} →
 * {@link AdminPermission#APPEAL_REVIEW}），见 {@link AdminIdentityResolver}。
 * <b>入参是运营账号 ID（{@code ap_admin_account.id}），不是 C 端账号 ID</b>：
 * 终审接口挂在 {@code /api/v1/audit/appeal/review}，该路径已被纳入运营鉴权
 * （见各服务 {@code ADMIN_PATH_PATTERNS} 与网关的运营路径前缀），因此请求头里的身份
 * 来自运营会话而不是 C 端 token。
 *
 * <p><b>与拦截器注解是两层，不是一次校验写两遍</b>：{@code ContentAppealController#review}
 * 上另有 {@link RequireAdminPermission}，由 {@code AdminAuthInterceptor} 校验。
 * 两层各自独立地失败 —— 注解只在该控制器路径被注册进拦截器时才生效，
 * 而路径注册是配置，改动一次不会有编译错误。本类不看路径，只要被调用就查一遍，
 * 于是"路径漏配"这一种失误不会退化成人人能终审。
 *
 * <p><b>信任语义（fail-closed）</b>：用户服务不可用、角色数据缺失、账号无对应角色
 * （或该 ID 压根不是一个启用中的运营账号），几种情况一律判定为"不是审核员"。
 * 这与 {@code InternalAuthSigner} 的取舍一致 —— 漏配只会让终审暂时不可用
 * （运营可发现并补齐授权），不会退化为"任意用户可终审"。
 *
 * <p>⚠️ <b>上线依赖</b>：需先执行 {@code db/migrations/create_ap_admin_account.sql} 与
 * {@code create_ap_admin_account_role.sql}，并给做终审的人建运营账号、授予
 * AUDITOR 或 OPERATOR 角色。未授权时终审会对所有人返回 403，属预期行为而非故障。
 */
@Slf4j
@Component
public class AuditReviewerGuard {

    @Autowired
    private AdminIdentityResolver adminIdentityResolver;

    /**
     * 是否为授权审核员。
     *
     * @param accountId 当前运营账号 ID（取自网关透传的可信身份头，经运营会话解析而来）
     * @return true 表示可执行终审；ID 为空、无角色、账号不存在或已停用、用户服务不可用时一律 false
     */
    public boolean isReviewer(Integer accountId) {
        if (accountId == null) {
            return false;
        }
        AdminIdentity identity = adminIdentityResolver.resolve(accountId);
        boolean reviewer = identity.has(AdminPermission.APPEAL_REVIEW);
        if (!reviewer) {
            log.debug("申诉终审未授权被拒, accountId={}, roles={}", accountId, identity.roleCodesAsString());
        }
        return reviewer;
    }
}
