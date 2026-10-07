package com.zhuri.coding.common.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.method.HandlerMethod;

/**
 * 运营接口鉴权拦截器单测。
 *
 * <p>这是运营后台的**授权闸门**，因此测试要钉住三件事：
 * <ul>
 *   <li>fail-closed：漏标注解的运营接口不是"谁都能调"，而是"谁都调不了"；</li>
 *   <li>权限按点判，不是"是运营就放行"（AUDITOR 能处置举报但下架不了）；</li>
 *   <li>被拒也要留痕，且审计写的是"被拒"而不是"成功"。</li>
 * </ul>
 *
 * <p>用例留在 common 而不是某个服务里：拦截器是 content 与 user 共用的同一份代码，
 * 测试跟着实现走，才不会有"改了实现忘了改另一份测试"的情况。
 * 桩的是两个**接口**（{@link AdminRoleResolver} / {@link AdminAuditSink}）而不是具体实现，
 * 因为拦截器本来就只依赖接口 —— 这也顺带证明了抽象是够的。
 *
 * <p>这里刻意用裸 {@code mock()} 而不是 {@code @Mock}：同一个 mock 在不同分支上被调用的次数
 * 本来就不一样（放行路径不会读 URI、未登录路径不查角色），严格桩校验会把"这段代码没走到"
 * 误判成测试缺陷。
 */
@DisplayName("运营接口鉴权拦截器（AdminAuthInterceptor）")
class AdminAuthInterceptorTest {

    private static final Integer USER_ID = 8888;

