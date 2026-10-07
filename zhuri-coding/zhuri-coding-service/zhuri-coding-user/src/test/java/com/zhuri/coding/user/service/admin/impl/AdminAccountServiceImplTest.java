package com.zhuri.coding.user.service.admin.impl;

import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.common.admin.AdminContext;
import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.model.admin.AdminRole;
import com.zhuri.coding.model.admin.dtos.AdminAccountCreateDto;
import com.zhuri.coding.model.admin.dtos.AdminAccountStatusDto;
import com.zhuri.coding.model.admin.dtos.AdminPasswordChangeDto;
import com.zhuri.coding.model.admin.dtos.AdminRoleChangeDto;
import com.zhuri.coding.model.admin.pojos.ApAdminAccount;
import com.zhuri.coding.model.admin.pojos.ApAdminAccountRole;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminIdentityVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.user.admin.UserAdminAuditRecorder;
import com.zhuri.coding.user.mapper.ApAdminAccountMapper;
import com.zhuri.coding.user.mapper.ApAdminAccountRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营账号服务的单测。
 *
 * <p>这个类里的每条校验都是"漏了不会报错、只会在真出事时被发现"的类型，所以逐条钉住：
 * <ul>
 *   <li><b>登录不能区分"账号不存在"与"口令错误"</b>——区分开就是个账号枚举接口；</li>
 *   <li><b>系统必须始终剩一个能用的超管</b>——判定错方向的代价是角色管理永久锁死，只能连库改表；</li>
 *   <li><b>理由必填、角色先校验再落库</b>——分别对应"审计表写不进去"和"建了一半的账号"；</li>
 *   <li><b>口令只以 BCrypt 哈希落库</b>，且新建的账号一律标记为待改口令。</li>
 * </ul>
 *
 * <p>用 {@code LENIENT} 严格度：这里大量用例只关心"返回了什么"，不去构造无关的 stub，
 * 严格的未使用检测会把测试变成维护负担而没有多抓到东西。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("运营账号服务（AdminAccountServiceImpl）")
class AdminAccountServiceImplTest {

    private static final Integer ME = 900;

    @Mock
    private ApAdminAccountMapper accountMapper;

    @Mock
    private ApAdminAccountRoleMapper accountRoleMapper;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private UserAdminAuditRecorder auditSink;

    @Mock
    private CacheService cacheService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private AdminAccountServiceImpl service;

    @AfterEach
    void clearAdminContext() {
        // ThreadLocal 不清会在下一个用例里留下"上一个操作人"，而这恰好是审计字段的来源
        AdminContext.clear();
    }

    private void actingAs(Integer accountId) {
        AdminContext.set(new AdminIdentity(accountId, List.of()));
    }

    private ApAdminAccount account(int id, int status, String passwordHash) {
        ApAdminAccount account = new ApAdminAccount();
        account.setId(id);
        account.setUsername("ops" + id);
        account.setNickName("运营" + id);
        account.setPassword(passwordHash);
        account.setStatus(status);
        account.setMustChangePassword(1);
        return account;
    }

    // ==================== 登录凭据校验 ====================

    @Test
    @DisplayName("账号不存在与口令错误返回同一个码（区分开就等于对外提供了一个账号枚举接口）")
    void unknownAccountAndWrongPasswordShareTheSameCode() {
        when(accountMapper.selectOne(any())).thenReturn(null);
        ResponseResult unknown = service.verifyCredentials("nobody", "whatever");

        when(accountMapper.selectOne(any())).thenReturn(account(1, ApAdminAccount.STATUS_ENABLED, "$2a$10$hash"));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        ResponseResult wrongPwd = service.verifyCredentials("ops1", "wrong");

        assertEquals(AppHttpCodeEnum.LOGIN_PASSWORD_ERROR.getCode(), unknown.getCode());
        assertEquals(AppHttpCodeEnum.LOGIN_PASSWORD_ERROR.getCode(), wrongPwd.getCode());
        assertEquals(unknown.getMessage(), wrongPwd.getMessage());
    }

