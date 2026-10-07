package com.zhuri.coding.common.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 运营接口鉴权拦截器（content / user 两个服务共用同一份）。
 *
 * <p>职责：只做**授权**。认证（token 是否有效、身份头是否可信）已由网关与各服务的
 * 身份拦截器完成，本拦截器只读 {@code AppThreadLocalUtil} 中的当前用户，
 * 因此**必须注册在身份拦截器之后**（见各服务 WebMvcConfig 里的注册顺序）。
 *
 * <p><b>⚠️ 运营路径下这个"当前用户的 id"是运营账号 ID（{@code ap_admin_account.id}）</b>，
 * 不是 C 端用户 ID —— 网关是用运营会话里的账号 ID 填的身份头。包装它的是 C 端那边的
 * {@code ApUser}，只是因为这个类型串起了所有服务；别看到 {@code getUserId} 就以为在跟 C 端账号打交道。
 *
 * <p>三条判定规则：
 * <ol>
 *   <li>未登录 → 401；</li>
 *   <li>有 {@link RequireAdminPermission} 注解 → 校验对应权限点，不足则 403；</li>
 *   <li>运营路径下**没写注解** → 同样 403。这是刻意的 fail-closed：漏标注解的后果
 *       是"这个接口暂时没人能用"（上线即暴露），而不是"这个接口谁都能调"（上线即事故）。</li>
 * </ol>
 *
 * <p>OPTIONS 预检请求直接放行：它不携带业务意图，且跨域场景下若被拦，
 * 前端连错误信息都拿不到（浏览器只报 CORS 失败），排查成本很高。
 *
 * <p><b>被拒也要留痕</b>：两种 403 都写一条审计（{@code module=ACCESS, result=失败}）。
 * 业务动作的审计回答"谁做了什么"，这一类回答"谁想做什么但没做成"——
 * 一个反复尝试下架却屡屡被拒的账号，是需要被看到的信息。记录本身不影响拒绝结果，
 * 写不进去也只告警（见 {@link AdminAuditSink#recordFailure}）。
 */
@Slf4j
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 「需要登录、但不要求任何运营权限点」的运营接口路径。
     *
     * <p>目前只有身份自述一类的接口：{@code /admin/me}（我是谁、我有哪些权限）与
     * {@code /admin/me/password}（改自己的口令）。它们必须免权限点，否则会自相矛盾 ——
     * <ul>
     *   <li>一个"已建号但还没开通任何角色"的账号，连"我没有权限"这件事都问不出来，
     *       前端只能收到一个语焉不详的 403；</li>
     *   <li>用初始口令登录的人如果连改口令都要先有权限，初始口令就永远换不掉。</li>
     * </ul>
     *
     * <p><b>免的是权限点，不是登录</b>：未登录依然 401。
     *
     * <p>用"精确匹配或前缀 + /"而不是裸前缀：裸前缀 {@code /api/v1/admin/me} 会把
     * {@code /api/v1/admin/meetings} 这类同前缀路径一并放行 —— 将来多一个运营接口
     * 就多一个免鉴权入口，而这种事没人会注意到。
     *
     * <p>声明为 {@code public static final} 是为了让回归测试能拿它做输入
     * （见 {@code AdminEndpointAnnotationTest}）。
     */
    public static final List<String> PERMISSION_EXEMPT_PATHS = List.of("/api/v1/admin/me");

    private final AdminRoleResolver roleResolver;

    private final AdminAuditSink auditSink;

    public AdminAuthInterceptor(AdminRoleResolver roleResolver, AdminAuditSink auditSink) {
        this.roleResolver = roleResolver;
        this.auditSink = auditSink;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // 静态资源/错误页等非 Controller 处理，不归本拦截器管
            return true;
        }

        RequireAdminPermission required = handlerMethod.getMethodAnnotation(RequireAdminPermission.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequireAdminPermission.class);
        }

        ApUser current = AppThreadLocalUtil.getUser();
        if (current == null) {
            // 未登录：网关已把无 token 的请求挡在门外，走到这里通常是内部直连。
            // 不留痕 —— 不知是谁的"未登录访问"没有追溯价值，只会把审计表灌满噪声。
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "需要登录");
        }

        AdminIdentity identity = roleResolver.resolve(current.getId());
        // 写入上下文：审计记录器要靠它拿到"谁、以什么身份"；免权限点路径的业务代码也靠它拿身份
        AdminContext.set(identity);

        if (isPermissionExempt(request.getRequestURI())) {
            return true;
        }

        if (required == null) {
            log.error("运营接口缺少 @RequireAdminPermission 注解，已按 fail-closed 拒绝, path={}",
                request.getRequestURI());
            auditDenied(request, "接口未配置权限点：" + request.getRequestURI());
            return reject(response, HttpServletResponse.SC_FORBIDDEN, 403, "接口未配置权限点");
        }

        AdminPermission permission = required.value();
        if (!identity.has(permission)) {
            log.warn("运营接口越权访问被拒, userId={}, 需要={}, 角色={}, path={}",
                current.getId(), permission, identity.roleCodesAsString(), request.getRequestURI());
            auditDenied(request, "权限不足，需要 " + permission.getCode());
            return reject(response, HttpServletResponse.SC_FORBIDDEN, 403, "无权限执行该操作");
        }
        return true;
    }

    /**
     * 是否属于"免权限点但需登录"的路径；判定口径见 {@link #PERMISSION_EXEMPT_PATHS}。
     *
     * <p>声明为 {@code public static} 同样是为了让回归测试能直接调用它做断言 ——
     * 这条规则的特殊之处在于它<b>放宽</b>了鉴权，所以"会不会误放行同前缀的路径"
     * 必须有一处能验，而不是只写在注释里。
     */
    public static boolean isPermissionExempt(String uri) {
        if (uri == null) {
            return false;
        }
        for (String exempt : PERMISSION_EXEMPT_PATHS) {
            if (uri.equals(exempt) || uri.startsWith(exempt + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 记一条"被拒绝"的审计；module 固定为 ACCESS，target 取请求路径，便于按接口回看谁被挡过 */
    private void auditDenied(HttpServletRequest request, String reason) {
        auditSink.recordFailure(
            AdminAuditSink.entry(ApAdminAuditLog.MODULE_ACCESS, ApAdminAuditLog.ACTION_ACCESS_DENIED,
                "ENDPOINT", truncate(request.getRequestURI(), 64), reason),
            reason);
    }

    /** target_id 列宽 64；超长会因 "Data too long" 让整条审计写不进去，宁可截断也要留住"谁被挡过" */
    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private boolean reject(HttpServletResponse response, int httpStatus, int bizCode, String message)
        throws Exception {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        JSON.writeValue(response.getWriter(), ResponseResult.errorResult(bizCode, message));
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        // 线程复用，必须清理，否则下一个请求会读到上一个请求的运营身份
        AdminContext.clear();
    }
}
