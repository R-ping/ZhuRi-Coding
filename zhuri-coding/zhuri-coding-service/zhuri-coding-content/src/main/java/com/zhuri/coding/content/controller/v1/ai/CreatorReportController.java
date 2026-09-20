package com.heima.content.controller.v1.ai;

import com.heima.content.service.ai.CreatorReportService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作者 AI 复盘报告端点（面向创作者增值服务）
 *
 * <p>本人生成/查看近 N 天创作复盘；同窗口报告缓存 6h 直接返回（控制成本），
 * 无窗口数据时返回引导文案。付费商品化（周报订阅/复盘包）后续接入支付。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/creator")
public class CreatorReportController {

    @Autowired
    private CreatorReportService creatorReportService;

    @PostMapping("/report")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.USER,
        count = 2, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult report(@RequestParam(value = "days", required = false, defaultValue = "7") Integer days) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        log.info("生成作者复盘报告, userId={}, days={}", user.getId(), days);
        return creatorReportService.buildReport(user.getId(), days == null ? 7 : days);
    }
}