    @Test
    @DisplayName("账号已停用单独告知，且不更新 last_login_time（停用是管理动作，不是密码问题）")
    void disabledAccountIsToldApartAndDoesNotTouchLastLoginTime() {
        when(accountMapper.selectOne(any())).thenReturn(account(1, ApAdminAccount.STATUS_DISABLED, "$2a$10$hash"));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        ResponseResult result = service.verifyCredentials("ops1", "right");

        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), result.getCode());
        assertEquals("该运营账号已停用，请联系超级管理员", result.getMessage());
        verify(accountMapper, never()).updateById(any(ApAdminAccount.class));
    }

    @Test
    @DisplayName("登录成功 → 记录 last_login_time，并返回会话要用的四个字段")
    void successfulLoginRecordsLastLoginTimeAndReturnsIdentityFields() {
        when(accountMapper.selectOne(any())).thenReturn(account(1001, ApAdminAccount.STATUS_ENABLED, "$2a$10$hash"));
        when(passwordEncoder.matches(eq("right"), anyString())).thenReturn(true);

        ResponseResult result = service.verifyCredentials("  ops1001  ", "right");

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) result.getData();
        assertEquals(1001, data.get("accountId"));
        assertTrue(data.containsKey("username"));
        assertTrue(data.containsKey("nickName"));
        assertTrue(data.containsKey("mustChangePassword"));

        ArgumentCaptor<ApAdminAccount> captor = ArgumentCaptor.forClass(ApAdminAccount.class);
        verify(accountMapper).updateById(captor.capture());
        assertEquals(1001, captor.getValue().getId());
        assertNotNull(captor.getValue().getLastLoginTime(), "登录成功要留时间戳，否则查不到谁长期没登录");
    }

    // ==================== 身份自述 ====================

    @Test
    @DisplayName("没有角色的账号 → 200 + isAdmin=false（不是 403，否则前端只能收到一个语焉不详的拒绝）")
    void currentIdentityReportsNoPermissionsForAccountWithoutRoles() {
        actingAs(1001);
        ApAdminAccount entity = account(1001, ApAdminAccount.STATUS_ENABLED, null);
        when(accountMapper.selectOne(any())).thenReturn(entity);

        ResponseResult result = service.currentIdentity();

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
        AdminIdentityVO vo = (AdminIdentityVO) result.getData();
        assertEquals(1001, vo.getAccountId());
        assertFalse(vo.getIsAdmin());
        assertTrue(vo.getPermissions().isEmpty());
    }

    @Test
    @DisplayName("会话还在但账号已被删 → 按未登录处理，让前端回登录页")
    void currentIdentityFallsBackToNeedLoginWhenAccountIsGone() {
        actingAs(1001);
        when(accountMapper.selectOne(any())).thenReturn(null);

        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), service.currentIdentity().getCode());
    }

    // ==================== 新建账号 ====================

    private AdminAccountCreateDto createDto(String username, String password, String roleCode, String reason) {
        AdminAccountCreateDto dto = new AdminAccountCreateDto();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setRoleCode(roleCode);
        dto.setReason(reason);
        return dto;
    }

    @Test
    @DisplayName("理由必填：审计表的 reason 是 NOT NULL，且事后补不回来")
    void createAccountRequiresReason() {
        ResponseResult result = service.createAccount(createDto("ops01", "Passw0rd123", "OPERATOR", "   "));

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        verify(accountMapper, never()).insert(any(ApAdminAccount.class));
    }

    @Test
    @DisplayName("口令太短 → 拒绝，且不落任何数据")
    void createAccountRejectsShortPasswordBeforeAnyWrite() {
        ResponseResult result = service.createAccount(createDto("ops01", "123", "OPERATOR", "开通"));

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        verify(accountMapper, never()).insert(any(ApAdminAccount.class));
    }

    @Test
    @DisplayName("角色编码认不出 → 先拒掉，不要留下一个「建了一半」的账号")
    void createAccountRejectsUnknownRoleBeforeAnyWrite() {
        ResponseResult result = service.createAccount(createDto("ops01", "Passw0rd123", "SUPER_ADMIN_V2", "开通"));

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        verify(accountMapper, never()).insert(any(ApAdminAccount.class));
        verify(accountRoleMapper, never()).insert(any(ApAdminAccountRole.class));
    }

    @Test
    @DisplayName("登录名重复 → 拒绝")
    void createAccountRejectsDuplicateUsername() {
        when(accountMapper.selectOne(any())).thenReturn(account(1, ApAdminAccount.STATUS_ENABLED, null));

        ResponseResult result = service.createAccount(createDto("ops1", "Passw0rd123", "OPERATOR", "开通"));

        assertEquals(AppHttpCodeEnum.DATA_EXIST.getCode(), result.getCode());
        verify(accountMapper, never()).insert(any(ApAdminAccount.class));
    }

    @Test
    @DisplayName("建号成功 → 落库的是 BCrypt 哈希、状态启用、强制改口令；角色绑定记下授权人")
    void createAccountStoresBcryptHashAndForcesPasswordChange() {
        actingAs(ME);
        when(accountMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode("Passw0rd123")).thenReturn("$2a$10$encoded");
        // 模拟自增主键回填：不模拟的话 account.getId() 是 null，角色绑定会落成无效数据
        doAnswer(inv -> {
            ((ApAdminAccount) inv.getArgument(0)).setId(1001);
            return 1;
        }).when(accountMapper).insert(any(ApAdminAccount.class));

        ResponseResult result = service.createAccount(createDto("ops01", "Passw0rd123", "OPERATOR", "开一个新运营"));

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
        ArgumentCaptor<ApAdminAccount> created = ArgumentCaptor.forClass(ApAdminAccount.class);
        verify(accountMapper).insert(created.capture());
        assertEquals("$2a$10$encoded", created.getValue().getPassword(), "落库的必须是哈希");
        assertNotEquals("Passw0rd123", created.getValue().getPassword(), "明文口令绝不能出现在插入语句里");
        assertEquals(ApAdminAccount.STATUS_ENABLED, created.getValue().getStatus());
        assertEquals(1, created.getValue().getMustChangePassword(), "超管设定的初始口令要强制更换");

        ArgumentCaptor<ApAdminAccountRole> binding = ArgumentCaptor.forClass(ApAdminAccountRole.class);
        verify(accountRoleMapper).insert(binding.capture());
        assertEquals(1001, binding.getValue().getAccountId());
        assertEquals(AdminRole.OPERATOR.getCode(), binding.getValue().getRoleCode());
        // 授权人取自会话而不是入参：入参有伪造空间，而审计里错一个授权人等于没有审计
        assertEquals(ME, binding.getValue().getGrantedBy());
    }

    // ==================== 启用 / 停用 ====================

    private AdminAccountStatusDto statusDto(int status) {
        AdminAccountStatusDto dto = new AdminAccountStatusDto();
        dto.setStatus(status);
        dto.setReason("测试理由");
        return dto;
    }

    @Test
    @DisplayName("不能停用自己：否则一次误点就把自己关在门外")
    void cannotDisableOwnAccount() {
        actingAs(1001);
        when(accountMapper.selectById(1001)).thenReturn(account(1001, ApAdminAccount.STATUS_ENABLED, null));

        ResponseResult result = service.updateStatus(1001, statusDto(ApAdminAccount.STATUS_DISABLED));

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        verify(accountMapper, never()).updateById(any(ApAdminAccount.class));
    }

    @Test
    @DisplayName("不能停用最后一个可用超管：别的超管账号都停用时，等于把角色管理锁死")
    void cannotDisableLastUsableSuperAdmin() {
        actingAs(ME);
        when(accountMapper.selectById(1001)).thenReturn(account(1001, ApAdminAccount.STATUS_ENABLED, null));
        // 1001 是超管
        when(accountRoleMapper.selectCount(any())).thenReturn(1L);
        // 还有一个超管是 1002 —— 但它已停用
        when(accountRoleMapper.selectList(any())).thenReturn(List.of(binding(1002)));
        when(accountMapper.selectCount(any())).thenReturn(0L);

        ResponseResult result = service.updateStatus(1001, statusDto(ApAdminAccount.STATUS_DISABLED));

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("最后一个可用超管"));
        verify(accountMapper, never()).updateById(any(ApAdminAccount.class));
    }

    private ApAdminAccountRole binding(int accountId) {
        ApAdminAccountRole binding = new ApAdminAccountRole();
        binding.setAccountId(accountId);
        binding.setRoleCode(AdminRole.SUPER_ADMIN.getCode());
        return binding;
    }

    // ==================== 角色回收 ====================

    private AdminRoleChangeDto roleDto(String roleCode) {
        AdminRoleChangeDto dto = new AdminRoleChangeDto();
        dto.setRoleCode(roleCode);
        dto.setReason("测试理由");
        return dto;
    }

    @Test
    @DisplayName("回收超管角色：只剩一个「已停用」的超管同样要拒（只看绑定不看状态，就会把能用的超管清零）")
    void revokeRoleRejectsWhenTheOnlyOtherSuperAdminIsDisabled() {
        actingAs(ME);
        when(accountRoleMapper.selectCount(any())).thenReturn(1L);
        when(accountRoleMapper.selectList(any())).thenReturn(List.of(binding(1002)));
        when(accountMapper.selectCount(any())).thenReturn(0L);

        ResponseResult result = service.revokeRole(1001, roleDto(AdminRole.SUPER_ADMIN.getCode()));

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("最后一个可用超管"));
        verify(accountRoleMapper, never()).delete(any());
    }

    @Test
    @DisplayName("确实还有启用的超管 → 允许回收，并留审计")
    void revokeRoleSucceedsWhenAnotherEnabledSuperAdminExists() {
        actingAs(ME);
        when(accountRoleMapper.selectCount(any())).thenReturn(1L);
        when(accountRoleMapper.selectList(any())).thenReturn(List.of(binding(1002)));
        when(accountMapper.selectCount(any())).thenReturn(1L);

        ResponseResult result = service.revokeRole(1001, roleDto(AdminRole.SUPER_ADMIN.getCode()));

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
        verify(accountRoleMapper).delete(any());
        ArgumentCaptor<ApAdminAuditLog> audit = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditSink).recordSuccess(audit.capture());
        assertEquals(ApAdminAuditLog.MODULE_ADMIN, audit.getValue().getModule());
    }

    @Test
    @DisplayName("重复授予同一个角色 → 明确拒绝，不静默成功（静默成功会让「他到底有几个角色」变得无从查证）")
    void grantRoleRejectsDuplicate() {
        actingAs(ME);
        when(accountMapper.selectById(1001)).thenReturn(account(1001, ApAdminAccount.STATUS_ENABLED, null));
        when(accountRoleMapper.selectCount(any())).thenReturn(1L);

        ResponseResult result = service.grantRole(1001, roleDto(AdminRole.OPERATOR.getCode()));

        assertEquals(AppHttpCodeEnum.DATA_EXIST.getCode(), result.getCode());
        verify(accountRoleMapper, never()).insert(any(ApAdminAccountRole.class));
    }

    // ==================== 撤销标记 ====================

    private void stubRedis() {
        when(cacheService.getstringRedisTemplate()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("停用写撤销标记、启用清掉它（JWT 只能等过期，会话可以立刻踢）")
    void disablingAccountWritesRevocationMarkerAndEnablingClearsIt() {
        actingAs(ME);
        stubRedis();
        when(accountMapper.selectById(1001)).thenReturn(account(1001, ApAdminAccount.STATUS_ENABLED, null));
        when(accountRoleMapper.selectCount(any())).thenReturn(0L);
        String key = "admin:session:revoked:account:1001";

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(),
            service.updateStatus(1001, statusDto(ApAdminAccount.STATUS_DISABLED)).getCode());
        verify(valueOps).set(eq(key), eq("1"), eq(Duration.ofHours(12)));

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(),
            service.updateStatus(1001, statusDto(ApAdminAccount.STATUS_ENABLED)).getCode());
        verify(redisTemplate).delete(key);
    }

    @Test
    @DisplayName("撤销标记写失败只告警：账号状态才是事实来源，不该让整个停用回滚")
    void revocationMarkerFailureDoesNotFailTheDisable() {
        actingAs(ME);
        stubRedis();
        when(accountMapper.selectById(1001)).thenReturn(account(1001, ApAdminAccount.STATUS_ENABLED, null));
        when(accountRoleMapper.selectCount(any())).thenReturn(0L);
        doThrow(new RuntimeException("redis down")).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        ResponseResult result = service.updateStatus(1001, statusDto(ApAdminAccount.STATUS_DISABLED));

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode(), "标记只是加速件，不该反过来把停用弄失败");
        verify(accountMapper).updateById(any(ApAdminAccount.class));
    }

    // ==================== 修改自己的口令 ====================

    private AdminPasswordChangeDto pwdDto(String oldPwd, String newPwd) {
        AdminPasswordChangeDto dto = new AdminPasswordChangeDto();
        dto.setOldPassword(oldPwd);
        dto.setNewPassword(newPwd);
        return dto;
    }

    @Test
    @DisplayName("改自己的口令要先证明是本人：光有会话不够（共用电脑、忘记退出）")
    void changeOwnPasswordRequiresCorrectOldPassword() {
        actingAs(1001);
        when(accountMapper.selectById(1001)).thenReturn(account(1001, ApAdminAccount.STATUS_ENABLED, "$2a$10$hash"));
        when(passwordEncoder.matches(eq("wrong"), anyString())).thenReturn(false);

        ResponseResult result = service.changeOwnPassword(pwdDto("wrong", "NewPassw0rd"));

        assertEquals(AppHttpCodeEnum.LOGIN_PASSWORD_ERROR.getCode(), result.getCode());
        verify(accountMapper, never()).updateById(any(ApAdminAccount.class));
    }

    @Test
    @DisplayName("新口令不能与旧口令相同（强制改密的场景下，原样填回去等于没改）")
    void changeOwnPasswordRejectsReusingOldPassword() {
        actingAs(1001);
        when(accountMapper.selectById(1001)).thenReturn(account(1001, ApAdminAccount.STATUS_ENABLED, "$2a$10$hash"));
        when(passwordEncoder.matches(eq("SamePassw0rd"), anyString())).thenReturn(true);

        ResponseResult result = service.changeOwnPassword(pwdDto("SamePassw0rd", "SamePassw0rd"));

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
        verify(accountMapper, never()).updateById(any(ApAdminAccount.class));
    }

    @Test
    @DisplayName("改密成功 → 存哈希、并清掉「仍在使用初始口令」标记（否则网关会一直拦着）")
    void changeOwnPasswordClearsInitialPasswordFlag() {
        actingAs(1001);
        when(accountMapper.selectById(1001)).thenReturn(account(1001, ApAdminAccount.STATUS_ENABLED, "$2a$10$hash"));
        when(passwordEncoder.matches(eq("OldPassw0rd"), anyString())).thenReturn(true);
        when(passwordEncoder.encode("NewPassw0rd")).thenReturn("$2a$10$new");

        ResponseResult result = service.changeOwnPassword(pwdDto("OldPassw0rd", "NewPassw0rd"));

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
        ArgumentCaptor<ApAdminAccount> updated = ArgumentCaptor.forClass(ApAdminAccount.class);
        verify(accountMapper).updateById(updated.capture());
        assertEquals("$2a$10$new", updated.getValue().getPassword());
        assertEquals(0, updated.getValue().getMustChangePassword());
    }

    @Test
    @DisplayName("没有会话上下文 → 按未登录拒绝（本方法靠 AdminContext 取身份，没有就无从来证明是本人）")
    void changeOwnPasswordWithoutSessionIsRejected() {
        AdminContext.clear();

        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(),
            service.changeOwnPassword(pwdDto("a", "NewPassw0rd")).getCode());
    }
}
