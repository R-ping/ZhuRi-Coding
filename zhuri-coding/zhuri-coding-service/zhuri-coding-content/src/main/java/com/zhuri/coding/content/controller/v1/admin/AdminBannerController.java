package com.zhuri.coding.content.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.admin.AdminBannerService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.AdminActionDto;
import com.zhuri.coding.model.admin.dtos.AdminBannerSaveDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台 · Banner 管理（首页轮播）。
 *
 * <p>设计取舍见 {@code AdminBannerService}：建档型 CRUD（与活动 CMS 同骨架），
 * 新建一律落停用态；启用/停用/删除是动作语义走 POST/DELETE（重复调用被状态闸口拒绝，
 * 不静默成功）；删除的理由走查询参数（有中间件会丢 DELETE 的 body）。
 * 校验分工：这里只管"请求合不合规矩"，"数据能不能写进库"由服务层决定。
 *
 * <p>权限归 {@code OPS_CONFIG}：该权限点的定义本来就写着"配置运营位（话题、活动、Banner）"，
 * Banner 是运营位四件套的最后一块，不需要也不应该另立权限点。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/banners")
public class AdminBannerController {

    private static final int REASON_MAX_LEN = 500;

    @Autowired
    private AdminBannerService adminBannerService;

    /**
     * Banner 列表（含停用）。
     * GET /api/v1/admin/banners?keyword=&status=&page=&size=
     */
    @GetMapping
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult page(@RequestParam(value = "keyword", required = false) String keyword,
                               @RequestParam(value = "status", required = false) Integer status,
                               @RequestParam(value = "page", defaultValue = "1") Integer page,
                               @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return adminBannerService.page(keyword, status, page, size);
    }

    /**
     * Banner 详情。
     * GET /api/v1/admin/banners/{id}
     */
    @GetMapping("/{id}")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult detail(@PathVariable("id") Long id) {
        return adminBannerService.detail(id);
    }

    /**
     * 新建（一律落停用态，启用了才会出现在 C 端）。
     * POST /api/v1/admin/banners
     */
    @PostMapping
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult create(@RequestBody AdminBannerSaveDto dto) {
        ResponseResult invalid = validateSave(dto);
        if (invalid != null) {
            return invalid;
        }
        return adminBannerService.create(dto);
    }

    /**
     * 编辑（清空时间窗 = 恢复"不限"）。
     * PUT /api/v1/admin/banners/{id}
     */
    @PutMapping("/{id}")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult update(@PathVariable("id") Long id, @RequestBody AdminBannerSaveDto dto) {
        ResponseResult invalid = validateSave(dto);
        if (invalid != null) {
            return invalid;
        }
        return adminBannerService.update(id, dto);
    }

    /**
     * 启用（停用 → 启用，C 端可见性还受时间窗影响）。
     * POST /api/v1/admin/banners/{id}/enable
     */
    @PostMapping("/{id}/enable")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult enable(@PathVariable("id") Long id, @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminBannerService.enable(id, dto.getReason().trim());
    }

    /**
     * 停用（启用 → 停用，C 端立即不可见）。
     * POST /api/v1/admin/banners/{id}/disable
     */
    @PostMapping("/{id}/disable")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult disable(@PathVariable("id") Long id, @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminBannerService.disable(id, dto.getReason().trim());
    }

    /**
     * 删除（仅限停用态）。理由走查询参数：有中间件会丢 DELETE 的请求体。
     * DELETE /api/v1/admin/banners/{id}?reason=xxx
     */
    @DeleteMapping("/{id}")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult delete(@PathVariable("id") Long id,
                                 @RequestParam("reason") String reason) {
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        return adminBannerService.delete(id, reason.trim());
    }

    private ResponseResult validateSave(AdminBannerSaveDto dto) {
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "参数不完整");
        }
        return validateReason(dto.getReason());
    }

    private ResponseResult validateReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写操作理由");
        }
        if (value.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "操作理由不能超过" + REASON_MAX_LEN + "字");
        }
        return null;
    }
}
