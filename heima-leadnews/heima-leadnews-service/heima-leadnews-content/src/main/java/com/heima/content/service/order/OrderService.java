package com.heima.content.service.order;

import com.heima.model.course.pojos.ApCourseOrder;
import com.heima.model.common.dtos.ResponseResult;

public interface OrderService {

    /**
     * 创建订单。
     *
     * @param courseId        课程ID
     * @param discountCode    课程专属折扣码（可空）
     * @param couponItemCode  抽奖获得的通用5折券道具代码（如 course50，可空；与折扣码二选一）
     * @param userId          用户ID
     * @param payType         支付方式
     */
    ResponseResult createOrder(Long courseId, String discountCode, String couponItemCode, Long userId, String payType);

    /**
     * 查询订单状态。
     * <p>仅返回当前登录用户自己名下订单，防止越权查看他人订单。
     *
     * @param orderNo 订单号
     * @param userId  当前登录用户 ID（用于归属校验）
     */
    ResponseResult getOrderStatus(String orderNo, Long userId);

    /** 我的订单列表 */
    ResponseResult getMyOrders(Long userId, Integer page, Integer size);

    /** 支付成功回调处理 */
    void handlePaySuccess(String orderNo, String tradeNo);

    /** 根据订单号查询 */
    ApCourseOrder getByOrderNo(String orderNo);
}