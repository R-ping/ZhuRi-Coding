package com.zhuri.coding.common.admin;

import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.AdminRole;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 当前请求的运营身份（一次解析、本次请求内复用）。
 *
 * <p><b>为什么放在 common 而不是某个服务里</b>：运营鉴权现在有两个服务要用
 * （content 管内容、user 管账号），而"角色 → 权限 → 放行/拒绝"这套判定是安全逻辑，
 * 复制一份出去必然漂移 —— 将来给某一侧补上一个越权口子，另一侧不会跟着改。
 * 所以判定逻辑只有这一份。
 *
 * <p>权限集合在构造时一次性算好（{@link AdminRole#permissionsOf}），
 * 判定只是集合查找，避免在拦截器里反复解析角色编码。
 *
 * <p>非运营账号同样会得到一个 {@code AdminIdentity} 实例（角色与权限均为空集），
 * 而不是 {@code null} —— 这样"未登录"与"已登录但无运营角色"能区分开：
 * 前者是认证问题（该返回 401），后者是授权问题（403）。
 */
@Getter
public class AdminIdentity {

    /** 账号ID */
    private final Integer userId;

    /** 角色编码列表（原样保留，含代码认不出的编码，便于审计留痕） */
    private final List<String> roleCodes;

    /** 权限并集 */
    private final Set<AdminPermission> permissions;

    public AdminIdentity(Integer userId, List<String> roleCodes) {
        this.userId = userId;
        this.roleCodes = roleCodes == null ? List.of() : List.copyOf(roleCodes);
        this.permissions = AdminRole.permissionsOf(this.roleCodes);
    }

    /** 是否有任一运营角色（用于给前端返回"是否显示运营入口"） */
    public boolean isAdmin() {
        return !permissions.isEmpty();
    }

    /** 是否持有指定权限 */
    public boolean has(AdminPermission permission) {
        return permission != null && permissions.contains(permission);
    }

    /** 角色快照（审计用，逗号分隔） */
    public String roleCodesAsString() {
        return roleCodes.isEmpty() ? null : String.join(",", roleCodes);
    }

    /** 空身份（未登录时使用） */
    public static AdminIdentity anonymous(Integer userId) {
        return new AdminIdentity(userId, Collections.emptyList());
    }
}
