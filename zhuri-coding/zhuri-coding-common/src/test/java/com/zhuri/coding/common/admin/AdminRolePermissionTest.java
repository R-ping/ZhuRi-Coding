package com.zhuri.coding.common.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.AdminRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 角色 → 权限映射与"解析角色编码列表 → 身份"的单测。
 *
 * <p>守住的核心语义：**未知角色编码不授权、不报错**。这是这条路线上最容易写错的一处 ——
 * 用 {@code AdminRole.valueOf(code)} 直接解析，遇到库里一个拼错的编码就会抛
 * {@code IllegalArgumentException}，表现为"运营后台整个 500"；而如果为了不报错改成
 * "认不出就当超管"，则一条脏数据就能把最高权限发出去。两条错路都在这里被钉住。
 */
@DisplayName("运营角色与权限映射")
class AdminRolePermissionTest {

    @Test
    @DisplayName("AUDITOR：能处置举报，但不能下架内容（下架是不可逆的线上动作）")
    void auditorCannotTakeDown() {
        AdminIdentity identity = new AdminIdentity(1, List.of("AUDITOR"));
        assertTrue(identity.isAdmin());
        assertTrue(identity.has(AdminPermission.REPORT_VIEW));
        assertTrue(identity.has(AdminPermission.REPORT_HANDLE));
        assertTrue(identity.has(AdminPermission.CONTENT_FOLD));
        assertFalse(identity.has(AdminPermission.CONTENT_TAKE_DOWN), "审核员不应持有下架权限");
        assertFalse(identity.has(AdminPermission.USER_BAN));
        assertFalse(identity.has(AdminPermission.ROLE_GRANT));
    }

    @Test
    @DisplayName("OPERATOR：能下架内容，但仍不能封禁用户 / 管理角色")
    void operatorCanTakeDownNotBan() {
        AdminIdentity identity = new AdminIdentity(2, List.of("OPERATOR"));
        assertTrue(identity.has(AdminPermission.CONTENT_TAKE_DOWN));
        assertTrue(identity.has(AdminPermission.OPS_CONFIG));
        assertTrue(identity.has(AdminPermission.USER_WARN));
        assertFalse(identity.has(AdminPermission.USER_BAN));
        assertFalse(identity.has(AdminPermission.ROLE_GRANT));
    }

    @Test
    @DisplayName("活动管理归 OPERATOR 而不是 AUDITOR：它新建的是站级对外内容，属于内容运营线")
    void activityManageBelongsToOperatorOnly() {
        // 两个断言合起来守住"这个权限点真的生效"：挂上去（否则谁也调不了）
        // 与没有多余扩散（审核员做的是内容治理，不该能发布站级活动）
        assertTrue(new AdminIdentity(2, List.of("OPERATOR")).has(AdminPermission.ACTIVITY_MANAGE));
        assertFalse(new AdminIdentity(5, List.of("AUDITOR")).has(AdminPermission.ACTIVITY_MANAGE),
            "审核员的职责是处置内容，发布对外活动是另一条线");
    }

    @Test
    @DisplayName("OPERATOR 持有小册/沸点管理权限：原 EditorConfig 白名单的等价迁移")
    void operatorKeepsLegacyEditorCapability() {
        // 这条断言守的是「迁移前后行为等价」：原 EditorConfig.EDITOR_USER_IDS = {4} 允许的操作，
        // 迁移后必须由 user 4 的 OPERATOR 角色完整承接（add_admin_role.sql 已把 4 插成 OPERATOR）。
        // 少给一个权限点，编辑账号就会在迁移当天"突然没权限了"。
        AdminIdentity operator = new AdminIdentity(4, List.of("OPERATOR"));
        assertTrue(operator.has(AdminPermission.BOOKLET_MANAGE), "编辑账号应能继续做小册审核");
        assertTrue(operator.has(AdminPermission.PINS_MANAGE), "编辑账号应能继续做沸点管理");
    }

    @Test
    @DisplayName("AUDITOR 不持有小册/沸点管理权限（内容治理与内容运营是两条线）")
    void auditorHasNoEditorCapability() {
        AdminIdentity auditor = new AdminIdentity(5, List.of("AUDITOR"));
        assertFalse(auditor.has(AdminPermission.BOOKLET_MANAGE));
        assertFalse(auditor.has(AdminPermission.PINS_MANAGE));
    }

