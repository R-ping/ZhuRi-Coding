package com.zhuri.coding.content.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.admin.AdminContentFoldService;
import com.zhuri.coding.model.admin.AdminContentType;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.CommentFoldDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
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
 * 运营后台 · 内容折叠（文章评论 / 沸点评论）。
 *
 * <p>在此之前折叠只有一个方向：AI 判定后置 {@code is_hidden=1}，没有任何人工入口 ——
 * 既不能人工折叠（AI 放过的引战评论只能靠举报走"下架"，而评论没有举报表），
 * 也不能人工恢复（误伤只能等评论者自己申诉）。这个控制器把两个方向都补齐。
 *
 * <p><b>为什么查看与操作共用同一个权限点</b>：{@code CONTENT_FOLD} 是一条完整的能力 ——
 * "看折叠队列"和"决定折不折"在这里是同一个动作的两半。能看不能改的人拿这个页面没有意义，
 * 而能改却看不到队列的人反而更危险（只能凭 id 盲操作）。举报那边拆开 {@code REPORT_VIEW}
 * 是因为举报队列同时服务于"只看不判"的实习岗，折叠复核没有这个角色划分。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/comments")
public class AdminContentFoldController {

    /** 理由上限，与 {@code ap_admin_audit_log.reason} 列等长 */
    private static final int REASON_MAX_LEN = 500;

    @Autowired
    private AdminContentFoldService adminContentFoldService;

    /**
     * 折叠内容列表。
     * GET /api/v1/admin/comments?targetType=COMMENT&hidden=1&page=1&size=20
     *
     * @param targetType 内容类型：COMMENT 文章评论 / PINS_COMMENT 沸点评论（必填）
     * @param hidden     0 正常 / 1 已折叠；不传表示全部
     */
    @GetMapping
    @RequireAdminPermission(AdminPermission.CONTENT_FOLD)
    public ResponseResult list(@RequestParam("targetType") String targetType,
                               @RequestParam(value = "hidden", required = false) Integer hidden,
                               @RequestParam(value = "page", defaultValue = "1") Integer page,
                               @RequestParam(value = "size", defaultValue = "20") Integer size) {
        AdminContentType type = AdminContentType.parse(targetType);
        if (type == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不支持的内容类型");
        }
        if (hidden != null && hidden != 0 && hidden != 1) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "hidden 只能是 0 或 1");
        }
        return adminContentFoldService.page(type, hidden, page, size);
    }

    /**
     * 折叠一条评论。
     * POST /api/v1/admin/comments/{id}/fold
     */
    @PostMapping("/{id}/fold")
    @RequireAdminPermission(AdminPermission.CONTENT_FOLD)
    public ResponseResult fold(@PathVariable("id") Long id, @RequestBody CommentFoldDto dto) {
        ResponseResult invalid = validate(id, dto);
        if (invalid != null) {
            return invalid;
        }
        return adminContentFoldService.fold(AdminContentType.parse(dto.getTargetType()), id, dto.getReason().trim());
    }

    /**
     * 解除折叠（运营复核认为 AI 误伤时使用）。
     * POST /api/v1/admin/comments/{id}/unfold
     */
    @PostMapping("/{id}/unfold")
    @RequireAdminPermission(AdminPermission.CONTENT_FOLD)
    public ResponseResult unfold(@PathVariable("id") Long id, @RequestBody CommentFoldDto dto) {
        ResponseResult invalid = validate(id, dto);
        if (invalid != null) {
            return invalid;
        }
        return adminContentFoldService.unfold(AdminContentType.parse(dto.getTargetType()), id, dto.getReason().trim());
    }

    /**
     * 两个写接口共用的入参校验，返回 null 表示通过。
     *
     * <p>理由在这里必须非空：折叠是"温和"处置，但被折叠的人只会觉得评论被吞了。
     * 理由不是留痕字段，是唯一能让作者理解发生了什么的东西 —— 空话填不进去，人就没法敷衍。
     */
    private ResponseResult validate(Long id, CommentFoldDto dto) {
        if (id == null || dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不完整");
        }
        if (!CommentFoldDto.ALLOWED_TARGETS.contains(
            dto.getTargetType() == null ? "" : dto.getTargetType().trim().toUpperCase())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不支持的内容类型");
        }
        String reason = dto.getReason() == null ? "" : dto.getReason().trim();
        if (reason.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写处置理由");
        }
        if (reason.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "处置理由不能超过" + REASON_MAX_LEN + "字");
        }
        return null;
    }
}
