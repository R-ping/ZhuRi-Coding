package com.zhuri.coding.content.auth;

import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.common.admin.AdminRoleResolver;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析运营账号的身份（content 侧实现：角色存在用户库，只能走 Feign 问）。
 *
 * <p><b>入参是运营账号 ID（{@code ap_admin_account.id}），不是 C 端用户 ID。</b>
 * 本类只在这条链路上被调用：网关把运营会话解析成身份头后，content 侧拿着那个 ID 回用户服务
 * 查角色。用户服务那边会再确认"这个 ID 确实是一个启用中的运营账号"，所以即便某条链路上
 * 误传了 C 端 ID，也只会得到空角色 —— 不会因为两个 ID 空间里的数字碰巧相等而放行。
 *
 * <p><b>为什么不加缓存</b>：运营接口是人工低频操作（一次点击一个请求），一次 Feign 往返
 * 的代价可以忽略；而不缓存意味着**回收角色立即生效**，不会出现"已撤权但还能操作 N 秒"的窗口。
 * 若将来运营接口被高频调用（例如批量处置），再考虑加短 TTL 缓存，并接受撤权延迟。
 *
 * <p><b>失败语义</b>：用户服务不可用时返回空身份（无任何权限），即 fail-closed。
 * 这与 {@code IUserClientFallback#getValidUserIds} 刻意 fail-open 的取舍相反 ——
 * 那边的代价是"多发一条无人可见的站内信"，这边的代价是"把运营后台向所有人敞开"，不可比。
 */
@Slf4j
@Component
public class AdminIdentityResolver implements AdminRoleResolver {

    @Autowired
    private IUserClient userClient;

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
            ResponseResult result = userClient.getAdminRoles(accountId.longValue());
            if (result == null || result.getData() == null) {
                return AdminIdentity.anonymous(accountId);
            }
            return new AdminIdentity(accountId, toStringList(result.getData()));
        } catch (Exception e) {
            // fail-closed：解析失败按"不是运营"处理，宁可用不了，也不放行
            log.error("解析运营身份失败，按无权限处理, accountId={}", accountId, e);
            return AdminIdentity.anonymous(accountId);
        }
    }

    /** ResponseResult.data 反序列化后可能是 List<?>，逐项转字符串并剔除空值 */
    private List<String> toStringList(Object data) {
        if (!(data instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null) {
                String code = String.valueOf(item).trim();
                if (!code.isEmpty()) {
                    roles.add(code);
                }
            }
        }
        return roles;
    }
}
