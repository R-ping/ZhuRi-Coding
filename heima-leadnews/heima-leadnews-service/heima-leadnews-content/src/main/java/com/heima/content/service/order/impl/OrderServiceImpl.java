package com.heima.content.service.order.impl;

import com.heima.apis.reward.IRewardClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.mapper.course.ApCourseOrderMapper;
import com.heima.content.mapper.course.ApUserCourseMapper;
import com.heima.content.service.order.DiscountService;
import com.heima.content.service.order.OrderService;
import com.heima.content.service.payment.PaymentRewardService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.course.pojos.ApCourseDiscount;
import com.heima.model.course.pojos.ApCourseOrder;
import com.heima.model.course.pojos.ApCourseOrder.PayType;
import com.heima.model.user.pojos.ApUserCourse;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private ApCourseOrderMapper orderMapper;

    @Autowired
    private ApCourseMapper courseMapper;

    @Autowired
    private ApUserCourseMapper userCourseMapper;

    @Autowired
    private DiscountService discountService;

    @Autowired
    private PaymentRewardService paymentRewardService;

    @Autowired
    private IRewardClient rewardClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createOrder(Long courseId, String discountCode, String couponItemCode, Long userId, String payType) {
        if (courseId == null || userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        // 查询课程信息获取真实价格
        ApCourse course = courseMapper.selectById(courseId);
        if (course == null || course.getIsDeleted() == 1) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        BigDecimal originalAmount = course.getPrice() != null ? course.getPrice() : BigDecimal.ZERO;

        BigDecimal discountAmount = BigDecimal.ZERO;
        ApCourseDiscount discount = null;
        String usedCouponCode = null;

        // 优先使用抽奖获得的通用5折券（全课程通用，收取后校验 reward 侧持有数量）
        if (couponItemCode != null && !couponItemCode.isEmpty()) {
            ResponseResult holdResult = rewardClient.getVirtualAssetHold(userId, couponItemCode);
            Map<String, Object> holdData = holdResult != null && holdResult.getData() != null
                    ? (Map<String, Object>) holdResult.getData() : Collections.emptyMap();
            Object qtyObj = holdData.get("quantity");
            int quantity = qtyObj instanceof Number ? ((Number) qtyObj).intValue() : 0;
            if (quantity < 1) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "道具不足，无法使用该折扣券");
            }
            Object rateObj = holdData.get("discountRate");
            double discountRate = rateObj instanceof Number ? ((Number) rateObj).doubleValue() : 1.0d;
            // payable 比例：5折券 rate=0.5 → 折扣金额 = 原价 * (1 - 0.5)
            discountAmount = originalAmount.multiply(
                    BigDecimal.valueOf(Math.max(0.0d, Math.min(1.0d, 1.0d - discountRate)))
            );
            usedCouponCode = couponItemCode;
            log.info("课程下单使用5折券: userId={}, courseId={}, coupon={}, rate={}, discount={}",
                    userId, courseId, couponItemCode, discountRate, discountAmount);
        } else if (discountCode != null && !discountCode.isEmpty()) {
            discount = discountService.validateDiscount(discountCode, courseId);
            if (discount == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "折扣码无效或已过期");
            }

            // 计算折扣金额
            if (discount.getDiscountType() == ApCourseDiscount.DiscountType.FIXED.getCode()) {
                discountAmount = discount.getDiscountValue();
            } else if (discount.getDiscountType() == ApCourseDiscount.DiscountType.PERCENTAGE.getCode()) {
                discountAmount = originalAmount.multiply(
                    BigDecimal.ONE.subtract(discount.getDiscountValue().divide(new BigDecimal("100")))
                );
                discountAmount = originalAmount.subtract(discountAmount);
            }
        }

        // 计算实付金额
        BigDecimal paidAmount = originalAmount.subtract(discountAmount);
        if (paidAmount.compareTo(BigDecimal.ZERO) < 0) {
            paidAmount = BigDecimal.ZERO;
        }

        // 创建订单
        ApCourseOrder order = new ApCourseOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId.intValue());
        order.setCourseId(courseId);
        order.setOriginalAmount(originalAmount);
        order.setDiscountAmount(discountAmount);
        order.setPayMethod(payType==null?PayType.OTHER:PayType.valueOf(payType));
        order.setPaidAmount(paidAmount);
        order.setTotalAmount(paidAmount);
        order.setDiscountCode(discountCode != null ? discountCode : "");
        order.setCouponItemCode(usedCouponCode != null ? usedCouponCode : "");
        order.setStatus(ApCourseOrder.Status.PENDING.getCode());
        order.setCreatedTime(new Date());
        order.setUpdatedTime(new Date());

        orderMapper.insert(order);

        return ResponseResult.okResult(order);
    }

    @Override
    public ResponseResult getOrderStatus(String orderNo, Long userId) {
        ApCourseOrder order = getByOrderNo(orderNo);
        if (order == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "订单不存在");
        }
        // 归属校验：仅允许查询本人订单，防止越权查看他人订单（含手机号/金额等敏感信息）
        if (userId == null || order.getUserId() == null || !userId.equals(order.getUserId().longValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无权访问该订单");
        }
        return ResponseResult.okResult(order);
    }

    @Override
    public ResponseResult getMyOrders(Long userId, Integer page, Integer size) {
        IPage<ApCourseOrder> iPage = new Page<>(page, size);
        LambdaQueryWrapper<ApCourseOrder> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseOrder::getUserId, userId.intValue());
        query.orderByDesc(ApCourseOrder::getCreatedTime);

        IPage<ApCourseOrder> resultPage = orderMapper.selectPage(iPage, query);
        Map<String, Object> data = new HashMap<>();
        data.put("list", resultPage.getRecords());
        data.put("total", resultPage.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handlePaySuccess(String orderNo, String tradeNo) {
        ApCourseOrder order = getByOrderNo(orderNo);
        if (order == null) {
            log.error("订单不存在: {}", orderNo);
            return;
        }

        // ★ 幂等：用"条件更新"原子抢占 PENDING→PAID，
        // 只有受影响行数==1 才继续发奖，杜绝支付宝重复通知/并发回调导致的重复放权、重复加销量。
        Date now = new Date();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ApCourseOrder>()
                .eq(ApCourseOrder::getOrderNo, orderNo)
                .eq(ApCourseOrder::getStatus, ApCourseOrder.Status.PENDING.getCode())
                .set(ApCourseOrder::getStatus, ApCourseOrder.Status.PAID.getCode())
                .set(ApCourseOrder::getTradeNo, tradeNo)
                .set(ApCourseOrder::getPayTime, now)
                .set(ApCourseOrder::getUpdatedTime, now));
        if (updated != 1) {
            log.warn("订单非待支付态或已被处理，跳过幂等后续: orderNo={}, status={}", orderNo, order.getStatus());
            return;
        }

        // 原子更新折扣码使用次数（防止并发超卖）
        if (order.getDiscountCode() != null && !order.getDiscountCode().isEmpty()) {
            boolean consumed = discountService.consumeDiscountCode(order.getDiscountCode());
            if (!consumed) {
                log.warn("折扣码使用次数已达上限或无效: code={}", order.getDiscountCode());
            }
        }

        // 核销抽奖获得的通用5折券（reward 侧原子扣减持有数量，防止超核）
        if (order.getCouponItemCode() != null && !order.getCouponItemCode().isEmpty()) {
            try {
                ResponseResult consumeResult = rewardClient.consumeVirtualAsset(
                        order.getUserId().longValue(), order.getCouponItemCode(), 1);
                if (consumeResult == null || consumeResult.getCode() != 200) {
                    log.warn("5折券核销失败，需补偿: orderNo={}, coupon={}",
                            orderNo, order.getCouponItemCode());
                }
            } catch (Exception e) {
                log.error("5折券核销异常: orderNo={}, coupon={}", orderNo, order.getCouponItemCode(), e);
            }
        }

        // 更新课程学习人数和销售数量
        ApCourse course = courseMapper.selectById(order.getCourseId());
        if (course != null) {
            course.setStudyCount((course.getStudyCount() != null ? course.getStudyCount() : 0) + 1);
            course.setSalesCount((course.getSalesCount() != null ? course.getSalesCount() : 0) + 1);
            if (course.getTotalRevenue() != null) {
                course.setTotalRevenue(course.getTotalRevenue().add(order.getPaidAmount()));
            } else {
                course.setTotalRevenue(order.getPaidAmount());
            }
            courseMapper.updateById(course);
        }

        // 添加用户课程权限
        LambdaQueryWrapper<ApUserCourse> ucQuery = new LambdaQueryWrapper<>();
        ucQuery.eq(ApUserCourse::getUserId, order.getUserId());
        ucQuery.eq(ApUserCourse::getCourseId, order.getCourseId());
        ApUserCourse userCourse = userCourseMapper.selectOne(ucQuery);
        if (userCourse == null) {
            userCourse = new ApUserCourse();
            userCourse.setUserId(order.getUserId());
            userCourse.setCourseId(order.getCourseId());
            userCourse.setAccessType(1); // 购买获得
            userCourse.setIsActive((byte) 1);
            userCourse.setIsTrial(0);
            userCourse.setProgress(BigDecimal.ZERO);
            userCourse.setLastLearnAt(new Date());
            userCourse.setCreatedTime(new Date());
            userCourseMapper.insert(userCourse);
        } else {
            userCourse.setIsActive((byte) 1);
            userCourse.setAccessType(1);
            userCourse.setIsTrial(0);
            userCourse.setLastLearnAt(new Date());
            userCourseMapper.updateById(userCourse);
        }

        // 4. 支付成功联动：加逐日等级经验 + 发"系统通知"站内信（失败不影响支付主流程）
        try {
            paymentRewardService.onCoursePurchaseSuccess(order.getUserId().longValue(),
                order.getCourseId(), order.getPaidAmount(), order.getOrderNo());
        } catch (Exception e) {
            log.error("课程支付成功联动失败: orderNo={}", orderNo, e);
        }

        log.info("订单支付成功: orderNo={}, tradeNo={}, userId={}, courseId={}",
                orderNo, tradeNo, order.getUserId(), order.getCourseId());
    }

    @Override
    public ApCourseOrder getByOrderNo(String orderNo) {
        LambdaQueryWrapper<ApCourseOrder> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseOrder::getOrderNo, orderNo);
        return orderMapper.selectOne(query);
    }

    private String generateOrderNo() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        return sdf.format(new Date()) + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}