package com.heima.content.controller.v1.audit;

import com.heima.content.service.audit.ContentAppealService;
import com.heima.model.audit.dtos.AppealReviewDto;
import com.heima.model.audit.dtos.AppealSubmitDto;
import com.heima.model.audit.pojos.ApContentAppeal;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
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
 * review：人工终审（allow 解除 / uphold 维持；防自审，生产需接运营权限）；
 * status：申诉人查询自己申诉的处理状态与 AI 预审建议。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audit/appeal")
public class ContentAppealController {

    @Autowired
    private ContentAppealService contentAppealService;

    /** 提交申诉（登录 + 归属校验） */
    @PostMapping("/submit")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.USER,
        count = 5, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
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

    /** 人工终审（运营/审核员；防申诉人自审） */
    @PostMapping("/review")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.USER,
        count = 20, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult review(@RequestBody AppealReviewDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto == null || dto.getAppealId() == null || dto.getAction() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
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
