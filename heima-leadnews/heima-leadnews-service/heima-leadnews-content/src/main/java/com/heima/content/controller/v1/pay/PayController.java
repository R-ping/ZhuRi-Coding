package com.heima.content.controller.v1.pay;

import com.heima.content.service.pay.AlipayService;
import com.heima.content.service.order.OrderService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.course.pojos.ApCourseOrder;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/course/pay")
public class PayController {

    @Autowired
    private AlipayService alipayService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private com.heima.content.service.ai.AiTopupService aiTopupService;

    /** 网关对外地址前缀（ALIPAY_BASE_URL）：用于拼装支付异步通知绝对地址（支付宝服务端回调，需外网可达） */
    @Value("${alipay.base-url:http://localhost:51601}")
    private String payBaseUrl;

    /** 前端 Web 地址前缀（ALIPAY_WEB_BASE_URL）：用于拼装课程支付成功后的回跳地址（课程页是前端 Vue SPA） */
    @Value("${alipay.web-base-url:http://localhost:9901}")
    private String webBaseUrl;

    /**
     * 去支付前的准备（登录态）：核验折扣码/5折券并原子抢占 PROCESSING。
     * <p>成功返回 PROCESSING 订单，前端随后再跳转 {@link #payPage}；
     * 失败（订单已关闭/券码失效）返回错误提示，不跳转支付页。</p>
     */
    @PostMapping(value = "/prepare")
    @ResponseBody
    public ResponseResult preparePay(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        String orderNo = params.get("orderNo") != null ? params.get("orderNo").toString() : null;
        return orderService.preparePay(orderNo, user.getId().longValue());
    }

    /** 发起支付 - 返回支付页面 */
    @GetMapping(value = "/page", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String payPage(@RequestParam String orderNo) {
        // 支付页直连（可能有独立窗口无登录态）：幂等抢占 PROCESSING，仍不可支付则拒绝生成收银台
        ResponseResult prep = orderService.preparePay(orderNo, null);
        if (prep.getCode() != 200 || !(prep.getData() instanceof ApCourseOrder)) {
            return errorPage(prep.getMessage() == null || prep.getMessage().isEmpty()
                    ? "订单已关闭或状态异常，请重新下单" : prep.getMessage());
        }
        ApCourseOrder order = (ApCourseOrder) prep.getData();

        String subject = "课程购买 - " + order.getCourseId();
        // 支付异步通知回打后端网关（baseUrl），回跳到前端课程详情页（webBaseUrl，前端 Vue SPA）
        String notifyUrl = payBaseUrl + "/content/api/v1/course/pay/notify";
        String returnUrl = webBaseUrl + "/course/" + order.getCourseId();
        return alipayService.generatePayPage(orderNo, subject, order.getPaidAmount().toString(), notifyUrl, returnUrl);
    }

    /** 生成简单的错误提示页（供支付页直连不可支付时使用） */
    private String errorPage(String msg) {
        String safeMsg = msg == null ? "" : msg.replace("<", "&lt;").replace(">", "&gt;");
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><title>支付失败</title>"
                + "<style>body{font-family:Arial,sans-serif;display:flex;justify-content:center;align-items:center;"
                + "min-height:100vh;background:#f5f5f5;margin:0;color:#333}.box{text-align:center;padding:40px;"
                + "background:#fff;border-radius:12px;box-shadow:0 4px 20px rgba(0,0,0,.1);max-width:420px}"
                + ".icon{font-size:48px;color:#fa5151}.title{font-size:18px;font-weight:600;margin:12px 0 8px}"
                + ".msg{font-size:14px;color:#888}</style></head><body>"
                + "<div class='box'><div class='icon'>&#x26A0;</div>"
                + "<div class='title'>无法发起支付</div><div class='msg'>" + safeMsg + "</div></div></body></html>";
    }

    /** 支付异步通知 */
    @PostMapping("/notify")
    @ResponseBody
    public String payNotify(HttpServletRequest request) {
        // 收集支付宝异步通知全部参数，用于服务端签名校验（rsaCheckV1 需要去除 sign/sign_type 后的完整参数集）
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((key, values) ->
                params.put(key, values != null && values.length > 0 ? values[0] : ""));

        // 1. 先验签：验签失败直接拒绝，不进入业务处理，防止伪造回调
        if (!alipayService.verifySign(params)) {
            return "fail";
        }

        // 2. 校验 app_id / seller_id 与本应用配置一致，防止跨应用回调混淆
        String notifyAppId = request.getParameter("app_id");
        if (notifyAppId == null || !notifyAppId.equals(alipayService.getAppId())) {
            log.warn("支付宝回调 app_id 不匹配, notifyAppId={}, expect={}", notifyAppId, alipayService.getAppId());
            return "fail";
        }

        String tradeNo = request.getParameter("trade_no");
        String orderNo = request.getParameter("out_trade_no");
        String totalAmount = request.getParameter("total_amount");
        String status = request.getParameter("trade_status");

        // AI 额度包订单（out_trade_no 前缀 "ai"）分发到充值服务；金额校验/幂等/入账在其内部完成
        if (orderNo != null && orderNo.startsWith(com.heima.model.ai.pojos.AiTopupOrder.ORDER_PREFIX)) {
            boolean aiOk = aiTopupService.handleNotify(orderNo, totalAmount, tradeNo);
            if (!aiOk) {
                log.warn("AI 额度包回调处理失败, orderNo={}", orderNo);
            }
            return aiOk ? "success" : "fail";
        }

        boolean success = alipayService.handleNotify(tradeNo, orderNo, totalAmount, status);
        return success ? "success" : "fail";
    }
}