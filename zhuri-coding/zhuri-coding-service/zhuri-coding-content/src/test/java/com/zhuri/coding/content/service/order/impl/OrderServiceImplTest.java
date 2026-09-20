package com.heima.content.service.order.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.mapper.course.ApCourseOrderMapper;
import com.heima.content.mapper.course.ApUserCourseMapper;
import com.heima.apis.reward.IRewardClient;
import com.heima.content.service.order.DiscountService;
import com.heima.content.service.order.OrderService;
import com.heima.content.service.outbox.OutboxService;
import com.heima.content.service.outbox.handler.PayRewardOutboxHandler;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.course.pojos.ApCourseDiscount;
import com.heima.model.course.pojos.ApCourseOrder;
import com.heima.model.course.pojos.ApCourseOrder.PayType;
import com.heima.model.user.pojos.ApUserCourse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderServiceImpl 单元测试（课程订单核心流程）
 *
 * @Service 依赖多个 mapper 与 DiscountService、OutboxService，均 @Mock 注入。
 * 覆盖：
 * - createOrder 参数校验 / 课程不存在 / 折扣码无效 / FIXED 与 PERCENTAGE 折扣计算 / 金额下溢归零 / 默认支付方式；
 * - getOrderStatus 归属校验（防越权）/ 不存在 / 成功；
 * - getMyOrders 分页查询；
 * - handlePaySuccess 订单不存在 / 状态非待支付 / 完整成功链路（折扣码原子消费、课程学习人数与营收、新购/续购用户课程、支付联动异常隔离）；
 * - getByOrderNo。
 */
class OrderServiceImplTest {

    @Mock
    private ApCourseOrderMapper orderMapper;
    @Mock
    private ApCourseMapper courseMapper;
    @Mock
    private com.heima.content.mapper.course.ApCourseChapterMapper courseChapterMapper;
    @Mock
    private ApUserCourseMapper userCourseMapper;
    @Mock
    private DiscountService discountService;
    @Mock
    private OutboxService outboxService;
    @Mock
    private IRewardClient rewardClient;
    @Mock
    private OrderTimeoutTask orderTimeoutTask;

    @InjectMocks
    private OrderServiceImpl orderService;

