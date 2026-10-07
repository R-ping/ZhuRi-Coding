package com.zhuri.coding.user.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.common.admin.AdminRoleResolver;
import com.zhuri.coding.model.admin.pojos.ApAdminAccount;
import com.zhuri.coding.model.admin.pojos.ApAdminAccountRole;
import com.zhuri.coding.user.mapper.ApAdminAccountMapper;
import com.zhuri.coding.user.mapper.ApAdminAccountRoleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析运营账号的身份（user 侧实现：角色就存在本库，直接查表，不走网络）。
 *
 * <p><b>入参是运营账号 ID（{@code ap_admin_account.id}），不是 C 端账号 ID。</b>
 * 运营账号与 C 端账号是两套完全隔离的 ID 空间（见 {@code ApAdminAccount}），
 * 所以这里既不查 {@code ap_user} 也不做任何"是不是同一个 id"的判断。
 *
 * <p><b>为什么查角色之前先确认这个 ID 是一个启用中的运营账号</b>：只按 ID 查角色表的话，
 * 「没有任何权限」这句保证就完全依赖调用方传对了 ID —— 而 ID 只是一个整数，传错不会有任何信号。
 * 一旦某条链路上传进来的是 C 端用户 ID，它只要凑巧等于某个持有角色的运营账号 ID，
 * 就会静默通过。补一次主键查询把这个"凑巧"变成不可能：<b>没有运营账号行，就没有角色</b>，
 * 这条保证不再依赖上游路由是否正确。
 * 顺带获得的收益是：会话还活着但账号刚被停用时，这里直接判空，不必等撤销标记生效。
 *
 * <p><b>这是 user 服务里"角色从哪来"的唯一一处查询</b>。{@code UserFeignController#getAdminRoles}
 * 对外提供的角色列表也复用它 —— 如果两处各查一次，将来加"角色有效期"之类的过滤条件时
 * 必然只改一侧，表现成"运营后台里能用，但 content 侧鉴权认不出"。
 *
 * <p><b>为什么不加缓存</b>：运营接口是人工低频操作（一次点击一个请求），一次主键前缀查询
 * 的代价可以忽略；不缓存意味着<b>回收角色立即生效</b>，不会出现"已撤权但还能操作 N 秒"的窗口。
 *
 * <p><b>失败语义</b>：查表异常时返回空身份（无任何权限），即 fail-closed。
 * 宁可"这段时间运营后台用不了"，也不能"数据库抖一下就把后台向所有人敞开"。
 */
@Slf4j
@Component
public class LocalAdminRoleResolver implements AdminRoleResolver {

    @Autowired
    private ApAdminAccountRoleMapper adminAccountRoleMapper;

    @Autowired
    private ApAdminAccountMapper adminAccountMapper;

    /**
     * 解析指定运营账号的运营身份。
     *
     * @param accountId 运营账号ID；为 null 时返回空身份
     * @return 永不为 null；无角色与解析失败均为空身份
     */
    @Override
    public AdminIdentity resolve(Integer accountId) {
        if (accountId == null) {
            return AdminIdentity.anonymous(null);
        }
        try {
            return new AdminIdentity(accountId, roleCodesOf(accountId));
        } catch (Exception e) {
            log.error("解析运营身份失败，按无权限处理, accountId={}", accountId, e);
            return AdminIdentity.anonymous(accountId);
        }
    }

    /**
     * 查询运营账号持有的全部角色编码。
     *
     * <p><b>先确认该 ID 是一个启用中的运营账号</b>，否则直接返空（理由见类注释）。
     *
     * <p><b>不过滤代码认不出的编码</b>：库里若存在旧版本遗留或手写 SQL 打错的角色码，
     * 原样返回给调用方（鉴权侧 {@code AdminRole.parse} 会按 fail-closed 忽略），
     * 在这里悄悄吃掉只会让"配错角色"更难排查。
     *
     * @param accountId 运营账号ID
     * @return 角色编码列表；账号不存在/已停用/无角色均返回空列表，永不为 null
     */
    public List<String> roleCodesOf(Integer accountId) {
        if (!isEnabledAccount(accountId)) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        for (ApAdminAccountRole binding : adminAccountRoleMapper.selectList(
                new LambdaQueryWrapper<ApAdminAccountRole>()
                        .select(ApAdminAccountRole::getRoleCode)
                        .eq(ApAdminAccountRole::getAccountId, accountId))) {
            String code = binding.getRoleCode();
            // 必须剔掉 null/空白：AdminIdentity 用 List.copyOf 收口，混进一个 null 会直接抛 NPE
            if (code != null && !code.isBlank()) {
                roles.add(code.trim());
            }
        }
        return roles;
    }

    /**
     * 该 ID 是否对应一个启用中的运营账号。
     *
     * <p>只取 {@code id + status} 两列：主键查询，且不把 {@code password} 读进内存。
     * 任何异常都当"不是"（fail-closed）—— 这个方法返回 true 的代价是权限，判断错方向不可接受。
     */
    private boolean isEnabledAccount(Integer accountId) {
        if (accountId == null) {
            return false;
        }
        try {
            ApAdminAccount account = adminAccountMapper.selectOne(
                new LambdaQueryWrapper<ApAdminAccount>()
                    .select(ApAdminAccount::getId, ApAdminAccount::getStatus)
                    .eq(ApAdminAccount::getId, accountId));
            return account != null && account.isEnabled();
        } catch (Exception e) {
            log.error("校验运营账号状态失败，按不存在处理, accountId={}", accountId, e);
            return false;
        }
    }
}
