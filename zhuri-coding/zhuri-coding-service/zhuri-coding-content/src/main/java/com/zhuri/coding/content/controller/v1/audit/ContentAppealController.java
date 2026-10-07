package com.zhuri.coding.content.controller.v1.audit;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.audit.AuditReviewerGuard;
import com.zhuri.coding.content.service.audit.ContentAppealService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.audit.dtos.AppealReviewDto;
import com.zhuri.coding.model.audit.dtos.AppealSubmitDto;
import com.zhuri.coding.model.audit.pojos.ApContentAppeal;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容治理申诉端点（AI 预审 + 人工终审）
 *
 * <p>submit：内容归属者对被折叠评论 / 被 AIGC 标注的文章发起申诉；
 * review：人工终审（allow 解除 / uphold 维持；**需授权审核员** —— 见 {@link AuditReviewerGuard}，另有防自审兜底）；
 * status：申诉人查询自己申诉的处理状态与 AI 预审建议。
 *
 * <p><b>⚠️ 本控制器同时面向两类人，鉴权来源不同</b>：{@code submit} 与 {@code status} 是 C 端用户
 * 给自己维权，走 C 端登录；{@code review} 是运营终审，走<b>运营会话</b>。
 * 因此路径是逐个注册的（{@code ADMIN_PATH_PATTERNS} 里给的是
 * {@code /api/v1/audit/appeal/review} 这一条完整路径，而不是 {@code /api/v1/audit/appeal/**}）——
 * 若按前缀整段纳入运营鉴权，上面两个 C 端接口就会被要求运营身份而直接不可用。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audit/appeal")
public class ContentAppealController {

    @Autowired
    private ContentAppealService contentAppealService;

    @Autowired
    private AuditReviewerGuard auditReviewerGuard;

    /** 提交申诉（登录 + 归属校验） */
    @PostMapping("/submit")
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.USER,
        count = 5, interval = 1, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult submit(@RequestBody AppealSubmitDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto == null || dto.getAppealType() == null || dto.getContentId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        if (dto.getAppealType() != ApContentAppeal.TYPE_COMMENT_HIDDEN
            && dto.getAppealType() != ApContentAppeal.TYPE_ARTICLE_AIGC) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不支持的申诉类型");
        }
        log.info("提交内容治理申诉, type={}, contentId={}, userId={}",
            dto.getAppealType(), dto.getContentId(), user.getId());
        return contentAppealService.submit(dto.getAppealType(), dto.getContentId(), dto.getReason(), user.getId());
    }

    /**
     * 人工终审（运营/审核员）。
     *
     * <p><b>这里拿到的 user 是运营账号</b>（{@code ap_admin_account.id}），不是 C 端用户 ——
     * 本路径在运营鉴权清单里，身份由运营会话解析而来。
     *
     * <p>授权有两层：注解交给 {@code AdminAuthInterceptor}，下面的
     * {@link AuditReviewerGuard} 是独立于路径配置的第二道（理由见该类注释）。
     */
    @PostMapping("/review")
    @RequireAdminPermission(AdminPermission.APPEAL_REVIEW)
    @com.zhuri.coding.common.annotation.RateLimit(dimension = com.zhuri.coding.common.annotation.RateLimit.Dimension.USER,
        count = 20, interval = 1, timeUnit = com.zhuri.coding.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult review(@RequestBody AppealReviewDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto == null || dto.getAppealId() == null || dto.getAction() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        // 授权校验：终审会改变治理结论，必须限定为持 APPEAL_REVIEW 权限的运营账号
        // （授权来源是运营角色表 ap_admin_account_role；角色服务不可用时一律拒绝）。
        if (!auditReviewerGuard.isReviewer(user.getId())) {
            log.warn("非审核员尝试终审申诉，已拒绝, accountId={}, appealId={}", user.getId(), dto.getAppealId());
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH);
        }
        return contentAppealService.review(dto.getAppealId(), dto.getAction(), user.getId());
    }

    /** 申诉状态查询（本人） */
    @GetMapping("/status")
    public ResponseResult getStatus(@RequestParam("appealType") Integer appealType,
                                    @RequestParam("contentId") Long contentId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return contentAppealService.getStatus(appealType, contentId, user.getId());
    }
}
