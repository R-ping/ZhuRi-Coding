package com.zhuri.coding.content.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.admin.AdminOpsConfigService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.AdminOpsOrderDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台 · 运营位配置（人气圈子 / 推荐话题）。
 *
 * <p><b>为什么这里用 PUT 而其它运营接口都用 POST</b>：这两组接口表达的是
 * "把这份清单变成这样" —— 提交同一份清单两次，结果与提交一次相同（幂等），
 * 这是 PUT 的语义。而折叠、下架、封禁那些接口表达的是"再做一次这个动作"，
 * 两次调用是两次事件（第二次会被闸口拒掉），属于 POST。
 *
 * <p><b>关于"清空"</b>：{@code items} 传空数组是合法操作（临时下掉整个运营位），
 * 与 {@code items} 缺失（参数错误）区分开。空数组不清空为"删掉配置表" ——
 * 人气圈子表被清空后 C 端返回空列表，这是预期结果。
 *
 * <p><b>校验分工</b>：控制器只管"这个请求合不合规矩"（字段齐不齐、理由有没有、长度超没超），
 * "这份清单能不能写进库"（id 是否合法、是否重复、是否超过位次上限、圈子/话题是否存在）
 * 由 {@code AdminOpsConfigService} 决定 —— 后者对任何调用方都成立，不该只在这里拦一道。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ops")
public class AdminOpsConfigController {

    /** 理由上限，与 {@code ap_admin_audit_log.reason} 列等长 */
    private static final int REASON_MAX_LEN = 500;

    @Autowired
    private AdminOpsConfigService adminOpsConfigService;

    // ==================== 人气圈子 ====================

    /**
     * 当前人气圈子清单（按位次）。
     * GET /api/v1/admin/ops/hot-circles
     */
    @GetMapping("/hot-circles")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult hotCircles() {
        return adminOpsConfigService.hotCircles();
    }

    /**
     * 圈子候选（供运营挑选）。
     * GET /api/v1/admin/ops/circles?keyword=前端&page=1&size=20
     */
    @GetMapping("/circles")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult circles(@RequestParam(value = "keyword", required = false) String keyword,
                                  @RequestParam(value = "page", defaultValue = "1") Integer page,
                                  @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return adminOpsConfigService.searchCircles(keyword, page, size);
    }

    /**
     * 整份替换人气圈子清单（数组顺序即展示顺序）。
     * PUT /api/v1/admin/ops/hot-circles
     */
    @PutMapping("/hot-circles")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult saveHotCircles(@RequestBody AdminOpsOrderDto dto) {
        ResponseResult invalid = validate(dto, "人气圈子");
        if (invalid != null) {
            return invalid;
        }
        return adminOpsConfigService.saveHotCircles(dto.getItems(), dto.getReason().trim());
    }

    // ==================== 推荐话题 ====================

    /**
     * 当前推荐话题清单（按位次）。
     * GET /api/v1/admin/ops/recommend-topics
     */
    @GetMapping("/recommend-topics")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult recommendTopics() {
        return adminOpsConfigService.recommendTopics();
    }

    /**
     * 话题候选（供运营挑选）。
     * GET /api/v1/admin/ops/topics?keyword=Java&page=1&size=20
     */
    @GetMapping("/topics")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult topics(@RequestParam(value = "keyword", required = false) String keyword,
                                 @RequestParam(value = "page", defaultValue = "1") Integer page,
                                 @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return adminOpsConfigService.searchTopics(keyword, page, size);
    }

    /**
     * 整份替换推荐话题清单（数组顺序即位次）。
     * PUT /api/v1/admin/ops/recommend-topics
     */
    @PutMapping("/recommend-topics")
    @RequireAdminPermission(AdminPermission.OPS_CONFIG)
    public ResponseResult saveRecommendTopics(@RequestBody AdminOpsOrderDto dto) {
        ResponseResult invalid = validate(dto, "推荐话题");
        if (invalid != null) {
            return invalid;
        }
        return adminOpsConfigService.saveRecommendTopics(dto.getItems(), dto.getReason().trim());
    }

    /**
     * 两个写接口共用的入参校验，返回 null 表示通过。
     *
     * <p>理由必须非空：运营位决定的是"全站用户第一眼看到什么"，事后追问"这个位置为什么换成它了"
     * 只能靠这句话回答。它与折叠/下架的理由是同一类东西 —— 不是给系统看的，是给人看的。
     */
    private ResponseResult validate(AdminOpsOrderDto dto, String label) {
        if (dto == null || dto.getItems() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "缺少 " + label + " 清单（要清空请传空数组）");
        }
        String reason = dto.getReason() == null ? "" : dto.getReason().trim();
        if (reason.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写操作理由");
        }
        if (reason.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "操作理由不能超过" + REASON_MAX_LEN + "字");
        }
        return null;
    }
}
