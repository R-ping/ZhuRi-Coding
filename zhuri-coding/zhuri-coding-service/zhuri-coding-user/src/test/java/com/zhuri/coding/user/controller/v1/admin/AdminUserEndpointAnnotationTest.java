package com.zhuri.coding.user.controller.v1.admin;

import com.zhuri.coding.common.admin.AdminAuthInterceptor;
import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.AdminRole;
import com.zhuri.coding.user.config.UserWebMvcConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * user 服务运营接口的「静默失效」回归（content 侧同名测试的镜像）。
 *
 * <p>运营鉴权靠两件事合作，两件都是漏了不报错、只静默放开或静默拒绝的：
 * <ol>
 *   <li>handler 的路径被 {@link UserWebMvcConfig#ADMIN_PATH_PATTERNS} 覆盖 ——
 *       漏挂路径 → 拦截器根本不跑 → 接口<b>裸奔</b>（谁都能封禁别人、谁能新建超管）；</li>
 *   <li>handler 挂着 {@link RequireAdminPermission} ——
 *       漏挂注解 → 拦截器 fail-closed 拒绝 → 接口<b>谁都用不了</b>。</li>
 * </ol>
 * 前者是安全事故，后者是上线即故障。纯反射、不启动 Spring，跑得很快。
 *
 * <p>{@code ADMIN_PATH_PATTERNS} 与 {@code PERMISSION_EXEMPT_PATHS} 刻意是 {@code public static final}，
 * 就是为了让这个测试能读到 —— 前者是"哪些接口被鉴权"的唯一边界，后者是<b>唯一的放宽口</b>，
 * 两者都必须有回归盯着。
 *
 * <p><b>⚠️ 这个测试的边界：控制器列表是手写的。</b>
 * 新增运营控制器却忘了加进 {@link #CONTROLLERS}，本测试不会发现 —— 它只校验收录进来的控制器。
 * 之所以不用包扫描自动发现，是因为"哪些控制器属于运营"无法从包名推断
 * （大量 C 端 {@code @RestController} 与它们同包）。
 * <b>新增运营控制器时，加进这个列表和加 {@code @RequireAdminPermission} 一样是必须动作。</b>
 */
class AdminUserEndpointAnnotationTest {

    /** 需要校验收录的控制器。新增一个就加一个（见类注释里的边界说明）。 */
    private static final List<Class<?>> CONTROLLERS = List.of(
        AdminUserDispositionController.class,
        AdminAccountController.class
    );

    @Test
    @DisplayName("路径被运营鉴权覆盖的 handler 都必须声明权限点（免权限点路径除外）")
    void everyCoveredNonExemptHandlerDeclaresPermission() {
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : handlerMethods(controller)) {
                String path = handlerPath(controller, method);
                if (!isCovered(path) || AdminAuthInterceptor.isPermissionExempt(path)) {
                    continue;
                }
                assertTrue(declaresPermission(controller, method),
                    controller.getSimpleName() + "#" + method.getName() + "（" + path + "）"
                        + " 落在运营鉴权路径下、且不在免权限点清单里，却未标注 @RequireAdminPermission："
                        + "该接口会被拦截器 fail-closed 拒绝，上线即不可用。");
            }
        }
    }

    @Test
    @DisplayName("声明了权限点的 handler 必须真在运营鉴权路径下（否则注解形同虚设，接口裸奔）")
    void everyAnnotatedHandlerIsActuallyCovered() {
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : handlerMethods(controller)) {
                if (!declaresPermission(controller, method)) {
                    continue;
                }
                String path = handlerPath(controller, method);
                assertTrue(isCovered(path),
                    controller.getSimpleName() + "#" + method.getName() + "（" + path + "）"
                        + " 标了 @RequireAdminPermission，但它的路径不在 UserWebMvcConfig.ADMIN_PATH_PATTERNS "
                        + "里 —— 拦截器不会执行，注解不会生效，这个接口实际上是裸奔的。");
            }
        }
    }

    @Test
    @DisplayName("每个控制器至少有端点被运营鉴权覆盖（防止误把整个控制器加进来）")
    void everyListedControllerHasAtLeastOneCoveredHandler() {
        for (Class<?> controller : CONTROLLERS) {
            boolean anyCovered = handlerMethods(controller).stream()
                .anyMatch(m -> isCovered(handlerPath(controller, m)));
            assertTrue(anyCovered,
                controller.getSimpleName() + " 没有任何端点落在运营鉴权路径下，"
                    + "但它被列进了本测试的控制器清单 —— 要么清单多写了，要么 ADMIN_PATH_PATTERNS 少写了。");
        }
    }

    @Test
    @DisplayName("免权限点清单里的每一条都必须对应真实端点（写错了没人会发现，只会静静失效）")
    void permissionExemptPathsMustMatchRealEndpoints() {
        Set<String> realPaths = new HashSet<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : handlerMethods(controller)) {
                realPaths.add(handlerPath(controller, method));
            }
        }
        assertFalse(AdminAuthInterceptor.PERMISSION_EXEMPT_PATHS.isEmpty(),
            "免权限点清单为空 —— 若这是有意的，请连同这条断言一起删掉，而不是让它空跑");
        for (String exempt : AdminAuthInterceptor.PERMISSION_EXEMPT_PATHS) {
            assertTrue(realPaths.contains(exempt),
                "免权限点清单里的 " + exempt + " 不对应任何真实端点（现有端点："
                    + new java.util.TreeSet<>(realPaths) + "）。"
                    + "写错的后果是：想放行的接口照旧 403，而你以为已经放行了。");
        }
    }

    @Test
    @DisplayName("免权限点按『精确匹配或前缀+斜杠』生效，不能把同前缀的兄弟路径一起放行")
    void permissionExemptDoesNotSwallowSiblingPaths() {
        // 这条规则放宽了鉴权，所以"会不会误放行"必须真验一遍，不能只写在注释里。
        // /api/v1/admin/meetings 就是那个经典例子：裸前缀 startsWith("/api/v1/admin/me") 会放它进来。
        assertTrue(AdminAuthInterceptor.isPermissionExempt("/api/v1/admin/me"));
        assertTrue(AdminAuthInterceptor.isPermissionExempt("/api/v1/admin/me/password"));
        assertFalse(AdminAuthInterceptor.isPermissionExempt("/api/v1/admin/meetings"));
        assertFalse(AdminAuthInterceptor.isPermissionExempt("/api/v1/admin/meeting"));
        assertFalse(AdminAuthInterceptor.isPermissionExempt("/api/v1/admin/mex"));
        assertFalse(AdminAuthInterceptor.isPermissionExempt("/api/v1/admin/accounts"));
        assertFalse(AdminAuthInterceptor.isPermissionExempt(null));
    }

    @Test
    @DisplayName("免权限点清单里不能出现目录式或通配条目（那等于整段放行）")
    void permissionExemptPathsMustBeConcrete() {
        for (String exempt : AdminAuthInterceptor.PERMISSION_EXEMPT_PATHS) {
            assertFalse(exempt.endsWith("/"),
                exempt + " 以斜杠结尾：它同时是匹配前缀，会把该目录下所有接口一并放行。");
            assertFalse(exempt.contains("*"),
                exempt + " 含通配符：免权限点清单只接受具体路径。");
            assertFalse(exempt.contains("{"),
                exempt + " 含占位段：无法判断它到底匹配哪些接口。");
        }
    }

    @Test
    @DisplayName("权限点声明了就必须真挂到角色上（否则该权限谁都没有）")
    void declaredPermissionsAreGrantedToSomeRole() {
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : handlerMethods(controller)) {
                RequireAdminPermission annotation = AnnotatedElementUtils
                    .findMergedAnnotation(method, RequireAdminPermission.class);
                if (annotation == null) {
                    annotation = AnnotatedElementUtils.findMergedAnnotation(controller, RequireAdminPermission.class);
                }
                if (annotation == null) {
                    continue;
                }
                final AdminPermission permission = annotation.value();
                boolean granted = Arrays.stream(AdminRole.values())
                    .anyMatch(role -> role.getPermissions().contains(permission));
                assertTrue(granted,
                    controller.getSimpleName() + "#" + method.getName() + " 声明了权限点 " + permission
                        + "，但它没有挂在任何角色上 —— 这个接口对所有人都是 403。"
                        + "新增权限点时必须同时加进 AdminRole。");
            }
        }
    }

    @Test
    @DisplayName("封禁与解封必须是同一个权限点：能封不能解会留下解不开的账号")
    void banAndUnbanSharePermission() {
        Set<AdminPermission> permissions = new HashSet<>();
        int matched = 0;
        for (Method method : AdminUserDispositionController.class.getDeclaredMethods()) {
            if (!"ban".equals(method.getName()) && !"unban".equals(method.getName())) {
                continue;
            }
            RequireAdminPermission annotation = method.getAnnotation(RequireAdminPermission.class);
            assertNotNull(annotation, "handler " + method.getName() + " 缺少权限注解");
            permissions.add(annotation.value());
            matched++;
        }
        assertEquals(2, matched, "ban / unban 两个 handler 都要有权限注解");
        assertEquals(1, permissions.size(),
            "封禁与解封必须是同一个权限点，实际 " + permissions + " —— 权限不对称会造出「封得住解不开」的账号");
    }

    @Test
    @DisplayName("警告与封禁是不同权限点：警告可逆、运营能做；封禁影响面大，只给超管")
    void warnIsWeakerThanBan() {
        AdminPermission warn = permissionOf(AdminUserDispositionController.class, "warn");
        AdminPermission ban = permissionOf(AdminUserDispositionController.class, "ban");
        assertNotEquals(warn, ban, "警告与封禁不该共用一个权限点 —— 否则「先警告再升级」这条路径就只需要一个人");
        assertTrue(Arrays.stream(AdminRole.values())
                .anyMatch(role -> role.getPermissions().contains(warn)
                    && !role.getPermissions().contains(ban)),
            "至少要有一种角色能警告但不能封禁，否则警告作为「较轻处置」没有实际意义");
    }

    @Test
    @DisplayName("新建账号与授予角色是两个权限点：能改角色的人不该能凭空造出一个超管账号")
    void accountManageIsNotRoleGrant() {
        AdminPermission create = permissionOf(AdminAccountController.class, "createAccount");
        AdminPermission grant = permissionOf(AdminAccountController.class, "grantRole");
        assertNotEquals(create, grant,
            "新建账号（ACCOUNT_MANAGE）与授予角色（ROLE_GRANT）必须是不同权限点 —— "
                + "合成一个等于任何能调角色的人都能绕开「角色变更需要超管」造号。");
        // 两者都只应落在超管上：它们是"扩充进入这套系统的人"和"改变已有人的权力"，都不是日常运营动作
        assertTrue(Arrays.stream(AdminRole.values())
                .filter(role -> role.getPermissions().contains(create))
                .allMatch(role -> role.getPermissions().contains(grant)),
            "能新建账号却不能再授角色，或者反过来，都会造出一个职责残缺的管理入口");
    }

    private static AdminPermission permissionOf(Class<?> controller, String methodName) {
        for (Method method : controller.getDeclaredMethods()) {
            if (methodName.equals(method.getName())) {
                RequireAdminPermission annotation = method.getAnnotation(RequireAdminPermission.class);
                assertNotNull(annotation, "handler " + methodName + " 缺少权限注解");
                return annotation.value();
            }
        }
        throw new AssertionError("在 " + controller.getSimpleName() + " 里找不到 handler " + methodName);
    }

    private static boolean declaresPermission(Class<?> controller, Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequireAdminPermission.class)
            || AnnotatedElementUtils.hasAnnotation(controller, RequireAdminPermission.class);
    }

    /** 控制器里声明了 @RequestMapping（含 @GetMapping 这类派生注解）的方法 */
    private static List<Method> handlerMethods(Class<?> controller) {
        List<Method> handlers = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                handlers.add(method);
            }
        }
        return handlers;
    }

    private static String basePathOf(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        assertNotNull(mapping, controller.getSimpleName() + " 缺少 @RequestMapping（无法判断它属于哪条路径）");
        String base = firstPath(mapping);
        assertTrue(!base.isEmpty(), controller.getSimpleName() + " 的 @RequestMapping 没写路径");
        assertFalse(base.contains("{"),
            controller.getSimpleName() + " 的基础路径含占位段，无法参与前缀校验：" + base);
        return base;
    }

    /** handler 的完整路径 = 控制器基础路径 + 方法级路径 */
    private static String handlerPath(Class<?> controller, Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        String methodPath = mapping == null ? "" : firstPath(mapping);
        String base = basePathOf(controller);
        if (methodPath.isEmpty()) {
            return base;
        }
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String right = methodPath.startsWith("/") ? methodPath : "/" + methodPath;
        return left + right;
    }

    private static String firstPath(RequestMapping mapping) {
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return paths.length > 0 ? paths[0] : "";
    }

    /**
     * Spring 的 ant 风格匹配在这里简化成前缀比较：等于该路径，或以「路径 + /」开头。
     * 与网关 {@code AdminSessionKeys#isAdminPath} 同一口径（理由见那边注释）。
     */
    private static boolean isCovered(String path) {
        for (String pattern : UserWebMvcConfig.ADMIN_PATH_PATTERNS) {
            String prefix = pattern.endsWith("/**") ? pattern.substring(0, pattern.length() - 3) : pattern;
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
