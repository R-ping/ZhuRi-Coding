package com.heima.content.service.order.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createOrder(Long courseId, String discountCode, Long userId, String payType) {
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

        // 校验折扣码
        if (discountCode != null && !discountCode.isEmpty()) {
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
        order.setStatus(ApCourseOrder.Status.PENDING.getCode());
        order.setCreatedTime(new Date());
        order.setUpdatedTime(new Date());

        orderMapper.insert(order);

        return ResponseResult.okResult(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult freeJoin(Long courseId, Long userId) {
        if (courseId == null || userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        // 校验课程存在且为免费小册（price <= 0）
        ApCourse course = courseMapper.selectById(courseId);
        if (course == null || course.getIsDeleted() == 1) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }
        BigDecimal price = course.getPrice() != null ? course.getPrice() : BigDecimal.ZERO;
        if (price.compareTo(BigDecimal.ZERO) > 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "付费小册请先购买");
        }

        // 幂等：若已有有效权限则直接返回成功
        LambdaQueryWrapper<ApUserCourse> ucQuery = new LambdaQueryWrapper<>();
        ucQuery.eq(ApUserCourse::getUserId, userId.intValue());
        ucQuery.eq(ApUserCourse::getCourseId, courseId);
        ApUserCourse userCourse = userCourseMapper.selectOne(ucQuery);
        if (userCourse == null) {
            // 直接写入用户课程权限，不创建任何订单
            userCourse = new ApUserCourse();
            userCourse.setUserId(userId.intValue());
            userCourse.setCourseId(courseId);
            userCourse.setAccessType(2); // 免费获得
            userCourse.setIsActive((byte) 1);
            userCourse.setIsTrial(0);
            userCourse.setProgress(BigDecimal.ZERO);
            userCourse.setLastLearnAt(new Date());
            userCourse.setCreatedTime(new Date());
            userCourseMapper.insert(userCourse);
        } else if (userCourse.getIsActive() == null || userCourse.getIsActive() != (byte) 1) {
            userCourse.setIsActive((byte) 1);
            userCourse.setLastLearnAt(new Date());
            userCourseMapper.updateById(userCourse);
        }

        // 免费加入联动：更新学习人数 + 加逐日等级经验（失败不影响主流程）
        try {
            course.setStudyCount((course.getStudyCount() != null ? course.getStudyCount() : 0) + 1);
            courseMapper.updateById(course);
            paymentRewardService.onCoursePurchaseSuccess(userId, courseId, BigDecimal.ZERO, null);
        } catch (Exception e) {
            log.error("免费加入联动失败: courseId={}, userId={}", courseId, userId, e);
        }

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    @Override
    public ResponseResult getOrderStatus(String orderNo) {
        ApCourseOrder order = getByOrderNo(orderNo);
        if (order == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "订单不存在");
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

        if (order.getStatus() != ApCourseOrder.Status.PENDING.getCode()) {
            log.warn("订单状态异常: {}, status={}", orderNo, order.getStatus());
            return;
        }

        order.setStatus(ApCourseOrder.Status.PAID.getCode());
        order.setTradeNo(tradeNo);
        order.setPayTime(new Date());
        order.setUpdatedTime(new Date());
        orderMapper.updateById(order);

        // 原子更新折扣码使用次数（防止并发超卖）
        if (order.getDiscountCode() != null && !order.getDiscountCode().isEmpty()) {
            boolean consumed = discountService.consumeDiscountCode(order.getDiscountCode());
            if (!consumed) {
                log.warn("折扣码使用次数已达上限或无效: code={}", order.getDiscountCode());
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