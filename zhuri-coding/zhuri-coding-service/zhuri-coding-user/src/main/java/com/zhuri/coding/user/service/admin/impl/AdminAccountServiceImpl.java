package com.zhuri.coding.user.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.common.admin.AdminContext;
import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.AdminRole;
import com.zhuri.coding.model.admin.dtos.AdminAccountCreateDto;
import com.zhuri.coding.model.admin.dtos.AdminAccountStatusDto;
import com.zhuri.coding.model.admin.dtos.AdminPasswordChangeDto;
import com.zhuri.coding.model.admin.dtos.AdminRoleChangeDto;
import com.zhuri.coding.model.admin.pojos.ApAdminAccount;
import com.zhuri.coding.model.admin.pojos.ApAdminAccountRole;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminAccountVO;
import com.zhuri.coding.model.admin.vos.AdminIdentityVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.user.mapper.ApAdminAccountMapper;
import com.zhuri.coding.user.mapper.ApAdminAccountRoleMapper;
import com.zhuri.coding.user.service.admin.AdminAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营账号与角色管理的实现（库：leadnews_user）。
 *
 * <p><b>几条贯穿全类的不变量</b>，分散在各个方法里但属于同一条规则：
 * <ul>
 *   <li><b>不能把自己关在门外</b>：不能停用自己。</li>
 *   <li><b>系统必须始终有一个能用的超管</b>：回收最后一个超管角色、停用最后一个启用的超管账号，
 *       都会被拒。否则一次误操作就能把角色管理永久锁死 —— 而修它只能去连数据库改表。</li>
 *   <li><b>登录失败不区分"账号不存在"与"口令错误"</b>：区分开等于送出一个账号枚举接口。</li>
 *   <li><b>所有写操作都留审计</b>，且 {@code recordSuccess} 与业务同事务（见 {@link AdminAuditSink}）。
 *       唯独登录不记 —— 审计表 {@code reason} 是 NOT NULL，而"某人登录成功"没有理由可写，
 *       硬填一个占位串只会污染审计表。</li>
 * </ul>
 */
