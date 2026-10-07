package com.zhuri.coding.content.auth;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.config.ContentWebMvcConfig;
import com.zhuri.coding.content.controller.v1.admin.AdminActivityController;
import com.zhuri.coding.content.controller.v1.admin.AdminAigcReviewController;
import com.zhuri.coding.content.controller.v1.admin.AdminAuditReviewController;
import com.zhuri.coding.content.controller.v1.admin.AdminBannerController;
import com.zhuri.coding.content.controller.v1.admin.AdminContentFoldController;
import com.zhuri.coding.content.controller.v1.admin.AdminOpsConfigController;
import com.zhuri.coding.content.controller.v1.admin.AdminPopupController;
import com.zhuri.coding.content.controller.v1.admin.AdminReportController;
import com.zhuri.coding.content.controller.v1.audit.ContentAppealController;
import com.zhuri.coding.content.controller.v1.course.BookletReviewController;
import com.zhuri.coding.content.controller.v1.pins.PinsController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 运营接口的「静默失效」回归。鉴权靠两件事合作，而这两件事漏了都不会报错：
 * <ol>
 *   <li>handler 的路径被 {@link ContentWebMvcConfig#ADMIN_PATH_PATTERNS} 覆盖 ——
 *       漏挂路径 → 拦截器根本不跑 → 接口**裸奔**（谁都能调）；</li>
 *   <li>handler 挂着 {@link RequireAdminPermission} ——
 *       漏挂注解 → 拦截器 fail-closed 拒绝 → 接口**谁都用不了**。</li>
 * </ol>
 * 前者是安全事故，后者是上线即故障，两种都该在上线之前被拦下来。
 * 这个测试不启动 Spring 上下文，纯反射，跑得很快，可以进 CI。
 *
 * <p><b>断言按「handler 的完整路径」判定，而不是按控制器</b>：本服务里有
 * {@code ContentAppealController} 这种<b>混着两类受众</b>的控制器 ——
 * {@code /submit} 与 {@code /status} 给 C 端用户用，{@code /review} 给运营用。
 * 按控制器判会把两个 C 端接口一起要求运营身份，得出的结论是错的。
 *
 * <p><b>⚠️ 这个测试的边界：控制器列表是手写的。</b>
 * 新增一个运营控制器却忘了加进 {@link #CONTROLLERS}，本测试<b>不会</b>发现 ——
 * 它只校验收录进来的控制器。之所以不用包扫描自动发现，是因为"哪些控制器属于运营"
 * 无法从包名推断（大量 C 端 {@code @RestController} 与它们同包），扫描反而要先知道答案。
 * 换句话说：<b>新增运营控制器时，加进这个列表和加 {@code @RequireAdminPermission} 一样是必须动作。</b>
 */
class AdminEndpointAnnotationTest {

    /**
     * 需要校验收录的控制器 —— 包含"整体走运营鉴权"和"部分端点走运营鉴权"两类。
     *
     * <p>新增一个就加一个（见类注释里的边界说明）。
     */
    private static final List<Class<?>> CONTROLLERS = List.of(
        AdminReportController.class,
        AdminContentFoldController.class,
        AdminOpsConfigController.class,
        AdminActivityController.class,
        AdminAigcReviewController.class,
        AdminAuditReviewController.class,
        AdminBannerController.class,
        AdminPopupController.class,
        BookletReviewController.class,
        PinsController.class,
        ContentAppealController.class
    );

    @Test
    @DisplayName("路径被运营鉴权覆盖的 handler 都必须声明权限点（漏挂 = fail-closed 后接口谁都调不了）")
    void everyCoveredHandlerDeclaresPermission() {
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : handlerMethods(controller)) {
                String path = handlerPath(controller, method);
                if (!isCovered(path)) {
                    continue;
                }
                assertTrue(declaresPermission(controller, method),
                    controller.getSimpleName() + "#" + method.getName() + "（" + path + "）"
                        + " 落在运营鉴权路径下却未标注 @RequireAdminPermission："
                        + "该接口会被拦截器 fail-closed 拒绝，上线即不可用。请显式声明所需权限点。");
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
                        + " 标了 @RequireAdminPermission，但它的路径不在 "
                        + "ContentWebMvcConfig.ADMIN_PATH_PATTERNS 里 —— 拦截器不会执行，"
                        + "注解不会生效，这个接口实际上是裸奔的。");
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
                    + "但它被列进了本测试的控制器清单 —— 要么清单多写了，要么 "
                    + "ADMIN_PATH_PATTERNS 少写了。");
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
                final var permission = annotation.value();
                boolean granted = java.util.Arrays.stream(com.zhuri.coding.model.admin.AdminRole.values())
                    .anyMatch(role -> role.getPermissions().contains(permission));
                assertTrue(granted,
                    controller.getSimpleName() + "#" + method.getName() + " 声明了权限点 " + permission
                        + "，但它没有挂在任何角色上 —— 这个接口对所有人都是 403。"
                        + "新增权限点时必须同时加进 AdminRole。");
            }
        }
    }

    private static boolean declaresPermission(Class<?> controller, Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequireAdminPermission.class)
            || AnnotatedElementUtils.hasAnnotation(controller, RequireAdminPermission.class);
    }

    /** 控制器里声明了 @RequestMapping（含 @GetMapping 这类派生注解）的方法 */
    private static List<Method> handlerMethods(Class<?> controller) {
        return java.util.Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
            .toList();
    }

    private static String basePathOf(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        assertNotNull(mapping, controller.getSimpleName() + " 缺少 @RequestMapping（无法判断它属于哪条路径）");
        String base = firstPath(mapping);
        assertTrue(!base.isEmpty(), controller.getSimpleName() + " 的 @RequestMapping 没写路径");
        // 含占位段（如 /api/v1/{id}/admin）的基础路径无法参与前缀比较，这种控制器本测试判断不了
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
     * Spring 的 ant 风格匹配在这里简化成前缀比较。
     *
     * <p>与网关 {@code AdminSessionKeys#isAdminPath} 同一口径：等于该路径，或以「路径 + /」开头。
     * 裸前缀会把 {@code /api/v1/audit/appeal/reviewXxx} 这类兄弟路径也算成被覆盖，
     * 于是测试会替一个其实没鉴权的接口盖章通过。
     */
    private static boolean isCovered(String path) {
        for (String pattern : ContentWebMvcConfig.ADMIN_PATH_PATTERNS) {
            String prefix = pattern.endsWith("/**") ? pattern.substring(0, pattern.length() - 3) : pattern;
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
