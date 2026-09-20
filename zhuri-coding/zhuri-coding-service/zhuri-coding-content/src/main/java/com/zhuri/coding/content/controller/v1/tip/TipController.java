package com.zhuri.coding.content.controller.v1.tip;

import com.zhuri.coding.content.service.pay.AlipayService;
import com.zhuri.coding.content.service.tip.TipService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章打赏控制器
 *
 * <p>创建打赏订单、生成支付页面、处理支付回调、获取汇总与感谢名单、作者打赏收益。
 */
@RestController
@RequestMapping("/api/v1/tip")
public class TipController {

    @Autowired
    private TipService tipService;

    @Autowired
    private AlipayService alipayService;

    /** 创建打赏订单（需登录） */
    @PostMapping("/create")
    public ResponseResult create(@RequestBody Map<String, Object> body) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long articleId = body.get("articleId") != null ? Long.valueOf(body.get("articleId").toString()) : null;
        BigDecimal amount = body.get("amount") != null ? new BigDecimal(body.get("amount").toString()) : null;
        String message = body.get("message") != null ? body.get("message").toString() : "";
        return tipService.createOrder(articleId, amount, message, user.getId().longValue());
    }

    /** 生成打赏支付页面 HTML */
    @GetMapping(value = "/pay/page", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String payPage(@RequestParam String orderNo) {
        return tipService.getPayPage(orderNo);
    }

    /** 打赏支付异步通知（支付宝回调） */
    @PostMapping("/notify")
    @ResponseBody
    public String payNotify(HttpServletRequest request) {
        // 收集支付宝异步通知全部参数，用于服务端签名校验（rsaCheckV1 需要去除 sign/sign_type 后的完整参数集）
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((key, values) ->
                params.put(key, values != null && values.length > 0 ? values[0] : ""));

        // 1. 先验签：验签失败直接拒绝，防止伪造打赏回调
        if (!alipayService.verifySign(params)) {
            return "fail";
        }

        String tradeNo = request.getParameter("trade_no");
        String orderNo = request.getParameter("out_trade_no");
        String totalAmount = request.getParameter("total_amount");
        String status = request.getParameter("trade_status");
        boolean success = tipService.handleNotify(tradeNo, orderNo, totalAmount, status);
        return success ? "success" : "fail";
    }

    /** 获取文章打赏汇总信息（人数、总金额） */
    @GetMapping("/summary")
    public ResponseResult summary(@RequestParam Long articleId) {
        return tipService.getTipSummary(articleId);
    }

    /** 获取文章打赏名单（公开感谢名单） */
    @GetMapping("/list")
    public ResponseResult list(@RequestParam Long articleId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size) {
        return tipService.getTipList(articleId, page, size);
    }

    /** 作者打赏收益汇总（创作中心结算） */
    @GetMapping("/my-revenue")
    public ResponseResult myRevenue() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return tipService.getMyRevenue(user.getId().longValue());
    }
}