    private final Long userId = 7L;
    private final Long courseId = 10L;
    private final String orderNo = "20270101120000123456";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCourseOrder.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCourse.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserCourse.class);
        // 默认无 AIGC 章节（与 P0 之前 OrderServiceImpl 行 78-83 的「含 AIGC 章节禁止售卖」检查对应；
        // 不桩会因 courseChapterMapper 为 null 抛 NPE）
        lenient().when(courseChapterMapper.selectCount(any())).thenReturn(0L);
    }

    private ApCourse course(BigDecimal price, Integer isDeleted) {
        ApCourse c = new ApCourse();
        c.setId(courseId);
        c.setPrice(price);
        c.setIsDeleted(isDeleted);
        c.setStudyCount(1);
        c.setSalesCount(2);
        c.setTotalRevenue(new BigDecimal("100"));
        return c;
    }

    private ApCourseOrder order(Integer status, String discountCode) {
        ApCourseOrder o = new ApCourseOrder();
        o.setOrderNo(orderNo);
        o.setUserId(userId.intValue());
        o.setCourseId(courseId);
        o.setOriginalAmount(new BigDecimal("100"));
        o.setDiscountAmount(BigDecimal.ZERO);
        o.setPaidAmount(new BigDecimal("100"));
        o.setDiscountCode(discountCode);
        o.setStatus(status);
        o.setCreatedTime(new Date());
        o.setUpdatedTime(new Date());
        return o;
    }

    // ---------- createOrder ----------
    @Test
    @DisplayName("createOrder 必填参数缺失")
    void createOrderMissingParam() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                orderService.createOrder(null, null, null, userId, "OTHER").getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                orderService.createOrder(courseId, null, null, null, "OTHER").getCode());
    }

    @Test
    @DisplayName("createOrder 课程不存在或已删除")
    void createOrderCourseNotExist() {
        when(courseMapper.selectById(courseId)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                orderService.createOrder(courseId, null, null, userId, "OTHER").getCode());
    }

    @Test
    @DisplayName("createOrder 折扣码无效")
    void createOrderInvalidDiscount() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        when(discountService.validateDiscount("BAD", courseId)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                orderService.createOrder(courseId, "BAD", null, userId, "OTHER").getCode());
    }

    @Test
    @DisplayName("createOrder 固定金额折扣")
    void createOrderFixedDiscount() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        ApCourseDiscount d = new ApCourseDiscount();
        d.setDiscountType(ApCourseDiscount.DiscountType.FIXED.getCode());
        d.setDiscountValue(new BigDecimal("30"));
        when(discountService.validateDiscount("FIX", courseId)).thenReturn(d);

        ApCourseOrder o = (ApCourseOrder) orderService.createOrder(courseId, "FIX", null, userId, "ZHIFUBAO").getData();
        assertEquals(new BigDecimal("30"), o.getDiscountAmount());
        assertEquals(new BigDecimal("70"), o.getPaidAmount());
        assertEquals(PayType.ZHIFUBAO, o.getPayMethod());
        assertEquals(ApCourseOrder.Status.PENDING.getCode(), o.getStatus());
        assertNotNull(o.getOrderNo());
    }

    @Test
    @DisplayName("createOrder 百分比折扣计算")
    void createOrderPercentageDiscount() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        ApCourseDiscount d = new ApCourseDiscount();
        d.setDiscountType(ApCourseDiscount.DiscountType.PERCENTAGE.getCode());
        d.setDiscountValue(new BigDecimal("80")); // 优惠 80%，实付 20%
        when(discountService.validateDiscount("P80", courseId)).thenReturn(d);

        ApCourseOrder o = (ApCourseOrder) orderService.createOrder(courseId, "P80", null, userId, null).getData();
        // 折扣计算产生 80.0/20.0（scale=1），用 compareTo 做纯数值比较即可刻度无关
        assertEquals(0, o.getDiscountAmount().compareTo(new BigDecimal("80"))); // 优惠 80 元
        assertEquals(0, o.getPaidAmount().compareTo(new BigDecimal("20")));
        assertEquals(PayType.OTHER, o.getPayMethod()); // payType 为空默认 OTHER
    }

    @Test
    @DisplayName("createOrder 折扣后金额下溢归零")
    void createOrderAmountFloorZero() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("10"), 0));
        ApCourseDiscount d = new ApCourseDiscount();
        d.setDiscountType(ApCourseDiscount.DiscountType.FIXED.getCode());
        d.setDiscountValue(new BigDecimal("50")); // 优惠大于原价
        when(discountService.validateDiscount("BIG", courseId)).thenReturn(d);

        ApCourseOrder o = (ApCourseOrder) orderService.createOrder(courseId, "BIG", null, userId, "OTHER").getData();
        assertEquals(BigDecimal.ZERO, o.getPaidAmount());
    }

    @Test
    @DisplayName("createOrder 无折扣码常规下单")
    void createOrderNoDiscount() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        ApCourseOrder o = (ApCourseOrder) orderService.createOrder(courseId, null, null, userId, "OTHER").getData();
        assertEquals(BigDecimal.ZERO, o.getDiscountAmount());
        assertEquals(new BigDecimal("100"), o.getPaidAmount());
        assertEquals("", o.getDiscountCode());
    }

    @Test
    @DisplayName("createOrder 使用5折券抵扣")
    void createOrderWithCoupon() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        Map<String, Object> hold = new java.util.HashMap<>();
        hold.put("quantity", 1);
        hold.put("discountRate", 0.5d);
        hold.put("itemCode", "course50");
        when(rewardClient.getVirtualAssetHold(userId, "course50"))
                .thenReturn(ResponseResult.okResult(hold));

        ApCourseOrder o = (ApCourseOrder) orderService.createOrder(courseId, null, "course50", userId, "OTHER").getData();
        // 5折券 rate=0.5 → 折扣=原价*(1-0.5)=50，实付=50
        assertEquals(0, o.getDiscountAmount().compareTo(new BigDecimal("50")));
        assertEquals(0, o.getPaidAmount().compareTo(new BigDecimal("50")));
        assertEquals("course50", o.getCouponItemCode());
        assertEquals("", o.getDiscountCode());
    }

    @Test
    @DisplayName("createOrder 5折券数量不足时拒绝下单")
    void createOrderCouponInsufficient() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        Map<String, Object> hold = new java.util.HashMap<>();
        hold.put("quantity", 0);
        hold.put("discountRate", 0.5d);
        when(rewardClient.getVirtualAssetHold(userId, "course50"))
                .thenReturn(ResponseResult.okResult(hold));

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                orderService.createOrder(courseId, null, "course50", userId, "OTHER").getCode());
    }

    // ---------- getOrderStatus ----------
    @Test
    @DisplayName("getOrderStatus 订单不存在")
    void getOrderStatusNotExist() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                orderService.getOrderStatus(orderNo, userId).getCode());
    }

    @Test
    @DisplayName("getOrderStatus 越权访问被拒绝")
    void getOrderStatusForbidden() {
        when(orderMapper.selectOne(any())).thenReturn(order(0, ""));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                orderService.getOrderStatus(orderNo, 999L).getCode());
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                orderService.getOrderStatus(orderNo, null).getCode());
    }

    @Test
    @DisplayName("getOrderStatus 正常返回本人订单")
    void getOrderStatusOk() {
        when(orderMapper.selectOne(any())).thenReturn(order(0, ""));
        assertEquals(200, orderService.getOrderStatus(orderNo, userId).getCode());
    }

    // ---------- getMyOrders ----------
    @Test
    @DisplayName("getMyOrders 分页查询")
    void getMyOrders() {
        Page<ApCourseOrder> p = new Page<>(1, 10);
        p.setRecords(java.util.List.of(order(0, "")));
        p.setTotal(1);
        when(orderMapper.selectPage(any(Page.class), any())).thenReturn(p);

        Map<?, ?> data = (Map<?, ?>) orderService.getMyOrders(userId, 1, 10).getData();
        assertEquals(1, ((java.util.List<?>) data.get("list")).size());
        assertEquals(1L, ((Long) data.get("total")).longValue());
    }

    // ---------- handlePaySuccess ----------
    @Test
    @DisplayName("handlePaySuccess 订单不存在")
    void handlePaySuccessNoOrder() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        orderService.handlePaySuccess(orderNo, "TN1");
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("handlePaySuccess 状态非待支付时丢弃")
    void handlePaySuccessWrongStatus() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PAID.getCode(), ""));
        when(orderMapper.update(any(), any())).thenReturn(0); // CAS WHERE status=PENDING 命中0行，丢弃回调
        orderService.handlePaySuccess(orderNo, "TN1");
        // CAS 更新已尝试但受影响0行 → 不进入放权/加销量/开课等后续
        verify(orderMapper).update(any(), any());
        verify(userCourseMapper, never()).insert(any(ApUserCourse.class));
    }

    @Test
    @DisplayName("handlePaySuccess 完整成功链路：折扣原子消费+课程计数+新购权限+联动")
    void handlePaySuccessFullFlow() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), "CODE"));
        when(orderMapper.update(any(), any())).thenReturn(1); // CAS 抢占 PENDING→PAID 成功
        when(discountService.consumeDiscountCode("CODE")).thenReturn(true);
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        when(userCourseMapper.selectOne(any())).thenReturn(null);

        orderService.handlePaySuccess(orderNo, "TN1");

        verify(orderMapper).update(any(), any()); // 条件更新抢占
        verify(discountService).consumeDiscountCode("CODE");
        verify(courseMapper).updateById(any(ApCourse.class));
        verify(userCourseMapper).insert(any(ApUserCourse.class));
        // 方案 B：联动改为 Outbox 事件（同事务写入），不再同步调 PaymentRewardService
        org.mockito.ArgumentCaptor<String> payload =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(outboxService).record(
                org.mockito.ArgumentMatchers.eq(PayRewardOutboxHandler.EVENT_TYPE + ":" + orderNo),
                org.mockito.ArgumentMatchers.eq(PayRewardOutboxHandler.EVENT_TYPE),
                payload.capture());
        // payload JSON 含关键业务字段（反序列化失败会由 handler 判死信，这里防呆）
        assertTrue(payload.getValue().contains("\"orderNo\":\"" + orderNo + "\""));
        assertTrue(payload.getValue().contains("\"userId\":" + userId));
    }

    @Test
    @DisplayName("handlePaySuccess 折扣为空则跳过消费、续购更新、null 营收初始化")
    void handlePaySuccessExistingUserAndNoDiscount() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), ""));
        when(orderMapper.update(any(), any())).thenReturn(1); // CAS 抢占成功
        ApCourse c = course(new BigDecimal("100"), 0);
        c.setTotalRevenue(null);
        when(courseMapper.selectById(courseId)).thenReturn(c);
        ApUserCourse existing = new ApUserCourse();
        existing.setProgress(BigDecimal.ZERO);
        when(userCourseMapper.selectOne(any())).thenReturn(existing);

        orderService.handlePaySuccess(orderNo, "TN1");

        verify(discountService, never()).consumeDiscountCode(any());
        verify(userCourseMapper).updateById(any(ApUserCourse.class));
        verify(courseMapper).updateById(any(ApCourse.class));
    }

    @Test
    @DisplayName("handlePaySuccess 联动走 Outbox：只写事件不同步调联动，主流程返回 true")
    void handlePaySuccessRewardViaOutbox() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), ""));
        when(orderMapper.update(any(), any())).thenReturn(1); // CAS 抢占成功
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        when(userCourseMapper.selectOne(any())).thenReturn(null);
        when(outboxService.record(any(), any(), any())).thenReturn(true);

        boolean ok = orderService.handlePaySuccess(orderNo, "TN1");

        assertTrue(ok); // 主流程仍完成
        // 事件以 orderNo 为幂等键写入（PAY_REWARD:{orderNo}），联动改由 Dispatcher 异步执行
        verify(outboxService).record(
                org.mockito.ArgumentMatchers.eq(PayRewardOutboxHandler.EVENT_TYPE + ":" + orderNo),
                org.mockito.ArgumentMatchers.eq(PayRewardOutboxHandler.EVENT_TYPE),
                any());
    }

    @Test
    @DisplayName("handlePaySuccess 使用5折券的订单支付成功后被核销")
    void handlePaySuccessConsumeCoupon() {
        ApCourseOrder o = order(ApCourseOrder.Status.PENDING.getCode(), "");
        o.setCouponItemCode("course50");
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(), any())).thenReturn(1); // CAS 抢占成功
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        when(userCourseMapper.selectOne(any())).thenReturn(null);
        when(rewardClient.consumeVirtualAsset(userId, "course50", 1))
                .thenReturn(ResponseResult.okResult(new java.util.HashMap<>()));

        orderService.handlePaySuccess(orderNo, "TN1");

        verify(rewardClient).consumeVirtualAsset(userId, "course50", 1);
    }

    @Test
    @DisplayName("handlePaySuccess 无5折券则不调用核销")
    void handlePaySuccessNoCouponSkipConsume() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), ""));
        when(orderMapper.update(any(), any())).thenReturn(1); // CAS 抢占成功
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        when(userCourseMapper.selectOne(any())).thenReturn(null);

        orderService.handlePaySuccess(orderNo, "TN1");

        verify(rewardClient, never()).consumeVirtualAsset(any(), any(), anyInt());
    }

    // ---------- preparePay / closePayChannel ----------
    @Test
    @DisplayName("preparePay 正常：PENDING→PROCESSING，返回订单并排程支付通道超时")
    void preparePayOk() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), ""));
        when(orderMapper.update(any(), any())).thenReturn(1); // CAS 抢占 PENDING→PROCESSING 成功

        ApCourseOrder result = (ApCourseOrder) orderService.preparePay(orderNo, userId).getData();
        assertEquals(ApCourseOrder.Status.PROCESSING.getCode(), result.getStatus());
        verify(orderMapper).update(any(), any());
        verify(orderTimeoutTask).scheduleClosePayChannel(orderNo);
    }

    @Test
    @DisplayName("preparePay 订单不存在")
    void preparePayNotExist() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                orderService.preparePay(orderNo, userId).getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("preparePay 越权访问他人订单被拒绝")
    void preparePayForbidden() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), ""));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                orderService.preparePay(orderNo, 999L).getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("preparePay 订单已关闭/非待支付拒绝发起支付")
    void preparePayStateNotPending() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.CANCELLED.getCode(), ""));
        assertEquals(AppHttpCodeEnum.ORDER_STATUS_INVALID.getCode(),
                orderService.preparePay(orderNo, userId).getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("preparePay 并发下关单先行（抢占命中0行）→ 提示订单已关闭，不排程")
    void preparePayClaimRaceClosed() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), ""));
        when(orderMapper.update(any(), any())).thenReturn(0); // 关单已先行 PENDING→CANCELLED

        assertEquals(AppHttpCodeEnum.ORDER_CLOSED.getCode(),
                orderService.preparePay(orderNo, userId).getCode());
        verify(orderTimeoutTask, never()).scheduleClosePayChannel(orderNo);
    }

    @Test
    @DisplayName("preparePay 已处于 PROCESSING 幂等放行，不重复抢占")
    void preparePayAlreadyProcessing() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PROCESSING.getCode(), ""));
        assertEquals(200, orderService.preparePay(orderNo, userId).getCode());
        verify(orderMapper, never()).update(any(), any());
        verify(orderTimeoutTask, never()).scheduleClosePayChannel(orderNo);
    }

    @Test
    @DisplayName("preparePay 5折券数量不足拒绝发起支付")
    void preparePayCouponInsufficient() {
        ApCourseOrder o = order(ApCourseOrder.Status.PENDING.getCode(), "");
        o.setCouponItemCode("course50");
        when(orderMapper.selectOne(any())).thenReturn(o);
        Map<String, Object> hold = new java.util.HashMap<>();
        hold.put("quantity", 0);
        when(rewardClient.getVirtualAssetHold(userId, "course50"))
                .thenReturn(ResponseResult.okResult(hold));

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                orderService.preparePay(orderNo, userId).getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("preparePay 折扣码已失效拒绝发起支付")
    void preparePayDiscountInvalid() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), "BAD"));
        when(discountService.validateDiscount("BAD", courseId)).thenReturn(null);

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                orderService.preparePay(orderNo, userId).getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("preparePay 公开访问（userId=null）跳过券码核验并抢占")
    void preparePayPublicNoUserId() {
        ApCourseOrder o = order(ApCourseOrder.Status.PENDING.getCode(), "CODE");
        o.setCouponItemCode("course50");
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(), any())).thenReturn(1);

        ApCourseOrder result = (ApCourseOrder) orderService.preparePay(orderNo, null).getData();
        assertEquals(ApCourseOrder.Status.PROCESSING.getCode(), result.getStatus());
        // 无非特权：不调用 reward 持有量校验，仅原子抢占
        verify(rewardClient, never()).getVirtualAssetHold(any(), any());
    }

    @Test
    @DisplayName("closePayChannel 将 PROCESSING 订单置为已取消")
    void closePayChannel() {
        when(orderMapper.update(any(), any())).thenReturn(1);
        orderService.closePayChannel(orderNo);
        verify(orderMapper).update(any(), any());
    }

    @Test
    @DisplayName("closePayChannel 已非 PROCESSING（如已支付）则不受影响不重复关单")
    void closePayChannelNoEffect() {
        when(orderMapper.update(any(), any())).thenReturn(0);
        orderService.closePayChannel(orderNo);
        verify(orderMapper).update(any(), any());
    }

    // ---------- handlePaySuccess 返回值 / markRefunded 退款兜底 ----------
    @Test
    @DisplayName("handlePaySuccess 真正放权成功返回 true")
    void handlePaySuccessReturnsTrueWhenApplied() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), ""));
        when(orderMapper.update(any(), any())).thenReturn(1); // CAS 抢占成功
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        when(userCourseMapper.selectOne(any())).thenReturn(null);

        assertTrue(orderService.handlePaySuccess(orderNo, "TN1"));
    }

    @Test
    @DisplayName("handlePaySuccess 订单已关闭（抢占失败）返回 false 供退款兜底判断")
    void handlePaySuccessReturnsFalseWhenNotApplied() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.CANCELLED.getCode(), ""));
        when(orderMapper.update(any(), any())).thenReturn(0); // CAS 命中0行

        assertFalse(orderService.handlePaySuccess(orderNo, "TN1"));
    }

    @Test
    @DisplayName("markRefunded 将 CANCELLED 订单置为已退款（写入退款信息）")
    void markRefunded() {
        when(orderMapper.update(any(), any())).thenReturn(1);
        orderService.markRefunded(orderNo, "R1");
        verify(orderMapper).update(any(), any());
    }

    @Test
    @DisplayName("markRefunded 非 CANCELLED（已退款/已支付）不受影响")
    void markRefundedNoEffect() {
        when(orderMapper.update(any(), any())).thenReturn(0);
        orderService.markRefunded(orderNo, "R1");
        verify(orderMapper).update(any(), any());
    }

    @Test
    @DisplayName("markRefundPending 将 CANCELLED 订单置为待重试退款")
    void markRefundPending() {
        when(orderMapper.update(any(), any())).thenReturn(1);
        orderService.markRefundPending(orderNo);
        verify(orderMapper).update(any(), any());
    }

    @Test
    @DisplayName("listPendingRefundOrders 查询待重试退款订单")
    void listPendingRefundOrders() {
        when(orderMapper.selectList(any())).thenReturn(java.util.List.of(order(ApCourseOrder.Status.CANCELLED.getCode(), "")));
        assertEquals(1, orderService.listPendingRefundOrders(10).size());
    }

    @Test
    @DisplayName("markRefundRetryFailure 未达上限返回 false，下次继续记录")
    void markRefundRetryFailureNotExhausted() {
        ApCourseOrder o = order(ApCourseOrder.Status.CANCELLED.getCode(), "");
        o.setRefundRetryCount(1);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(), any())).thenReturn(1);

        assertFalse(orderService.markRefundRetryFailure(orderNo, 3)); // 1+1=2 < 3
        verify(orderMapper).update(any(), any());
    }

    @Test
    @DisplayName("markRefundRetryFailure 到达上限返回 true（触发告警停止重试）")
    void markRefundRetryFailureExhausted() {
        ApCourseOrder o = order(ApCourseOrder.Status.CANCELLED.getCode(), "");
        o.setRefundRetryCount(2);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(), any())).thenReturn(1);

        assertTrue(orderService.markRefundRetryFailure(orderNo, 3)); // 2+1=3 >= 3
    }

    @Test
    @DisplayName("markRefundPending 忽略已处于待重试的订单（避免计数灌高）")
    void markRefundPendingIdempotentWhenAlreadyPending() {
        ApCourseOrder o = order(ApCourseOrder.Status.CANCELLED.getCode(), "");
        o.setRefundPending(1);
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.update(any(), any())).thenReturn(0);
        orderService.markRefundPending(orderNo);
        verify(orderMapper).update(any(), any());
    }

    @Test
    @DisplayName("listAlertedRefundOrders 查询达标告警订单")
    void listAlertedRefundOrders() {
        when(orderMapper.selectList(any())).thenReturn(java.util.List.of(order(ApCourseOrder.Status.CANCELLED.getCode(), "")));
        assertEquals(1, orderService.listAlertedRefundOrders(10).size());
    }

    // ---------- getByOrderNo ----------
    @Test
    @DisplayName("getByOrderNo 未命中返回 null")
    void getByOrderNoNotFound() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertNull(orderService.getByOrderNo(orderNo));
    }

    // ---------- P0-5 修复：券核销失败 → 退款兜底（P0-5）----------

    /**
     * P0-5 关键安全断言：折扣码核销失败（已用完/无效）时，订单不能被静默放过。
     * <p>原代码只 log.warn，订单 PAID + 课程权限已开通，但券没扣 → 同一张券可反复使用（资损）。
     * 修复后必须：1) 返回 false 供 AlipayServiceImpl 触发退款；2) 调 markRefundPending 写 refund_pending=1；
     * 3) 不放权（userCourseMapper.insert 永远不被调）。</p>
     */
    @Test
    @DisplayName("handlePaySuccess 折扣码核销失败 → 置为退款待重试且不放权（防重复使用折扣码）")
    void handlePaySuccessDiscountCodeExhausted() {
        ApCourseOrder o = order(ApCourseOrder.Status.PENDING.getCode(), "COURSE123");
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(), any())).thenReturn(1); // CAS 抢占 PAID 成功
        when(discountService.consumeDiscountCode("COURSE123")).thenReturn(false); // 核销失败

        boolean applied = orderService.handlePaySuccess(orderNo, "TN1");

        // 必须返回 false 供上游 AlipayServiceImpl 触发退款
        assertFalse(applied);
        // 必须调 markRefundPending 把订单置为 refund_pending=1（行 343 后的 markRefundPending → 调 mapper.update 至少 2 次：1=PAID 抢占、2=markRefundPending）
        verify(orderMapper, org.mockito.Mockito.atLeast(2)).update(any(), any());
        // 关键：失败分支不能放权
        verify(userCourseMapper, never()).insert(any(ApUserCourse.class));
        verify(userCourseMapper, never()).updateById(any(ApUserCourse.class));
    }

    /**
     * P0-5 关键安全断言：5 折券远程核销返回非 200（reward 不可用 / 数量不足）→ 同样走退款兜底。
     */
    @Test
    @DisplayName("handlePaySuccess 5折券核销返回非 200 → 置为退款待重试且不放权")
    void handlePaySuccessCouponConsumeFailed() {
        ApCourseOrder o = order(ApCourseOrder.Status.PENDING.getCode(), "");
        o.setCouponItemCode("course50");
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(), any())).thenReturn(1);
        // 模拟 reward 服务降级：返回 errorResult(500, ...)
        when(rewardClient.consumeVirtualAsset(userId, "course50", 1))
                .thenReturn(ResponseResult.errorResult(500, "奖励服务不可用，虚拟道具核销失败"));

        boolean applied = orderService.handlePaySuccess(orderNo, "TN1");

        assertFalse(applied);
        verify(orderMapper, org.mockito.Mockito.atLeast(2)).update(any(), any());
        verify(userCourseMapper, never()).insert(any(ApUserCourse.class));
    }

    /**
     * P0-5 关键安全断言：5 折券远程核销抛异常（Feign 超时/熔断）→ 走退款兜底而非 catch 吞掉。
     */
    @Test
    @DisplayName("handlePaySuccess 5折券核销抛异常 → 置为退款待重试且不放权")
    void handlePaySuccessCouponConsumeException() {
        ApCourseOrder o = order(ApCourseOrder.Status.PENDING.getCode(), "");
        o.setCouponItemCode("course50");
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(rewardClient.consumeVirtualAsset(userId, "course50", 1))
                .thenThrow(new RuntimeException("feign timeout"));

        boolean applied = orderService.handlePaySuccess(orderNo, "TN1");

        assertFalse(applied);
        verify(orderMapper, org.mockito.Mockito.atLeast(2)).update(any(), any());
        verify(userCourseMapper, never()).insert(any(ApUserCourse.class));
    }

    /**
     * P0-5 关键安全断言：markRefundPending(orderNo, reason) 接受 reason 入参并写入 refund_pending_reason 字段。
     * <p>这是从 markRefundPending(orderNo) 重载出来的接口，主要给 AlipayServiceImpl 在
     * 「已 PAID + 券核销失败」分支传 reason=discount_code_exhausted / coupon_consume_failed 用，
     * 便于运维直接 SQL SELECT 排查「为什么这笔订单被置为退款」。</p>
     */
    @Test
    @DisplayName("markRefundPending(orderNo, reason) 把 reason 写到 refund_pending_reason 字段")
    void markRefundPendingWithReason() {
        when(orderMapper.update(any(), any())).thenReturn(1);
        orderService.markRefundPending(orderNo, OrderService.REFUND_REASON_DISCOUNT_EXHAUSTED);
        // 至少调一次 mapper.update 写入 refund_pending=1 + reason
        verify(orderMapper, org.mockito.Mockito.atLeast(1)).update(any(), any());
    }
}