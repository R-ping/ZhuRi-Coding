package com.zhuri.coding.content.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.admin.AdminPopupService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.AdminActionDto;
import com.zhuri.coding.model.admin.dtos.AdminPopupSaveDto;
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
 * 运营后台 · 弹窗公告管理。
 *
 * <p>与 {@code AdminBannerController} 同一套骨架（建档型 CRUD、默认停用、
 * 状态闸口、理由必填），差异在服务层 —— 见 {@code AdminPopupService}：
 * 时间窗必填（投放语义）、编辑结束时间同步刷关闭记录 TTL、删除时清关闭记录。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/popups")
public class AdminPopupController {

    private static final int REASON_MAX_LEN = 500;

    @Autowired
    private AdminPopupService adminPopupService;

    /**
     * 弹窗列表（含停用）。
     * GET /api/v1/admin/popups?keyword=&status=&page=&size=
     */
    @GetMapping
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult page(@RequestParam(value = "keyword", required = false) String keyword,
                               @RequestParam(value = "status", required = false) Integer status,
                               @RequestParam(value = "page", defaultValue = "1") Integer page,
                               @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return adminPopupService.page(keyword, status, page, size);
    }

    /**
     * 弹窗详情。
     * GET /api/v1/admin/popups/{id}
     */
    @GetMapping("/{id}")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult detail(@PathVariable("id") Long id) {
        return adminPopupService.detail(id);
    }

    /**
     * 新建（一律落停用态）。
     * POST /api/v1/admin/popups
     */
    @PostMapping
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult create(@RequestBody AdminPopupSaveDto dto) {
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "参数不完整");
        }
        ResponseResult invalid = validateReason(dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminPopupService.create(dto);
    }

    /**
     * 编辑（改结束时间会同步刷新用户关闭记录的 TTL）。
     * PUT /api/v1/admin/popups/{id}
     */
    @PutMapping("/{id}")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult update(@PathVariable("id") Long id, @RequestBody AdminPopupSaveDto dto) {
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "参数不完整");
        }
        ResponseResult invalid = validateReason(dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminPopupService.update(id, dto);
    }

    /**
     * 启用（停用 → 启用；C 端可见性还受时间窗影响）。
     * POST /api/v1/admin/popups/{id}/enable
     */
    @PostMapping("/{id}/enable")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult enable(@PathVariable("id") Long id, @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminPopupService.enable(id, dto.getReason().trim());
    }

    /**
     * 停用（启用 → 停用，C 端立即不可见）。
     * POST /api/v1/admin/popups/{id}/disable
     */
    @PostMapping("/{id}/disable")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult disable(@PathVariable("id") Long id, @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminPopupService.disable(id, dto.getReason().trim());
    }

    /**
     * 删除（仅限停用态，同时清用户关闭记录）。
     * DELETE /api/v1/admin/popups/{id}?reason=xxx
     */
    @DeleteMapping("/{id}")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult delete(@PathVariable("id") Long id,
                                 @RequestParam("reason") String reason) {
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        return adminPopupService.delete(id, reason.trim());
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
