package com.zhuri.coding.user.admin;

import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.pojos.ApAdminAccount;
import com.zhuri.coding.model.admin.pojos.ApAdminAccountRole;
import com.zhuri.coding.user.mapper.ApAdminAccountMapper;
import com.zhuri.coding.user.mapper.ApAdminAccountRoleMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * user 侧运营身份解析单测。
 *
 * <p>核心是两条安全约定：
 * <ol>
 *   <li><b>解析不出来就等于没有权限</b>（fail-closed）。这条如果反了，表现是"用户服务抖一下，
 *       运营后台向所有人敞开"—— 而且不会报错，只在被利用时才看得见。</li>
 *   <li><b>ID 不是一个运营账号，就没有角色</b>。这条挡的是"C 端用户 id 凑巧等于某个
 *       有角色的运营账号 id"—— 两套 ID 空间分离之后，这是唯一还能把权限漏出去的口子，
 *       所以它必须在数据访问层兜住，而不是指望上游每次都传对 ID。</li>
 * </ol>
 *
 * <p>载体是 {@link ApAdminAccountRole}（绑运营账号 ID）。历史上那张绑在 C 端账号上的
 * {@code ap_admin_user_role} 已废弃，若改回去，上面第 2 条立刻失效。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("user 侧运营身份解析（LocalAdminRoleResolver）")
class LocalAdminRoleResolverTest {

    private static final Integer ACCOUNT_ID = 1001;

    @Mock
    private ApAdminAccountRoleMapper roleMapper;

    @Mock
    private ApAdminAccountMapper accountMapper;

    @InjectMocks
    private LocalAdminRoleResolver resolver;

    @BeforeEach
    void setUp() {
        // Lambda 包装器解析列名时要读实体元信息，没有 MyBatis 会话时必须手动初始化。
        // ⚠️ 这一步漏了不会报"缺元信息"，而是 eq() 内部先抛异常 → 被 resolve 的 fail-closed
        //    兜住 → 表现为"所有账号都没角色"，测试里看到的是断言失败而不是异常，很容易误判成业务 bug。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ApAdminAccountRole.class);
        TableInfoHelper.initTableInfo(assistant, ApAdminAccount.class);
    }

    /** 默认让"该 ID 是一个启用中的运营账号"成立；只想验角色逻辑的用例直接用它 */
    private void givenEnabledAccount() {
        lenient().when(accountMapper.selectOne(any())).thenReturn(enabledAccount());
    }

    private ApAdminAccount enabledAccount() {
        ApAdminAccount account = new ApAdminAccount();
        account.setId(ACCOUNT_ID);
        account.setStatus(ApAdminAccount.STATUS_ENABLED);
        return account;
    }

    private ApAdminAccountRole binding(String roleCode) {
        ApAdminAccountRole binding = new ApAdminAccountRole();
        binding.setAccountId(ACCOUNT_ID);
        binding.setRoleCode(roleCode);
        return binding;
    }

    @Test
    @DisplayName("有角色 → 权限并集生效")
    void resolvesRoles() {
        givenEnabledAccount();
        when(roleMapper.selectList(any())).thenReturn(List.of(binding("OPERATOR"), binding("AUDITOR")));

        AdminIdentity identity = resolver.resolve(ACCOUNT_ID);

        assertEquals(List.of("OPERATOR", "AUDITOR"), identity.getRoleCodes());
        assertTrue(identity.isAdmin());
        assertTrue(identity.has(AdminPermission.USER_WARN));
        assertEquals("OPERATOR,AUDITOR", identity.roleCodesAsString());
    }

    @Test
    @DisplayName("无角色 → 空身份（不是 null，且判定一律 false）")
    void noRolesGivesEmptyIdentity() {
        givenEnabledAccount();
        when(roleMapper.selectList(any())).thenReturn(List.of());

        AdminIdentity identity = resolver.resolve(ACCOUNT_ID);

        assertNotNull(identity);
        assertFalse(identity.isAdmin());
        assertFalse(identity.has(AdminPermission.USER_BAN));
    }

    @Test
    @DisplayName("代码认不出的角色编码原样保留但不授权（配错角色要看得见，不能悄悄吃掉）")
    void unknownRoleIsKeptButNotGranted() {
        givenEnabledAccount();
        when(roleMapper.selectList(any())).thenReturn(List.of(binding("SUPER_ADMIN_V2")));

        AdminIdentity identity = resolver.resolve(ACCOUNT_ID);

        assertEquals(List.of("SUPER_ADMIN_V2"), identity.getRoleCodes());
        assertFalse(identity.isAdmin());
    }

    @Test
    @DisplayName("查库抛异常 → 空身份（fail-closed），绝不放行")
    void queryFailureIsFailClosed() {
        givenEnabledAccount();
        when(roleMapper.selectList(any())).thenThrow(new RuntimeException("DB 连接断了"));

        AdminIdentity identity = resolver.resolve(ACCOUNT_ID);

        assertFalse(identity.isAdmin());
        assertFalse(identity.has(AdminPermission.USER_WARN));
    }

    @Test
    @DisplayName("账号ID为空 → 空身份，不查库")
    void nullAccountId() {
        AdminIdentity identity = resolver.resolve(null);

        assertFalse(identity.isAdmin());
        verify(roleMapper, never()).selectList(any());
        verify(accountMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("角色编码含 null/空白会被剔除：AdminIdentity 用 List.copyOf 收口，混进 null 直接 NPE")
    void filtersBlankCodes() {
        givenEnabledAccount();
        when(roleMapper.selectList(any())).thenReturn(List.of(
            binding(null), binding("  "), binding(" OPERATOR ")));

        AdminIdentity identity = resolver.resolve(ACCOUNT_ID);

        assertEquals(List.of("OPERATOR"), identity.getRoleCodes());
    }

    @Test
    @DisplayName("这个 ID 不是运营账号 → 空身份，且根本不查角色表（ID 空间分离的兜底）")
    void nonAdminAccountIdGetsNoRoles() {
        // 例：上游误传了 C 端用户 ID。角色表里可能真的有同号的行，但这里连查都不该查。
        when(accountMapper.selectOne(any())).thenReturn(null);

        AdminIdentity identity = resolver.resolve(99999);

        assertFalse(identity.isAdmin());
        verify(roleMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("运营账号已停用 → 空身份，不查角色表（会话尚存也要立刻失效）")
    void disabledAccountGetsNoRoles() {
        ApAdminAccount disabled = enabledAccount();
        disabled.setStatus(ApAdminAccount.STATUS_DISABLED);
        when(accountMapper.selectOne(any())).thenReturn(disabled);

        AdminIdentity identity = resolver.resolve(ACCOUNT_ID);

        assertFalse(identity.isAdmin());
        verify(roleMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("新增的 ACCOUNT_MANAGE 权限点只落在超管上（建账号 ≠ 授角色，不能混为一谈）")
    void accountManageIsSuperAdminOnly() {
        assertTrue(com.zhuri.coding.model.admin.AdminRole.SUPER_ADMIN
            .getPermissions().contains(AdminPermission.ACCOUNT_MANAGE));
        assertFalse(com.zhuri.coding.model.admin.AdminRole.OPERATOR
            .getPermissions().contains(AdminPermission.ACCOUNT_MANAGE));
        assertFalse(com.zhuri.coding.model.admin.AdminRole.AUDITOR
            .getPermissions().contains(AdminPermission.ACCOUNT_MANAGE));
    }
}