    @Test
    @DisplayName("AUDIT_REVIEW 归内容治理线：AUDITOR 与 OPERATOR 都持有（与 APPEAL_REVIEW 同理）")
    void auditReviewIsGovernanceLine() {
        assertTrue(new AdminIdentity(5, List.of("AUDITOR")).has(AdminPermission.AUDIT_REVIEW),
            "复核巡检是审核员职责的一部分：放行只是清一个读时判断的标记，比下架轻得多");
        assertTrue(new AdminIdentity(2, List.of("OPERATOR")).has(AdminPermission.AUDIT_REVIEW),
            "治理线权限历来是 AUDITOR 的超集，运营不该反而缺复核这一项");
    }

    @Test
    @DisplayName("SUPER_ADMIN：持有全部权限点")
    void superAdminHasAll() {
        AdminIdentity identity = new AdminIdentity(3, List.of("SUPER_ADMIN"));
        for (AdminPermission p : AdminPermission.values()) {
            assertTrue(identity.has(p), "超管应持有 " + p);
        }
    }

    @Test
    @DisplayName("一人多角色：权限取并集（审核员 + 运营 = 两者之和）")
    void multiRoleUnion() {
        AdminIdentity identity = new AdminIdentity(4, List.of("AUDITOR", "OPERATOR"));
        assertTrue(identity.has(AdminPermission.REPORT_HANDLE));
        assertTrue(identity.has(AdminPermission.CONTENT_TAKE_DOWN));
        assertEquals("AUDITOR,OPERATOR", identity.roleCodesAsString(), "角色快照原样保留，供审计留痕");
    }

    @Test
    @DisplayName("未知角色编码：忽略且不报错，权限为空（fail-closed）")
    void unknownRoleIgnored() {
        AdminIdentity identity = new AdminIdentity(5, List.of("LEGACY_EDITOR", "Operato", " "));
        assertFalse(identity.isAdmin(), "认不出的编码不得换来任何权限");
        // 角色快照**原样**保留（含认不出的编码）：审计要能看到"这个人身上挂着一个不认识的编码"，
        // 这比把它悄悄丢掉更有诊断价值。
        assertEquals("LEGACY_EDITOR,Operato, ", identity.roleCodesAsString());
        assertEquals(3, identity.getRoleCodes().size());
    }

    @Test
    @DisplayName("完全无角色：角色快照为 null（而不是空字符串），落库即为 NULL")
    void noRoleSnapshotIsNull() {
        assertNull(AdminIdentity.anonymous(9).roleCodesAsString());
    }

    @Test
    @DisplayName("角色编码大小写与前后空格：容忍（库里的 'operator' 也能认出）")
    void roleCodeIsNormalized() {
        assertEquals(AdminRole.OPERATOR, AdminRole.parse("  operator "));
        assertEquals(AdminRole.AUDITOR, AdminRole.parse("AUDITOR"));
        assertNull(AdminRole.parse(null));
        assertNull(AdminRole.parse(""));
    }

    @Test
    @DisplayName("null / 空角色列表：得到空权限集，任何判定都是 false，不抛异常")
    void emptyRoleListSafe() {
        assertFalse(new AdminIdentity(6, null).isAdmin());
        assertFalse(new AdminIdentity(6, new ArrayList<>()).isAdmin());
        assertTrue(AdminRole.permissionsOf(null).isEmpty());
        assertTrue(AdminRole.permissionsOf(Arrays.asList((String) null, "NOPE")).isEmpty());
    }

    @Test
    @DisplayName("has(null) 恒为 false —— 避免上层漏判造成放行")
    void hasNullIsFalse() {
        assertFalse(new AdminIdentity(7, List.of("SUPER_ADMIN")).has(null));
    }

    @Test
    @DisplayName("权限集不可被调用方改坏（避免下游误改后影响判定）")
    void permissionsImmutable() {
        AdminIdentity identity = new AdminIdentity(8, List.of("AUDITOR"));
        try {
            identity.getPermissions().add(AdminPermission.USER_BAN);
            org.junit.jupiter.api.Assertions.fail("权限集应为不可变，写入应当抛异常");
        } catch (UnsupportedOperationException expected) {
            // 预期：不可变集合
        }
    }
}
