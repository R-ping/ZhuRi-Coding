package com.heima.content.controller.v1.pay;

import com.heima.content.service.pay.AlipayService;
import com.heima.content.service.order.OrderService;
import com.heima.model.course.pojos.ApCourseOrder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/course/pay")
public class PayController {

    @Autowired
    private AlipayService alipayService;

    @Autowired
    private OrderService orderService;

    /** 支付异步通知地址（支付宝服务端回调，需外网可达） */
    @Value("${alipay.notify-url}")
    private String notifyUrl;

    /** 前端 Web 地址前缀，用于拼装课程支付成功后的回跳地址（课程页是前端 Vue SPA） */
    @Value("${alipay.web-base-url:http://localhost:9901}")
    private String webBaseUrl;

    /** 发起支付 - 返回支付页面 */
    @GetMapping(value = "/page", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String payPage(@RequestParam String orderNo) {
        ApCourseOrder order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            return "<html><body><h2>订单不存在</h2></body></html>";
        }

        if (order.getStatus() != ApCourseOrder.Status.PENDING.getCode()) {
            return "<html><body><h2>订单状态异常</h2></body></html>";
        }

        String subject = "课程购买 - " + order.getCourseId();
        // 支付成功后回跳到前端课程详情页（使用前端对外地址，而非后端网关地址）
        String returnUrl = webBaseUrl + "/course/" + order.getCourseId();
        return alipayService.generatePayPage(orderNo, subject, order.getPaidAmount().toString(), notifyUrl, returnUrl);
    }

    /** 支付异步通知 */
    @PostMapping("/notify")
    @ResponseBody
    public String payNotify(HttpServletRequest request) {
        String tradeNo = request.getParameter("trade_no");
        String orderNo = request.getParameter("out_trade_no");
        String totalAmount = request.getParameter("total_amount");
        String status = request.getParameter("trade_status");

        boolean success = alipayService.handleNotify(tradeNo, orderNo, totalAmount, status);
        return success ? "success" : "fail";
    }
}