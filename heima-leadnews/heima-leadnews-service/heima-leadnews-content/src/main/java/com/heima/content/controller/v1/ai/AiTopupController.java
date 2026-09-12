package com.heima.content.controller.v1.ai;

import com.heima.content.service.ai.AiTopupService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 额度包充值端点（订阅-配额打通，支付宝沙箱）
 *
 * <p>create 生成订单 → page 返回收银台 HTML（前端整页跳转）→ 支付异步通知
 * 走 PayController /notify（out_trade_no 前缀 "ai" 自动分发）→ 入账钱包。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/topup")
public class AiTopupController {

    @Autowired
    private AiTopupService aiTopupService;

    /** 创建额度包订单 */
    @PostMapping("/create")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.USER,
        count = 10, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult create(@RequestParam("packageCode") String packageCode) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        log.info("AI 额度包下单, userId={}, packageCode={}", user.getId(), packageCode);
        return aiTopupService.createTopup(user.getId(), packageCode);
    }

    /** 支付收银台 HTML（整页打开即自动跳转支付宝） */
    @GetMapping(value = "/page", produces = MediaType.TEXT_HTML_VALUE)
    public String page(@RequestParam("orderNo") String orderNo) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return "<html><body><p>请先登录后重试</p></body></html>";
        }
        ResponseResult r = aiTopupService.buildPayPage(orderNo, user.getId());
        if (r.getCode() == 200 && r.getData() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.getData();
            Object html = data.get("html");
            if (html != null) {
                return String.valueOf(html);
            }
        }
        String msg = r.getMessage() != null ? r.getMessage() : "生成支付页失败";
        return "<html><body><p>" + escapeHtml(msg)
            + "，<a href=\"javascript:history.back()\">返回重试</a></p></body></html>";
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 充值订单状态（本人） */
    @GetMapping("/status")
    public ResponseResult status(@RequestParam("orderNo") String orderNo) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return aiTopupService.getStatus(orderNo, user.getId());
    }
}
