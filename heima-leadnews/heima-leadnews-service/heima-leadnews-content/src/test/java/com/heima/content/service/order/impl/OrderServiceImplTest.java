package com.heima.content.service.order.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.mapper.course.ApCourseOrderMapper;
import com.heima.content.mapper.course.ApUserCourseMapper;
import com.heima.content.service.order.DiscountService;
import com.heima.content.service.payment.PaymentRewardService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderServiceImpl 单元测试（课程订单核心流程）
 *
 * @Service 依赖多个 mapper 与 DiscountService、PaymentRewardService，均 @Mock 注入。
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
    private ApUserCourseMapper userCourseMapper;
    @Mock
    private DiscountService discountService;
    @Mock
    private PaymentRewardService paymentRewardService;

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
                orderService.createOrder(null, null, userId, "OTHER").getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                orderService.createOrder(courseId, null, null, "OTHER").getCode());
    }

    @Test
    @DisplayName("createOrder 课程不存在或已删除")
    void createOrderCourseNotExist() {
        when(courseMapper.selectById(courseId)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                orderService.createOrder(courseId, null, userId, "OTHER").getCode());
    }

    @Test
    @DisplayName("createOrder 折扣码无效")
    void createOrderInvalidDiscount() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        when(discountService.validateDiscount("BAD", courseId)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                orderService.createOrder(courseId, "BAD", userId, "OTHER").getCode());
    }

    @Test
    @DisplayName("createOrder 固定金额折扣")
    void createOrderFixedDiscount() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        ApCourseDiscount d = new ApCourseDiscount();
        d.setDiscountType(ApCourseDiscount.DiscountType.FIXED.getCode());
        d.setDiscountValue(new BigDecimal("30"));
        when(discountService.validateDiscount("FIX", courseId)).thenReturn(d);

        ApCourseOrder o = (ApCourseOrder) orderService.createOrder(courseId, "FIX", userId, "ZHIFUBAO").getData();
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

        ApCourseOrder o = (ApCourseOrder) orderService.createOrder(courseId, "P80", userId, null).getData();
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

        ApCourseOrder o = (ApCourseOrder) orderService.createOrder(courseId, "BIG", userId, "OTHER").getData();
        assertEquals(BigDecimal.ZERO, o.getPaidAmount());
    }

    @Test
    @DisplayName("createOrder 无折扣码常规下单")
    void createOrderNoDiscount() {
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        ApCourseOrder o = (ApCourseOrder) orderService.createOrder(courseId, null, userId, "OTHER").getData();
        assertEquals(BigDecimal.ZERO, o.getDiscountAmount());
        assertEquals(new BigDecimal("100"), o.getPaidAmount());
        assertEquals("", o.getDiscountCode());
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
        verify(orderMapper, never()).updateById(any(ApCourseOrder.class));
    }

    @Test
    @DisplayName("handlePaySuccess 状态非待支付时丢弃")
    void handlePaySuccessWrongStatus() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PAID.getCode(), ""));
        orderService.handlePaySuccess(orderNo, "TN1");
        verify(orderMapper, never()).updateById(any(ApCourseOrder.class));
    }

    @Test
    @DisplayName("handlePaySuccess 完整成功链路：折扣原子消费+课程计数+新购权限+联动")
    void handlePaySuccessFullFlow() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), "CODE"));
        when(discountService.consumeDiscountCode("CODE")).thenReturn(true);
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        when(userCourseMapper.selectOne(any())).thenReturn(null);

        orderService.handlePaySuccess(orderNo, "TN1");

        verify(orderMapper).updateById(any(ApCourseOrder.class));
        verify(discountService).consumeDiscountCode("CODE");
        verify(courseMapper).updateById(any(ApCourse.class));
        verify(userCourseMapper).insert(any(ApUserCourse.class));
        verify(paymentRewardService).onCoursePurchaseSuccess(userId, courseId, new BigDecimal("100"), orderNo);
    }

    @Test
    @DisplayName("handlePaySuccess 折扣为空则跳过消费、续购更新、null 营收初始化")
    void handlePaySuccessExistingUserAndNoDiscount() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), ""));
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
    @DisplayName("handlePaySuccess 联动异常隔离不影响支付主流程")
    void handlePaySuccessRewardDown() {
        when(orderMapper.selectOne(any())).thenReturn(order(ApCourseOrder.Status.PENDING.getCode(), ""));
        when(courseMapper.selectById(courseId)).thenReturn(course(new BigDecimal("100"), 0));
        when(userCourseMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("reward down"))
                .when(paymentRewardService).onCoursePurchaseSuccess(any(), any(), any(), any());

        orderService.handlePaySuccess(orderNo, "TN1");

        verify(orderMapper).updateById(any(ApCourseOrder.class)); // 主流程仍完成
    }

    // ---------- getByOrderNo ----------
    @Test
    @DisplayName("getByOrderNo 未命中返回 null")
    void getByOrderNoNotFound() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertNull(orderService.getByOrderNo(orderNo));
    }
}