@Slf4j
@Service
public class AdminAccountServiceImpl implements AdminAccountService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int REASON_MAX_LEN = 500;
    private static final int USERNAME_MAX = 64;
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 64;

    /** 审计里的对象类型 */
    private static final String TARGET_TYPE = "ADMIN_ACCOUNT";

    /**
     * 停用账号时写入的"撤销标记"存活时长。
     *
     * <p>必须 **≥ 网关侧会话的最大空闲时长**：标记比会话先过期的话，会出现"标记没了、
     * 会话还在"，停用就又变成延迟生效了。取 12h 是留足余量（网关会话空闲超时为 8h）。
     */
    private static final Duration REVOKE_TTL = Duration.ofHours(12);

    /**
     * 撤销标记的 Redis 键前缀。
     *
     * <p>⚠️ <b>必须与网关侧 {@code AdminSessionKeys.REVOKED_PREFIX} 逐字一致。</b>
     * 两个模块刻意不互相依赖（网关是 WebFlux，common 里有 servlet 组件，
     * 让网关依赖 common 会把 MVC 拖进响应式应用），所以这个键是跨模块的口头契约。
     * 改名时两边一起改，且要记住没有编译器会提醒你。
     */
    static final String REVOKED_KEY_PREFIX = "admin:session:revoked:account:";

    @Autowired
    private ApAdminAccountMapper accountMapper;

    @Autowired
    private ApAdminAccountRoleMapper accountRoleMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private com.zhuri.coding.user.admin.UserAdminAuditRecorder auditSink;

    @Autowired
    private CacheService cacheService;

    // ==================== 登录凭据校验 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult verifyCredentials(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE);
        }
        ApAdminAccount account = findByUsername(username.trim());
        // 账号不存在 / 口令错误共用同一个返回：区分开就等于对外提供了一个账号枚举接口
        if (account == null || !passwordEncoder.matches(rawPassword, account.getPassword())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.LOGIN_PASSWORD_ERROR);
        }
        // 停用单独告知：这是管理动作的结果，运营需要知道"不是密码错了，是号被停了"，
        // 否则只会反复试密码然后来找运维。停用状态下也不更新 last_login_time。
        if (!account.isEnabled()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "该运营账号已停用，请联系超级管理员");
        }

        ApAdminAccount touch = new ApAdminAccount();
        touch.setId(account.getId());
        touch.setLastLoginTime(new Date());
        touch.setUpdatedTime(new Date());
        accountMapper.updateById(touch);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", account.getId());
        data.put("username", account.getUsername());
        data.put("nickName", account.getNickName());
        data.put("mustChangePassword", account.getMustChangePassword());
        return ResponseResult.okResult(data);
    }

    // ==================== 身份自述 ====================

    @Override
    public ResponseResult currentIdentity() {
        AdminIdentity identity = AdminContext.get();
        if (identity == null || identity.getUserId() == null) {
            // 走到这里说明网关放行了但没带上身份（例如内部直连），按未登录处理
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        // 显式收窄投影：这个 VO 会直接序列化给前端，把 password（BCrypt 哈希）读进内存毫无必要，
        // 而"读进来了"和"漏出去了"之间只隔一次手滑的字段复制。
        ApAdminAccount account = accountMapper.selectOne(new LambdaQueryWrapper<ApAdminAccount>()
            .select(ApAdminAccount::getId, ApAdminAccount::getUsername, ApAdminAccount::getNickName,
                ApAdminAccount::getMustChangePassword, ApAdminAccount::getStatus)
            .eq(ApAdminAccount::getId, identity.getUserId()));
        if (account == null) {
            // 会话还在、账号已被删 —— 当作未登录，让前端回到登录页重新登录
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        AdminIdentityVO vo = new AdminIdentityVO();
        vo.setAccountId(account.getId());
        vo.setUsername(account.getUsername());
        vo.setNickName(account.getNickName());
        vo.setIsAdmin(identity.isAdmin());
        vo.setRoleCodes(identity.getRoleCodes());
        // 权限点排序后返回：前端渲染按钮顺序稳定，不然每次刷新菜单都跳
        List<String> permissions = new ArrayList<>();
        for (AdminPermission permission : identity.getPermissions()) {
            permissions.add(permission.getCode());
        }
        permissions.sort(String::compareTo);
        vo.setPermissions(permissions);
        vo.setMustChangePassword(account.getMustChangePassword());
        return ResponseResult.okResult(vo);
    }

    // ==================== 账号查询 ====================

    @Override
    public ResponseResult pageAccounts(Integer page, Integer size, String keyword) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;

        LambdaQueryWrapper<ApAdminAccount> wrapper = new LambdaQueryWrapper<ApAdminAccount>()
            // 收窄投影：password / must_change_password 之外还要显式排掉 password
            .select(ApAdminAccount::getId, ApAdminAccount::getUsername, ApAdminAccount::getNickName,
                ApAdminAccount::getStatus, ApAdminAccount::getMustChangePassword,
                ApAdminAccount::getLastLoginTime, ApAdminAccount::getCreatedTime,
                ApAdminAccount::getRemark)
            .orderByAsc(ApAdminAccount::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(ApAdminAccount::getUsername, kw)
                .or().like(ApAdminAccount::getNickName, kw));
        }

        IPage<ApAdminAccount> result = accountMapper.selectPage(new Page<>(p, s), wrapper);
        List<AdminAccountVO> list = new ArrayList<>();
        for (ApAdminAccount account : result.getRecords()) {
            AdminAccountVO vo = new AdminAccountVO();
            vo.setId(account.getId());
            vo.setUsername(account.getUsername());
            vo.setNickName(account.getNickName());
            vo.setStatus(account.getStatus());
            vo.setMustChangePassword(account.getMustChangePassword());
            vo.setLastLoginTime(account.getLastLoginTime());
            vo.setCreatedTime(account.getCreatedTime());
            vo.setRemark(account.getRemark());
            list.add(vo);
        }
        fillRoleCodes(list);

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        data.put("page", p);
        data.put("size", s);
        return ResponseResult.okResult(data);
    }

    /**
     * 一次性把这页账号的角色查出来，按 accountId 分组回填。
     *
     * <p>刻意不做"每行查一次"：账号列表默认每页 20 行，那就是 20 次往返 —— 而这是一次
     * 人工点击触发的页面，本该一次往返完成。
     */
    private void fillRoleCodes(List<AdminAccountVO> list) {
        if (list.isEmpty()) {
            return;
        }
        List<Integer> ids = new ArrayList<>();
        for (AdminAccountVO vo : list) {
            ids.add(vo.getId());
        }
        Map<Integer, List<String>> grouped = new HashMap<>();
        for (ApAdminAccountRole binding : accountRoleMapper.selectList(
                new LambdaQueryWrapper<ApAdminAccountRole>()
                    .select(ApAdminAccountRole::getAccountId, ApAdminAccountRole::getRoleCode)
                    .in(ApAdminAccountRole::getAccountId, ids))) {
            String code = binding.getRoleCode();
            if (code == null || code.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(binding.getAccountId(), k -> new ArrayList<>()).add(code.trim());
        }
        for (AdminAccountVO vo : list) {
            // 空列表而不是 null：前端可以直接 v-for，不必再判空
            vo.setRoleCodes(grouped.getOrDefault(vo.getId(), new ArrayList<>()));
        }
    }

    // ==================== 账号管理 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createAccount(AdminAccountCreateDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        if (dto == null || dto.getUsername() == null || dto.getUsername().isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "请填写登录名");
        }
        String username = dto.getUsername().trim();
        if (username.length() > USERNAME_MAX) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "登录名不能超过" + USERNAME_MAX + "个字符");
        }
        if (dto.getPassword() == null || dto.getPassword().length() < PASSWORD_MIN
            || dto.getPassword().length() > PASSWORD_MAX) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "口令长度需在 " + PASSWORD_MIN + "~" + PASSWORD_MAX + " 个字符之间");
        }
        // 角色编码先校验再落库：先建号再报错会留下一个"建了一半"的账号
        AdminRole role = null;
        if (dto.getRoleCode() != null && !dto.getRoleCode().isBlank()) {
            role = AdminRole.parse(dto.getRoleCode());
            if (role == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "无法识别的角色编码：" + dto.getRoleCode());
            }
        }
        if (findByUsername(username) != null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_EXIST, "登录名已存在");
        }

        Date now = new Date();
        ApAdminAccount account = new ApAdminAccount();
        account.setUsername(username);
        account.setPassword(passwordEncoder.encode(dto.getPassword()));
        account.setNickName(dto.getNickName() == null || dto.getNickName().isBlank()
            ? username : dto.getNickName().trim());
        account.setStatus(ApAdminAccount.STATUS_ENABLED);
        // 由超管设定的初始口令同样标记为"待更换"：它不是使用者自己选的
        account.setMustChangePassword(1);
        account.setCreatedTime(now);
        account.setUpdatedTime(now);
        account.setRemark(dto.getReason().trim());
        accountMapper.insert(account);

        if (role != null) {
            insertRoleBinding(account.getId(), role.getCode(), dto.getReason().trim());
        }

        auditSink.recordSuccess(AdminAuditSink.entry(ApAdminAuditLog.MODULE_ADMIN,
            "ADMIN_ACCOUNT_CREATE", TARGET_TYPE, String.valueOf(account.getId()),
            dto.getReason().trim()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", account.getId());
        data.put("username", account.getUsername());
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateStatus(Integer accountId, AdminAccountStatusDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        if (accountId == null || dto == null || dto.getStatus() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE);
        }
        int status = dto.getStatus();
        if (status != ApAdminAccount.STATUS_ENABLED && status != ApAdminAccount.STATUS_DISABLED) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "状态只能是 1（启用）或 0（停用）");
        }
        ApAdminAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "运营账号不存在");
        }

        AdminIdentity me = AdminContext.get();
        if (status == ApAdminAccount.STATUS_DISABLED) {
            if (me != null && accountId.equals(me.getUserId())) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "不能停用自己的账号；如需退出请使用登出");
            }
            ResponseResult lockout = guardLastSuperAdmin(accountId, "停用");
            if (lockout != null) {
                return lockout;
            }
        }

        ApAdminAccount update = new ApAdminAccount();
        update.setId(accountId);
        update.setStatus(status);
        update.setUpdatedTime(new Date());
        accountMapper.updateById(update);
        // 停用要立刻踢掉已签发的会话：会话是服务端状态的，这里写一个撤销标记，
        // 网关每个请求查一次。这是用会话替代 JWT 换来的好处 —— JWT 只能等它过期。
        syncRevocationMarker(accountId, status);

        auditSink.recordSuccess(AdminAuditSink.entry(ApAdminAuditLog.MODULE_ADMIN,
            "ADMIN_ACCOUNT_STATUS", TARGET_TYPE, String.valueOf(accountId),
            (status == ApAdminAccount.STATUS_ENABLED ? "启用：" : "停用：") + dto.getReason().trim()));
        return ResponseResult.okResult();
    }

    // ==================== 角色授予 / 回收 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult grantRole(Integer accountId, AdminRoleChangeDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        if (accountId == null || dto == null || dto.getRoleCode() == null || dto.getRoleCode().isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "请指定账号与角色");
        }
        ApAdminAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "运营账号不存在");
        }
        AdminRole role = AdminRole.parse(dto.getRoleCode());
        if (role == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "无法识别的角色编码：" + dto.getRoleCode());
        }
        if (hasRole(accountId, role.getCode())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_EXIST,
                "该账号已持有 " + role.getCode() + " 角色，无需重复授予");
        }

        insertRoleBinding(accountId, role.getCode(), dto.getReason().trim());
        auditSink.recordSuccess(AdminAuditSink.entry(ApAdminAuditLog.MODULE_ADMIN,
            "ADMIN_ROLE_GRANT", TARGET_TYPE, String.valueOf(accountId),
            "授予 " + role.getCode() + "：" + dto.getReason().trim()));
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult revokeRole(Integer accountId, AdminRoleChangeDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        if (accountId == null || dto == null || dto.getRoleCode() == null || dto.getRoleCode().isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "请指定账号与角色");
        }
        AdminRole role = AdminRole.parse(dto.getRoleCode());
        if (role == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "无法识别的角色编码：" + dto.getRoleCode());
        }
        if (!hasRole(accountId, role.getCode())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "该账号未持有此角色，无需回收");
        }
        if (role == AdminRole.SUPER_ADMIN) {
            // 回收掉最后一个超管角色之后，角色管理页面对所有人都是 403 —— 想去数据库改表才能救回来。
            // ⚠️ 判定必须和"停用"共用同一段逻辑（guardLastSuperAdmin）：这里曾经只数角色绑定的条数，
            //    于是"A 启用中的超管 + B 已停用的超管"会被判成"还有别的超管"，把 A 的权限收掉之后
            //    系统里就一个能用的超管都不剩了。只看绑定不看状态，两种情况的口径就对不上。
            ResponseResult lockout = guardLastSuperAdmin(accountId, "回收");
            if (lockout != null) {
                return lockout;
            }
        }

        accountRoleMapper.delete(new LambdaQueryWrapper<ApAdminAccountRole>()
            .eq(ApAdminAccountRole::getAccountId, accountId)
            .eq(ApAdminAccountRole::getRoleCode, role.getCode()));
        auditSink.recordSuccess(AdminAuditSink.entry(ApAdminAuditLog.MODULE_ADMIN,
            "ADMIN_ROLE_REVOKE", TARGET_TYPE, String.valueOf(accountId),
            "回收 " + role.getCode() + "：" + dto.getReason().trim()));
        return ResponseResult.okResult();
    }

    // ==================== 修改自己的口令 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult changeOwnPassword(AdminPasswordChangeDto dto) {
        AdminIdentity me = AdminContext.get();
        if (me == null || me.getUserId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto == null || dto.getOldPassword() == null || dto.getNewPassword() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE);
        }
        if (dto.getNewPassword().length() < PASSWORD_MIN || dto.getNewPassword().length() > PASSWORD_MAX) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "新口令长度需在 " + PASSWORD_MIN + "~" + PASSWORD_MAX + " 个字符之间");
        }
        ApAdminAccount account = accountMapper.selectById(me.getUserId());
        if (account == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        // 要求先证明是本人：共用电脑或忘记退出时，光有会话不足以改掉口令
        if (!passwordEncoder.matches(dto.getOldPassword(), account.getPassword())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.LOGIN_PASSWORD_ERROR, "当前口令不正确");
        }
        if (dto.getNewPassword().equals(dto.getOldPassword())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "新口令不能与当前口令相同");
        }

        ApAdminAccount update = new ApAdminAccount();
        update.setId(account.getId());
        update.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        // 换过口令就不再是"初始口令"了
        update.setMustChangePassword(0);
        update.setUpdatedTime(new Date());
        accountMapper.updateById(update);

        auditSink.recordSuccess(AdminAuditSink.entry(ApAdminAuditLog.MODULE_ADMIN,
            "ADMIN_PASSWORD_CHANGE", TARGET_TYPE, String.valueOf(account.getId()), "本人修改口令"));
        return ResponseResult.okResult();
    }

    // ==================== 内部工具 ====================

    private ApAdminAccount findByUsername(String username) {
        return accountMapper.selectOne(new LambdaQueryWrapper<ApAdminAccount>()
            .eq(ApAdminAccount::getUsername, username)
            .last("LIMIT 1"));
    }

    private boolean hasRole(Integer accountId, String roleCode) {
        Long count = accountRoleMapper.selectCount(new LambdaQueryWrapper<ApAdminAccountRole>()
            .eq(ApAdminAccountRole::getAccountId, accountId)
            .eq(ApAdminAccountRole::getRoleCode, roleCode));
        return count != null && count > 0;
    }

    private void insertRoleBinding(Integer accountId, String roleCode, String reason) {
        AdminIdentity me = AdminContext.get();
        ApAdminAccountRole binding = new ApAdminAccountRole();
        binding.setAccountId(accountId);
        binding.setRoleCode(roleCode);
        // 授权人取当前会话，而不是让调用方传：传参就有伪造空间，而审计表里错一个授权人等于没有审计
        binding.setGrantedBy(me == null ? null : me.getUserId());
        binding.setGrantedTime(new Date());
        binding.setRemark(reason);
        accountRoleMapper.insert(binding);
    }

    /**
     * 保证"停用/回收之后系统里仍有可用的超管"。
     *
     * <p>只在该账号确实持有 SUPER_ADMIN 时才查库 —— 绝大多数操作的目标是普通运营账号，
     * 不该为此多打两次查询。判定口径是"**另一个仍处于启用状态的**超管账号是否存在"：
     * 只看角色绑定不够，一个停用状态超管账号救不了场。
     */
    private ResponseResult guardLastSuperAdmin(Integer accountId, String action) {
        if (!hasRole(accountId, AdminRole.SUPER_ADMIN.getCode())) {
            return null;
        }
        List<Integer> otherSuperIds = new ArrayList<>();
        for (ApAdminAccountRole binding : accountRoleMapper.selectList(
                new LambdaQueryWrapper<ApAdminAccountRole>()
                    .select(ApAdminAccountRole::getAccountId)
                    .eq(ApAdminAccountRole::getRoleCode, AdminRole.SUPER_ADMIN.getCode())
                    .ne(ApAdminAccountRole::getAccountId, accountId))) {
            otherSuperIds.add(binding.getAccountId());
        }
        if (otherSuperIds.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "系统必须保留至少一个超管账号，不能" + action + "最后一个超管");
        }
        Long enabled = accountMapper.selectCount(new LambdaQueryWrapper<ApAdminAccount>()
            .in(ApAdminAccount::getId, otherSuperIds)
            .eq(ApAdminAccount::getStatus, ApAdminAccount.STATUS_ENABLED));
        if (enabled == null || enabled == 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "其余超管账号都处于停用状态，不能" + action + "最后一个可用超管");
        }
        return null;
    }

    /** 停用写撤销标记、启用清掉它；键格式见 {@link #REVOKED_KEY_PREFIX} */
    private void syncRevocationMarker(Integer accountId, int status) {
        String key = REVOKED_KEY_PREFIX + accountId;
        try {
            if (status == ApAdminAccount.STATUS_DISABLED) {
                cacheService.getstringRedisTemplate().opsForValue().set(key, "1", REVOKE_TTL);
            } else {
                cacheService.getstringRedisTemplate().delete(key);
            }
        } catch (Exception e) {
            // 账号状态已经落库（那是事实来源），标记只是"尽快踢掉会话"的加速件。
            // 写不进去最多让会话多活一会儿，不该让整个停用操作回滚 —— 于是只告警。
            log.error("写入会话撤销标记失败，停用将在会话过期后才生效, accountId={}", accountId, e);
        }
    }

    /** 理由必填（审计表 reason 列 NOT NULL，且事后补不回来）；返回 null 表示通过 */
    private ResponseResult validateReason(String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写操作理由");
        }
        if (reason.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "操作理由不能超过" + REASON_MAX_LEN + "字");
        }
        return null;
    }
}
