package com.heima.content.service.order;

import com.heima.model.course.pojos.ApCourseOrder;
import com.heima.model.common.dtos.ResponseResult;

public interface OrderService {

    /** 创建订单 */
    ResponseResult createOrder(Long courseId, String discountCode, Long userId, String payType);

    /** 免费加入课程（仅限价格为0的免费小册，直接授予阅读权限，不创建订单） */
    ResponseResult freeJoin(Long courseId, Long userId);

    /** 查询订单状态 */
    ResponseResult getOrderStatus(String orderNo);

    /** 我的订单列表 */
    ResponseResult getMyOrders(Long userId, Integer page, Integer size);

    /** 支付成功回调处理 */
    void handlePaySuccess(String orderNo, String tradeNo);

    /** 根据订单号查询 */
    ApCourseOrder getByOrderNo(String orderNo);
}