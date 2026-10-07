package com.zhuri.coding.content.controller.v1.pins;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.pins.ApPinsService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 运营后台 · 沸点管理（全量沸点的查看、审核、删除）。
 *
 * <p>注意与 {@code /api/v1/pins/manage/**}（作者管理自己的沸点）区分：这里是**运营视角**，
 * {@code findList} 不带作者过滤，能看见所有人的沸点；也正因如此它必须走运营鉴权。
 *
 * <p><b>权限来源已从代码白名单换成角色表</b>：本控制器原先逐个方法调用
 * {@code EditorConfig.isEditor(user.getId())}（白名单 {@code {4}} 硬编码在代码里）。
 * 现在改由 {@link RequireAdminPermission} 声明权限点 {@code PINS_MANAGE}，
 * 由 {@code AdminAuthInterceptor} 统一校验，运营账号与角色的绑定落在 {@code ap_admin_account_role} 表。
 *
 * <p>⚠️ 注解只是**声明**，真正生效的前提是本控制器的路径被注册进拦截器；
 * 见 {@code ContentWebMvcConfig#addInterceptors} 中的 {@code /api/v1/pins/admin/**}。
 */
@RestController
@RequestMapping("/api/v1/pins/admin")
@Slf4j
public class PinsController {

    @Autowired
    private ApPinsService apPinsService;

    @GetMapping("/list")
    @RequireAdminPermission(AdminPermission.PINS_MANAGE)
    public ResponseResult findList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Byte status) {
        return apPinsService.findList(page, size, status);
    }

    @DeleteMapping("/{id}")
    @RequireAdminPermission(AdminPermission.PINS_MANAGE)
    public ResponseResult deleteById(@PathVariable Long id) {
        return apPinsService.deleteById(id);
    }

    @PutMapping("/status")
    @RequireAdminPermission(AdminPermission.PINS_MANAGE)
    public ResponseResult updateStatus(@RequestBody Map<String, Object> params) {
        Long id = Long.parseLong(params.get("id").toString());
        Byte status = Byte.parseByte(params.get("status").toString());
        String reason = params.get("reason") != null ? params.get("reason").toString() : null;
        return apPinsService.updateStatus(id, status, reason);
    }
}