    private final AdminRoleResolver roleResolver = mock(AdminRoleResolver.class);
    private final AdminAuditSink auditSink = mock(AdminAuditSink.class);
    private final AdminAuthInterceptor interceptor = new AdminAuthInterceptor(roleResolver, auditSink);

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
        AdminContext.clear();
    }

    // ==================== 测试用 Controller ====================

    @SuppressWarnings("unused")
    static class SampleController {
        @RequireAdminPermission(AdminPermission.REPORT_VIEW)
        public void viewReports() {
        }

        @RequireAdminPermission(AdminPermission.CONTENT_TAKE_DOWN)
        public void takeDown() {
        }

        /** 故意不标注解 —— 模拟"新加接口忘了加权限点" */
        public void forgotAnnotation() {
        }
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("未登录 → 401，且不查角色、不写审计（不知是谁的访问没有追溯价值）")
    void notLoggedInReturns401() throws Exception {
        HttpServletResponse response = mockResponse();

        boolean pass = interceptor.preHandle(request("POST", "/api/v1/admin/reports"),
            response, handler("viewReports"));

        assertFalse(pass, "未登录必须被拦下");
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, capturedStatus(response));
        verify(roleResolver, never()).resolve(any());
        verify(auditSink, never()).recordFailure(any(), anyString());
    }

    @Test
    @DisplayName("持有注解要求的权限 → 放行，并把运营身份写入上下文")
    void grantedWhenPermissionHeld() throws Exception {
        loginAs();
        when(roleResolver.resolve(USER_ID)).thenReturn(new AdminIdentity(USER_ID, List.of("AUDITOR")));

        boolean pass = interceptor.preHandle(request("GET", "/api/v1/admin/reports"),
            mock(HttpServletResponse.class), handler("viewReports"));

        assertTrue(pass);
        AdminIdentity identity = AdminContext.get();
        assertNotNull(identity, "审计记录器要靠上下文取操作人与角色快照");
        assertEquals(USER_ID, identity.getUserId());
    }

    @Test
    @DisplayName("已登录但权限不足（AUDITOR 尝试下架）→ 403，并留一条 ACCESS 失败审计")
    void deniedWhenPermissionMissing() throws Exception {
        loginAs();
        when(roleResolver.resolve(USER_ID)).thenReturn(new AdminIdentity(USER_ID, List.of("AUDITOR")));
        HttpServletResponse response = mockResponse();

        boolean pass = interceptor.preHandle(request("POST", "/api/v1/admin/take-down"),
            response, handler("takeDown"));

        assertFalse(pass);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, capturedStatus(response));

        ArgumentCaptor<ApAdminAuditLog> captor = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditSink).recordFailure(captor.capture(), anyString());
        ApAdminAuditLog entry = captor.getValue();
        assertEquals(ApAdminAuditLog.MODULE_ACCESS, entry.getModule());
        assertEquals(ApAdminAuditLog.ACTION_ACCESS_DENIED, entry.getAction());
        assertEquals("ENDPOINT", entry.getTargetType());
        assertTrue(entry.getReason().contains("CONTENT_TAKE_DOWN"), "审计要说清缺的是哪个权限点");
    }

    @Test
    @DisplayName("运营路径下漏标注解 → 403（fail-closed：宁可这个接口暂时不能用）")
    void missingAnnotationIsFailClosed() throws Exception {
        loginAs();
        when(roleResolver.resolve(USER_ID)).thenReturn(new AdminIdentity(USER_ID, List.of("SUPER_ADMIN")));
        HttpServletResponse response = mockResponse();

        boolean pass = interceptor.preHandle(request("GET", "/api/v1/admin/ops/config"),
            response, handler("forgotAnnotation"));

        assertFalse(pass, "超管也不能调用未标注权限点的运营接口");
        assertEquals(HttpServletResponse.SC_FORBIDDEN, capturedStatus(response));
        verify(auditSink).recordFailure(any(ApAdminAuditLog.class), anyString());
    }

    @Test
    @DisplayName("超管的下架权限照常放行 —— 拦住的是越权，不是特定角色")
    void superAdminPasses() throws Exception {
        loginAs();
        when(roleResolver.resolve(USER_ID)).thenReturn(new AdminIdentity(USER_ID, List.of("SUPER_ADMIN")));

        assertTrue(interceptor.preHandle(request("POST", "/api/v1/admin/take-down"),
            mock(HttpServletResponse.class), handler("takeDown")));
    }

    @Test
    @DisplayName("无任何角色 → 403（不能因为'是个登录用户'就放行）")
    void anonymousRoleDenied() throws Exception {
        loginAs();
        when(roleResolver.resolve(USER_ID)).thenReturn(AdminIdentity.anonymous(USER_ID));
        HttpServletResponse response = mockResponse();

        assertFalse(interceptor.preHandle(request("GET", "/api/v1/admin/reports"),
            response, handler("viewReports")));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, capturedStatus(response));
    }

    @Test
    @DisplayName("OPTIONS 预检请求放行（被拦的话前端只看到 CORS 失败，无从排查）")
    void optionsPasses() throws Exception {
        boolean pass = interceptor.preHandle(request("OPTIONS", "/api/v1/admin/reports"),
            mock(HttpServletResponse.class), handler("viewReports"));

        assertTrue(pass);
        verify(roleResolver, never()).resolve(any());
    }

    @Test
    @DisplayName("非 Controller 处理（静态资源等）直接放行，不归本拦截器管")
    void nonHandlerMethodPasses() throws Exception {
        assertTrue(interceptor.preHandle(request("GET", "/api/v1/admin/x"),
            mock(HttpServletResponse.class), new Object()));
    }

    @Test
    @DisplayName("afterCompletion 清空运营上下文 —— 线程复用下不清会把上一个请求的身份带给下一个")
    void afterCompletionClearsContext() throws Exception {
        loginAs();
        when(roleResolver.resolve(USER_ID)).thenReturn(new AdminIdentity(USER_ID, List.of("OPERATOR")));
        interceptor.preHandle(request("GET", "/api/v1/admin/reports"),
            mock(HttpServletResponse.class), handler("viewReports"));
        assertNotNull(AdminContext.get());

        interceptor.afterCompletion(request("GET", "/api/v1/admin/reports"),
            mock(HttpServletResponse.class), handler("viewReports"), null);

        assertNull(AdminContext.get(), "上下文必须清空，否则线程复用会把上一个请求的运营身份带到下一个");
    }

    // ==================== 工具 ====================

    private void loginAs() {
        ApUser user = new ApUser();
        user.setId(USER_ID);
        AppThreadLocalUtil.setUser(user);
    }

    private HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    /** 返回一个可写响应体的 mock：拦截器拒绝时会往 Writer 里写 JSON，writer 为 null 会直接 NPE */
    private HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }

    private int capturedStatus(HttpServletResponse response) {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatus(captor.capture());
        return captor.getValue();
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        SampleController controller = new SampleController();
        return new HandlerMethod(controller, SampleController.class.getMethod(methodName));
    }
}
