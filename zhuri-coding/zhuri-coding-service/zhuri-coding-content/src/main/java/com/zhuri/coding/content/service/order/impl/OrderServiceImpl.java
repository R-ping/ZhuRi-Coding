package com.zhuri.coding.content.service.order.impl;

import com.zhuri.coding.apis.reward.IRewardClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.course.ApCourseChapterMapper;
import com.zhuri.coding.content.mapper.course.ApCourseMapper;
import com.zhuri.coding.content.mapper.course.ApCourseOrderMapper;
import com.zhuri.coding.content.mapper.course.ApUserCourseMapper;
import com.zhuri.coding.content.service.order.DiscountService;
import com.zhuri.coding.content.service.order.OrderService;
import com.zhuri.coding.content.service.outbox.OutboxService;
import com.zhuri.coding.content.service.outbox.handler.PayRewardOutboxHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.course.pojos.ApCourse;
import com.zhuri.coding.model.course.pojos.ApCourseChapter;
import com.zhuri.coding.model.course.pojos.ApCourseDiscount;
import com.zhuri.coding.model.course.pojos.ApCourseOrder;
import com.zhuri.coding.model.course.pojos.ApCourseOrder.PayType;
import com.zhuri.coding.model.user.pojos.ApUserCourse;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
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
    private ApCourseChapterMapper courseChapterMapper;

    @Autowired
    private ApUserCourseMapper userCourseMapper;

    @Autowired
    private DiscountService discountService;

    @Autowired
    private OutboxService outboxService;

    /** 支付联动事件载荷序列化（Outbox payload） */
    private static final ObjectMapper PAY_REWARD_MAPPER = new ObjectMapper();

    @Autowired
    private IRewardClient rewardClient;

    @Autowired
    private OrderTimeoutTask orderTimeoutTask;

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
        // Step4 内容诚信：课程含疑似 AI 水文小节禁止售卖（防付费凑数）
        Long aigcChapters = courseChapterMapper.selectCount(new LambdaQueryWrapper<ApCourseChapter>()
            .eq(ApCourseChapter::getCourseId, courseId)
            .eq(ApCourseChapter::getIsAigc, 1));
        if (aigcChapters != null && aigcChapters > 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "该课程包含疑似 AI 生成内容，暂不可购买");
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

        // 排程超时关单：订单进入延迟队列，超时未支付则由消费者条件更新置为 CANCELLED（幂等，不影响已支付订单）
        try {
            orderTimeoutTask.scheduleClose(order.getOrderNo());
        } catch (Exception e) {
            log.error("订单超时关单排程失败, orderNo={}", order.getOrderNo(), e);
        }

        return ResponseResult.okResult(order);
    }

    @Override
    public void closeExpiredOrder(String orderNo) {
        if (orderNo == null || orderNo.isEmpty()) {
            return;
        }
        // 条件更新抢占：仅 PENDING 状态可被关闭；若期间已支付（PAID）则不受影响，保证幂等
        Date now = new Date();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ApCourseOrder>()
                .eq(ApCourseOrder::getOrderNo, orderNo)
                .eq(ApCourseOrder::getStatus, ApCourseOrder.Status.PENDING.getCode())
                .set(ApCourseOrder::getStatus, ApCourseOrder.Status.CANCELLED.getCode())
                .set(ApCourseOrder::getUpdatedTime, now));
        if (updated == 1) {
            log.info("订单超时已关闭: orderNo={}", orderNo);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult preparePay(String orderNo, Long userId) {
        if (orderNo == null || orderNo.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "订单号不能为空");
        }
        ApCourseOrder order = getByOrderNo(orderNo);
        if (order == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "订单不存在");
        }
        // 归属校验仅作用于登录态；支付页直连（userId=null）不拦截
        if (userId != null && (order.getUserId() == null || !userId.equals(order.getUserId().longValue()))) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无权操作该订单");
        }

        int current = order.getStatus() == null ? -1 : order.getStatus();
        // 已在支付处理中：幂等放行（如支付页刷新、去支付重试），不重复抢占/核验/排程
        if (current == ApCourseOrder.Status.PROCESSING.getCode()) {
            return ResponseResult.okResult(order);
        }
        // 非待支付态（已关闭/已支付/已退款）不可再发起支付
        if (current != ApCourseOrder.Status.PENDING.getCode()) {
            log.warn("订单状态非待支付，拒绝发起支付: orderNo={}, status={}", orderNo, current);
            return ResponseResult.errorResult(AppHttpCodeEnum.ORDER_STATUS_INVALID,
                    "订单已关闭或已处理，请重新下单");
        }

        // 核验折扣码/5折券有效性（登录态才可校验 reward 持有量）
        if (userId != null) {
            String promoMsg = validateOrderPromotion(order, userId);
            if (promoMsg != null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, promoMsg);
            }
        }

        // 原子抢占 PENDING→PROCESSING：并发下若关单(→CANCELLED)先行则命中0行，提示重新下单
        Date now = new Date();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ApCourseOrder>()
                .eq(ApCourseOrder::getOrderNo, orderNo)
                .eq(ApCourseOrder::getStatus, ApCourseOrder.Status.PENDING.getCode())
                .set(ApCourseOrder::getStatus, ApCourseOrder.Status.PROCESSING.getCode())
                .set(ApCourseOrder::getUpdatedTime, now));
        if (updated != 1) {
            log.warn("去支付时订单已被关单抢占失败: orderNo={}", orderNo);
            return ResponseResult.errorResult(AppHttpCodeEnum.ORDER_CLOSED);
        }

        // 排程支付通道超时关单，防止用户在支付页长时间滞留后仍可支付
        try {
            orderTimeoutTask.scheduleClosePayChannel(orderNo);
        } catch (Exception e) {
            log.error("支付通道超时排程失败, orderNo={}", orderNo, e);
        }

        order.setStatus(ApCourseOrder.Status.PROCESSING.getCode());
        order.setUpdatedTime(now);
        log.info("订单进入支付处理中: orderNo={}, userId={}", orderNo, userId);
        return ResponseResult.okResult(order);
    }

    /**
     * 核验下单时使用的折扣码/通用5折券在去支付时仍有效。
     *
     * @return 校验失败返回提示文案，校验通过返回 null
     */
    private String validateOrderPromotion(ApCourseOrder order, Long userId) {
        // 通用5折券：reward 侧持有数量必须仍 >= 1
        if (order.getCouponItemCode() != null && !order.getCouponItemCode().isEmpty()) {
            try {
                ResponseResult holdResult = rewardClient.getVirtualAssetHold(userId, order.getCouponItemCode());
                Map<String, Object> holdData = holdResult != null && holdResult.getData() != null
                        ? (Map<String, Object>) holdResult.getData() : Collections.emptyMap();
                int quantity = holdData.get("quantity") instanceof Number
                        ? ((Number) holdData.get("quantity")).intValue() : 0;
                if (quantity < 1) {
                    return "5折券数量不足或已失效，请重新下单";
                }
            } catch (Exception e) {
                log.error("核验5折券异常: orderNo={}, coupon={}", order.getOrderNo(), order.getCouponItemCode(), e);
                return "5折券校验失败，请稍后重试";
            }
        }
        // 课程折扣码：仍有效才放行
        if (order.getDiscountCode() != null && !order.getDiscountCode().isEmpty()) {
            ApCourseDiscount d = discountService.validateDiscount(order.getDiscountCode(), order.getCourseId());
            if (d == null) {
                return "折扣码已失效，请重新下单";
            }
        }
        return null;
    }

    @Override
    public void closePayChannel(String orderNo) {
        if (orderNo == null || orderNo.isEmpty()) {
            return;
        }
        // 条件更新抢占：仅 PROCESSING 状态可被关闭（支付通道超时）；已支付/已关闭不受影响
        Date now = new Date();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ApCourseOrder>()
                .eq(ApCourseOrder::getOrderNo, orderNo)
                .eq(ApCourseOrder::getStatus, ApCourseOrder.Status.PROCESSING.getCode())
                .set(ApCourseOrder::getStatus, ApCourseOrder.Status.CANCELLED.getCode())
                .set(ApCourseOrder::getUpdatedTime, now));
        if (updated == 1) {
            log.info("支付通道超时已关闭: orderNo={}", orderNo);
        }
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
    public boolean handlePaySuccess(String orderNo, String tradeNo) {
        ApCourseOrder order = getByOrderNo(orderNo);
        if (order == null) {
            log.error("订单不存在: {}", orderNo);
            return false;
        }

        // ★ 幂等：用"条件更新"原子抢占 PENDING/PROCESSING→PAID，
        // 只有受影响行数==1 才继续发奖，杜绝支付宝重复通知/并发回调导致的重复放权、重复加销量。
        Date now = new Date();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ApCourseOrder>()
                .eq(ApCourseOrder::getOrderNo, orderNo)
                .in(ApCourseOrder::getStatus,
                        ApCourseOrder.Status.PENDING.getCode(),
                        ApCourseOrder.Status.PROCESSING.getCode())
                .set(ApCourseOrder::getStatus, ApCourseOrder.Status.PAID.getCode())
                .set(ApCourseOrder::getTradeNo, tradeNo)
                .set(ApCourseOrder::getPayTime, now)
                .set(ApCourseOrder::getUpdatedTime, now));
        if (updated != 1) {
            log.warn("订单非待支付态或已被处理，跳过幂等后续: orderNo={}, status={}", orderNo, order.getStatus());
            // 返回 false，供调用方判定是否触发「支付成功但订单已关闭」的退款兜底
            return false;
        }

        // 原子更新折扣码使用次数（防止并发超卖）
        if (order.getDiscountCode() != null && !order.getDiscountCode().isEmpty()) {
            boolean consumed = discountService.consumeDiscountCode(order.getDiscountCode());
            if (!consumed) {
                // 资损兜底：订单已 PAID 但折扣码已用完/无效——走「退款待重试」链路，
                // 由 AlipayServiceImpl.handleNotify 在本方法返回 false 后触发自动退款。
                // 不能只 log.warn，否则用户享受折扣但券未扣，同一张券可反复使用（P0-5）。
                log.warn("折扣码使用次数已达上限或无效，置为退款待重试: code={}, orderNo={}",
                        order.getDiscountCode(), orderNo);
                markRefundPending(orderNo, OrderService.REFUND_REASON_DISCOUNT_EXHAUSTED);
                return false;
            }
        }

        // 核销抽奖获得的通用5折券（reward 侧原子扣减持有数量，防止超核）
        if (order.getCouponItemCode() != null && !order.getCouponItemCode().isEmpty()) {
            try {
                ResponseResult consumeResult = rewardClient.consumeVirtualAsset(
                        order.getUserId().longValue(), order.getCouponItemCode(), 1);
                if (consumeResult == null || consumeResult.getCode() != 200) {
                    // 资损兜底：同上，5 折券远程核销失败（reward 不可用 / 数量不足），
                    // 把订单置为 refund_pending=1，AlipayServiceImpl 触发退款。
                    log.warn("5折券核销失败，置为退款待重试: orderNo={}, coupon={}, msg={}",
                            orderNo, order.getCouponItemCode(),
                            consumeResult != null ? consumeResult.getMessage() : "null");
                    markRefundPending(orderNo, OrderService.REFUND_REASON_COUPON_FAILED);
                    return false;
                }
            } catch (Exception e) {
                // 异常路径同样置为待退款（不能因 catch 吞掉就让用户免费使用 5 折券）
                log.error("5折券核销异常，置为退款待重试: orderNo={}, coupon={}",
                        orderNo, order.getCouponItemCode(), e);
                markRefundPending(orderNo, OrderService.REFUND_REASON_COUPON_FAILED);
                return false;
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

        // 4. 支付成功联动：加逐日等级经验 + 发"系统通知"站内信
        //    方案 B（Transactional Outbox）：主事务内只写事件，联动由 OutboxDispatcher 异步执行；
        //    主事务回滚事件一起回滚（原子），联动失败指数退避重试、超限死信告警 —— 不再 try-catch 丢事件。
        //    （资金类核销仍走上方同步 + 退款兜底语义，不进 Outbox —— 见铁律 4。）
        String payRewardPayload;
        try {
            payRewardPayload = PAY_REWARD_MAPPER.writeValueAsString(
                    PayRewardOutboxHandler.PayRewardPayload.of(order.getUserId().longValue(),
                            order.getCourseId(), order.getPaidAmount(), order.getOrderNo()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 序列化失败属于编程错误（字段固定）：显式失败让主事务回滚，绝不能让"订单 PAID 但联动事件丢失"
            throw new IllegalStateException("支付联动事件序列化失败: orderNo=" + orderNo, e);
        }
        outboxService.record(PayRewardOutboxHandler.EVENT_TYPE + ":" + orderNo,
                PayRewardOutboxHandler.EVENT_TYPE, payRewardPayload);

        log.info("订单支付成功: orderNo={}, tradeNo={}, userId={}, courseId={}",
                orderNo, tradeNo, order.getUserId(), order.getCourseId());
        return true;
    }

    @Override
    public void markRefunded(String orderNo, String refundTradeNo) {
        if (orderNo == null || orderNo.isEmpty()) {
            return;
        }
        // 条件更新抢占：仅 CANCELLED 状态可被置为 REFUNDED，防止并发/重复通知下重复标记退款
        Date now = new Date();
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ApCourseOrder>()
                .eq(ApCourseOrder::getOrderNo, orderNo)
                .eq(ApCourseOrder::getStatus, ApCourseOrder.Status.CANCELLED.getCode())
                .set(ApCourseOrder::getStatus, ApCourseOrder.Status.REFUNDED.getCode())
                .set(ApCourseOrder::getRefundTradeNo, refundTradeNo)
                .set(ApCourseOrder::getRefundTime, now)
                .set(ApCourseOrder::getRefundPending, 0)
                .set(ApCourseOrder::getRefundRetryCount, 0)
                .set(ApCourseOrder::getUpdatedTime, now));
        if (updated == 1) {
            log.info("订单退款完成: orderNo={}, refundTradeNo={}", orderNo, refundTradeNo);
        }
    }

    @Override
    public void markRefundPending(String orderNo) {
        // 兼容旧调用：AlipayServiceImpl.handleNotify 触发「订单已关闭 → 自动退款失败」时仍走此入口，
        // 原因用默认常量 ORDER_CLOSED 与 P0-5 新增 reason 入参保持统一。
        markRefundPending(orderNo, OrderService.REFUND_REASON_ORDER_CLOSED);
    }

    @Override
    public void markRefundPending(String orderNo, String reason) {
        if (orderNo == null || orderNo.isEmpty()) {
            return;
        }
        String safeReason = reason == null ? OrderService.REFUND_REASON_ORDER_CLOSED : reason;
        // 仅对仍处 PAID 或 CANCELLED 且尚未待重试的订单初始化（refund_pending=1, 重试计数=1）；
        // 已待重试则忽略，避免重复回调把计数灌高。
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<ApCourseOrder>()
                .eq(ApCourseOrder::getOrderNo, orderNo)
                .in(ApCourseOrder::getStatus,
                        ApCourseOrder.Status.CANCELLED.getCode(),
                        ApCourseOrder.Status.PAID.getCode())
                .ne(ApCourseOrder::getRefundPending, 1)
                .set(ApCourseOrder::getRefundPending, 1)
                .set(ApCourseOrder::getRefundPendingReason, safeReason)
                .set(ApCourseOrder::getRefundRetryCount, 1)
                .set(ApCourseOrder::getUpdatedTime, new Date()));
        if (updated == 1) {
            log.info("订单退款置为待重试: orderNo={}, reason={}", orderNo, safeReason);
        }
    }

    @Override
    public boolean markRefundRetryFailure(String orderNo, int maxRetries) {
        if (orderNo == null || orderNo.isEmpty()) {
            return false;
        }
        ApCourseOrder order = getByOrderNo(orderNo);
        if (order == null) {
            return false;
        }
        int current = order.getRefundRetryCount() == null ? 0 : order.getRefundRetryCount();
        int newCount = current + 1;
        // 达到/超过重试上限：停止自动重试并置为「已告警」，交由运维/人工介入
        boolean exhausted = maxRetries > 0 && newCount >= maxRetries;
        orderMapper.update(null, new LambdaUpdateWrapper<ApCourseOrder>()
                .eq(ApCourseOrder::getOrderNo, orderNo)
                .eq(ApCourseOrder::getStatus, ApCourseOrder.Status.CANCELLED.getCode())
                .set(ApCourseOrder::getRefundRetryCount, newCount)
                .set(ApCourseOrder::getRefundPending, exhausted ? 2 : 1)
                .set(ApCourseOrder::getUpdatedTime, new Date()));
        return exhausted;
    }

    @Override
    public List<ApCourseOrder> listPendingRefundOrders(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<ApCourseOrder> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseOrder::getStatus, ApCourseOrder.Status.CANCELLED.getCode())
                .eq(ApCourseOrder::getRefundPending, 1)
                .orderByAsc(ApCourseOrder::getUpdatedTime)
                .last("LIMIT " + safeLimit);
        List<ApCourseOrder> list = orderMapper.selectList(query);
        return list != null ? list : Collections.emptyList();
    }

    @Override
    public List<ApCourseOrder> listAlertedRefundOrders(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<ApCourseOrder> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseOrder::getRefundPending, 2)
                .orderByAsc(ApCourseOrder::getUpdatedTime)
                .last("LIMIT " + safeLimit);
        List<ApCourseOrder> list = orderMapper.selectList(query);
        return list != null ? list : Collections.emptyList();
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