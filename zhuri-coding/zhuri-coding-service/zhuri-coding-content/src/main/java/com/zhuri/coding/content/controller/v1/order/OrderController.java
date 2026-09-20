package com.zhuri.coding.content.controller.v1.order;

import com.zhuri.coding.content.service.order.OrderService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/course/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /** 创建订单 */
    @PostMapping("/create")
    public ResponseResult createOrder(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        String discountCode = params.get("discountCode") != null ? params.get("discountCode").toString() : null;
        String couponItemCode = params.get("couponItemCode") != null ? params.get("couponItemCode").toString() : null;
        String payType = params.get("payType")!=null?params.get("payType").toString():null;
        return orderService.createOrder(courseId, discountCode, couponItemCode, user.getId().longValue(),payType);
    }

    /** 查询订单状态 */
    @GetMapping("/status")
    public ResponseResult getOrderStatus(@RequestParam String orderNo) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return orderService.getOrderStatus(orderNo, user.getId().longValue());
    }

    /** 我的订单列表 */
    @GetMapping("/my")
    public ResponseResult getMyOrders(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return orderService.getMyOrders(user.getId().longValue(), page, size);
    }
}