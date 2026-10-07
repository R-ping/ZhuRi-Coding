package com.zhuri.coding.content.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.admin.AdminAuditReviewService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.AdminActionDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台 · 审核复核队列（违规任务的人工复核）。
 *
 * <p>三个端点与 AIGC 复核队列（{@code /api/v1/admin/aigc-reviews}）同构：
 * 队列 + 放行 + 维持。两条业务线的差异在服务层（见 {@code AdminAuditReviewService}），
 * 权限同属 {@code AUDIT_REVIEW} —— "AI 判定的人工兜底"是一个动作域的两条业务线。
 *
 * <p>校验分工：这里只管"请求合不合规矩"，"数据能不能动"由服务层决定
 * （理由校验在服务层还有一道，这里是防手误的第一道）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/audit-reviews")
public class AdminAuditReviewController {

    private static final int REASON_MAX_LEN = 500;

    @Autowired
    private AdminAuditReviewService adminAuditReviewService;

    /**
     * 复核队列：违规且未复核的任务，先违规先复核。
     * GET /api/v1/admin/audit-reviews?bizType=&page=&size=
     */
    @GetMapping
    @RequireAdminPermission(AdminPermission.AUDIT_REVIEW)
    public ResponseResult page(@RequestParam(value = "bizType", required = false) String bizType,
                               @RequestParam(value = "page", defaultValue = "1") Integer page,
                               @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return adminAuditReviewService.page(bizType, page, size);
    }

    /**
     * 复核放行：恢复内容可见（软删翻回 / 沸点状态翻回）+ 行为记录 + 补发恢复通知。
     * POST /api/v1/admin/audit-reviews/{id}/restore
     */
    @PostMapping("/{id}/restore")
    @RequireAdminPermission(AdminPermission.AUDIT_REVIEW)
    public ResponseResult restore(@PathVariable("id") Long id, @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminAuditReviewService.restore(id, dto.getReason().trim());
    }

    /**
     * 维持违规：内容保持不可见，任务退出队列。
     * POST /api/v1/admin/audit-reviews/{id}/uphold
     */
    @PostMapping("/{id}/uphold")
    @RequireAdminPermission(AdminPermission.AUDIT_REVIEW)
    public ResponseResult uphold(@PathVariable("id") Long id, @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminAuditReviewService.uphold(id, dto.getReason().trim());
    }

    private ResponseResult validateReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty()) {
            return ResponseResult.errorResult(
                com.zhuri.coding.model.common.enums.AppHttpCodeEnum.PARAM_INVALID, "请填写操作理由");
        }
        if (value.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(
                com.zhuri.coding.model.common.enums.AppHttpCodeEnum.PARAM_INVALID,
                "操作理由不能超过" + REASON_MAX_LEN + "字");
        }
        return null;
    }
}
