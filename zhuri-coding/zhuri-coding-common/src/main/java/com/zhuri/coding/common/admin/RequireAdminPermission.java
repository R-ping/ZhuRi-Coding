package com.zhuri.coding.common.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.zhuri.coding.model.admin.AdminPermission;

/**
 * 标注运营接口所需的权限点。
 *
 * <p>由 {@link AdminAuthInterceptor} 在请求进入 Controller 前校验：
 * 未登录 → 401；已登录但无该权限 → 403。
 *
 * <p><b>为什么必须显式标注</b>：不标注就不会被校验。为避免"新接口忘记加注解而裸奔"，
 * {@link AdminAuthInterceptor} 对已纳入运营鉴权的**所有**接口做兜底处理 ——
 * 未标注权限点的运营接口一律拒绝（fail-closed）。宁可上线时发现漏标，
 * 也不要出现一个谁都能调的运营接口。
 *
 * <p><b>漏挂路径是另一半风险</b>：注解只在请求真的经过拦截器时才生效，
 * 而拦截器是按路径前缀注册的（见各服务的 WebMvcConfig）。新开一个运营前缀却忘了注册，
 * 接口就是裸奔。两侧都有回归测试盯着（反射检查：注解在不在、路径覆盖没覆盖）。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdminPermission {

    /** 所需权限点 */
    AdminPermission value();
}
