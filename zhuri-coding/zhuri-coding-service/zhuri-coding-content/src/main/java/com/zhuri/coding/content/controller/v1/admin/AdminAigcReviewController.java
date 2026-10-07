package com.zhuri.coding.content.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.admin.AdminAigcReviewService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.AdminAigcReviewActionDto;
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
 * 运营后台 · AIGC 复核队列。
 *
 * <p>检测链路把疑似水文打标（关打赏/禁售/不入向量库），但这个处置是<b>静默</b>的 ——
 * 内容还在、作者几乎感知不到，也就不会有人来申诉。此前唯一的恢复通道是申诉终审，
 * 等于恢复通道永远没人走。这里补的是"运营主动巡检"的入口：
 * 按疑似分从高到低过一遍 flagged 记录，逐条放行（清标记）或确认（保留标记）。
 *
 * <p><b>为什么放行与确认是两个端点而不是一个接口加个 outcome 参数</b>：
 * 两者是不同的运营动作（一个恢复作者权益、一个钉死机器判定），理由和审计各是各的。
 * 合成一个接口，审计里就只剩"某人对某记录做了复核"——"为什么放行"和"为什么确认"
 * 从动作编码上分不开了。与 {@code AdminActivityController} 的上线/下线同一取舍。
 *
 * <p><b>写操作全走 POST</b>：复核是"再做一次动作"的语义，第二次调用应当被状态闸口
 * 拒掉（"无需复核"），而不是静默成功。幂等靠状态比对实现，不靠 HTTP 方法承诺。
 *
 * <p><b>校验分工</b>：这里只管"请求合不合规矩"（理由有没有、超没超长），
 * "这条记录能不能复核"（存在性、当前状态）由服务层决定——
 * 后者对任何调用方都成立，不该只在这里拦一道。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/aigc-reviews")
public class AdminAigcReviewController {

    /** 理由上限，与 {@code ap_admin_audit_log.reason} 列等长 */
    private static final int REASON_MAX_LEN = 500;

    @Autowired
    private AdminAigcReviewService adminAigcReviewService;

    /**
     * 复核队列（仅已 flagged，按疑似分降序）。
     * GET /api/v1/admin/aigc-reviews?page=1&size=20
     */
    @GetMapping
    @RequireAdminPermission(AdminPermission.AUDIT_REVIEW)
    public ResponseResult page(@RequestParam(value = "page", defaultValue = "1") Integer page,
                               @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return adminAigcReviewService.page(page, size);
    }

    /**
     * 复核放行：记录转"复核放行"，业务主表清 AI 标记（打赏/禁售/向量库随之恢复）。
     * POST /api/v1/admin/aigc-reviews/{id}/clear
     */
    @PostMapping("/{id}/clear")
    @RequireAdminPermission(AdminPermission.AUDIT_REVIEW)
    public ResponseResult clear(@PathVariable("id") Long id,
                                @RequestBody AdminAigcReviewActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminAigcReviewService.clear(id, dto.getReason().trim());
    }

    /**
     * 人工确认 AI 水文：记录转"人工确认"，业务标记保留（处置维持）。
     * POST /api/v1/admin/aigc-reviews/{id}/confirm
     */
    @PostMapping("/{id}/confirm")
    @RequireAdminPermission(AdminPermission.AUDIT_REVIEW)
    public ResponseResult confirm(@PathVariable("id") Long id,
                                  @RequestBody AdminAigcReviewActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminAigcReviewService.confirm(id, dto.getReason().trim());
    }

    /**
     * 理由必须非空：放行决定"作者的变现闸门是否重新打开"，确认决定"疑似是否钉死"。
     * 事后追问只能靠这句话回答。
     */
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
