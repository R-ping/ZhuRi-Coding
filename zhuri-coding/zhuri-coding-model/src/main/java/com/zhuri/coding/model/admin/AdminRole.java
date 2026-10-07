package com.zhuri.coding.model.admin;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 运营角色。
 *
 * <p>三级最小划分，按「能造成多大不可逆影响」递增：
 * <ul>
 *   <li>{@link #AUDITOR} 审核员 —— 能折叠内容、处置举报、复核 AI 审核，但**不能下架**（下架是不可逆的线上动作）</li>
 *   <li>{@link #OPERATOR} 运营 —— 增加下架、运营位配置、活动管理、小册与沸点管理、警告用户</li>
 *   <li>{@link #SUPER_ADMIN} 超管 —— 增加封禁用户、管理角色</li>
 * </ul>
 *
 * <p><b>角色是可叠加的</b>：一人多角色时权限取并集（见 {@link #permissionsOf}）。
 * 这样可以表达"既是审核员又是运营"，而不必再造一个 AUDITOR_AND_OPERATOR 角色。
 *
 * <p><b>未知角色编码一律忽略</b>（fail-closed）：库里若存在代码认不出的编码
 * （例如旧版本遗留、手写 SQL 打错字），不报错也不授权——报错会让人误以为系统故障，
 * 静默忽略则只会表现为"这个人没权限"，是安全的一侧。
 */
public enum AdminRole {

    /** 审核员：处置举报与折叠内容、终审申诉、复核 AI 审核队列 */
    AUDITOR("审核员", EnumSet.of(
        AdminPermission.REPORT_VIEW,
        AdminPermission.REPORT_HANDLE,
        AdminPermission.CONTENT_FOLD,
        AdminPermission.APPEAL_REVIEW,
        // 复核与申诉同属内容治理线（AUDIT_REVIEW 的 javadoc 说明了为何是两个权限点）。
        // 放行动作只是清一个读时判断的标记，比下架轻得多，审核员持有没有争议。
        AdminPermission.AUDIT_REVIEW
    )),

    /** 运营：审核员职责 + 下架、运营位配置、活动管理、小册与沸点管理、警告用户 */
    OPERATOR("运营", EnumSet.of(
        AdminPermission.REPORT_VIEW,
        AdminPermission.REPORT_HANDLE,
        AdminPermission.CONTENT_FOLD,
        AdminPermission.CONTENT_TAKE_DOWN,
        AdminPermission.APPEAL_REVIEW,
        // 与 APPEAL_REVIEW 同理：治理线权限是 AUDITOR 的超集，运营不该反而缺这一项
        AdminPermission.AUDIT_REVIEW,
        AdminPermission.OPS_CONFIG,
        // 活动建的是"站级对外内容"（标题/封面/时间窗直接给全站用户看），
        // 与运营位、小册、沸点同属内容运营线，故给 OPERATOR 而不是 AUDITOR。
        AdminPermission.ACTIVITY_MANAGE,
        // 这两个权限点是原 EditorConfig.EDITOR_USER_IDS 白名单的去向：编辑账号做的是
        // 「小册审核 + 沸点审核」，属于内容运营而不是内容治理，故归到 OPERATOR 而不是 AUDITOR。
        AdminPermission.BOOKLET_MANAGE,
        AdminPermission.PINS_MANAGE,
        AdminPermission.USER_WARN
    )),

    /** 超级管理员：全部权限，含封禁与角色管理 */
    SUPER_ADMIN("超级管理员", EnumSet.allOf(AdminPermission.class));

    private final String desc;
    private final Set<AdminPermission> permissions;

    AdminRole(String desc, Set<AdminPermission> permissions) {
        this.desc = desc;
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public String getDesc() {
        return desc;
    }

    /** 角色编码（与 {@code ap_admin_account_role.role_code} 取值一致） */
    public String getCode() {
        return name();
    }

    /** 本角色直接持有的权限点（不含其他角色） */
    public Set<AdminPermission> getPermissions() {
        return permissions;
    }

    /** 解析角色编码；无法识别返回 {@code null}（由调用方决定如何处理，默认忽略） */
    public static AdminRole parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 多角色权限并集。
     *
     * @param roleCodes 角色编码集合，可为 null/空；无法识别的编码被忽略
     * @return 不可变的权限并集；无有效角色时为空集（此时任何 {@code has} 判定都是 false）
     */
    public static Set<AdminPermission> permissionsOf(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<AdminPermission> merged = new LinkedHashSet<>();
        for (String code : roleCodes) {
            AdminRole role = parse(code);
            if (role != null) {
                merged.addAll(role.permissions);
            }
        }
        return Collections.unmodifiableSet(merged);
    }
}
