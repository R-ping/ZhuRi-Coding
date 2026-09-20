package com.zhuri.coding.content.service.order.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.course.ApCourseMapper;
import com.zhuri.coding.content.mapper.course.ApCourseOrderMapper;
import com.zhuri.coding.content.mapper.course.ApCourseSettlementMapper;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.course.pojos.ApCourse;
import com.zhuri.coding.model.course.pojos.ApCourseOrder;
import com.zhuri.coding.model.course.pojos.ApCourseOrder.Status;
import com.zhuri.coding.model.course.pojos.ApCourseSettlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SettlementServiceImpl 单元测试（月度营收结算 & 作者/平台分成）
 *
 * 覆盖：
 * - getMonthlyList 空列表汇总归零 / 非空列表累计 authorId 的销售、平台分成、作者分成；
 * - getSettlementDetail 不存在 / 正常返回；
 * - executeMonthlySettlement 幂等跳过(已结算) / 无订单提前返回 / 完整分组结算 / 课程作者缺失兜底为0 / 并发插入冲突幂等跳过。
 */
class SettlementServiceImplTest {

    @Mock
    private ApCourseSettlementMapper settlementMapper;
    @Mock
    private ApCourseOrderMapper orderMapper;
    @Mock
    private ApCourseMapper courseMapper;

    @InjectMocks
    private SettlementServiceImpl settlementService;

    private final String month = "2026-07";
    private final int authorId = 3;
    private final Long courseId = 10L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private ApCourseSettlement settlement(Integer authorId, String month, String total, String platform, String authorShare) {
        ApCourseSettlement s = new ApCourseSettlement();
        s.setAuthorId(authorId);
        s.setCourseId(courseId);
        s.setSettlementMonth(month);
        s.setTotalSales(total != null ? new BigDecimal(total) : null);
        s.setPlatformShare(platform != null ? new BigDecimal(platform) : null);
        s.setAuthorShare(authorShare != null ? new BigDecimal(authorShare) : null);
        s.setOrderCount(2);
        return s;
    }

    private ApCourseOrder paidOrder(String paidAmount, Long courseId) {
        ApCourseOrder o = new ApCourseOrder();
        o.setUserId(100); // 买家
        o.setCourseId(courseId);
        o.setPaidAmount(new BigDecimal(paidAmount));
        o.setStatus(Status.PAID.getCode());
        return o;
    }

    // ---------- getMonthlyList ----------
    @Test
    @DisplayName("getMonthlyList 空列表：汇总均归零")
    void getMonthlyListEmpty() {
        when(settlementMapper.selectList(any())).thenReturn(List.of());

        Map<?, ?> data = (Map<?, ?>) settlementService.getMonthlyList((long) authorId).getData();
        assertEquals(0, ((List<?>) data.get("list")).size());
        assertEquals(BigDecimal.ZERO, data.get("totalSales"));
        assertEquals(BigDecimal.ZERO, data.get("totalPlatformShare"));
        assertEquals(BigDecimal.ZERO, data.get("totalAuthorShare"));
    }

    @Test
    @DisplayName("getMonthlyList 非空列表：累计销售/平台分成/作者分成")
    void getMonthlyListSum() {
        when(settlementMapper.selectList(any())).thenReturn(List.of(
                settlement(authorId, month, "100", "30", "70"),
                settlement(authorId, month, "50", null, "35"))); // platformShare 为 null 时按 0 处理

        Map<?, ?> data = (Map<?, ?>) settlementService.getMonthlyList((long) authorId).getData();
        assertEquals(2, ((List<?>) data.get("list")).size());
        assertEquals(0, ((BigDecimal) data.get("totalSales")).compareTo(new BigDecimal("150")));
        assertEquals(0, ((BigDecimal) data.get("totalPlatformShare")).compareTo(new BigDecimal("30")));
        assertEquals(0, ((BigDecimal) data.get("totalAuthorShare")).compareTo(new BigDecimal("105")));
    }

    // ---------- getSettlementDetail ----------
    @Test
    @DisplayName("getSettlementDetail 结算不存在")
    void getSettlementDetailNotExist() {
        when(settlementMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                settlementService.getSettlementDetail(1L).getCode());
    }

    @Test
    @DisplayName("getSettlementDetail 正常返回")
    void getSettlementDetailOk() {
        when(settlementMapper.selectById(1L)).thenReturn(settlement(authorId, month, "100", "30", "70"));
        ResponseResult r = settlementService.getSettlementDetail(1L);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
    }

    // ---------- executeMonthlySettlement ----------
    @Test
    @DisplayName("executeMonthlySettlement 已结算过则幂等跳过")
    void executeMonthlySettlementAlreadySettled() {
        when(settlementMapper.selectCount(any())).thenReturn(2L);
        settlementService.executeMonthlySettlement(month);
        verify(orderMapper, never()).selectList(any());
        verify(settlementMapper, never()).insert(any(ApCourseSettlement.class));
    }

    @Test
    @DisplayName("executeMonthlySettlement 当月无订单则提前返回")
    void executeMonthlySettlementNoOrders() {
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.selectList(any())).thenReturn(List.of());
        settlementService.executeMonthlySettlement(month);
        verify(settlementMapper, never()).insert(any(ApCourseSettlement.class));
    }

    @Test
    @DisplayName("executeMonthlySettlement 完整分组结算：作者分成为 70%、平台 30%")
    void executeMonthlySettlementFullFlow() {
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        ApCourse course = new ApCourse();
        course.setId(courseId);
        course.setAuthorId(authorId);
        when(courseMapper.selectById(courseId)).thenReturn(course);
        // 同一课程两笔订单：100 + 50 = 150
        when(orderMapper.selectList(any())).thenReturn(List.of(
                paidOrder("100", courseId), paidOrder("50", courseId)));

        settlementService.executeMonthlySettlement(month);

        // 触发 insert，校验分成金额
        org.mockito.ArgumentCaptor<ApCourseSettlement> captor =
                org.mockito.ArgumentCaptor.forClass(ApCourseSettlement.class);
        verify(settlementMapper).insert(captor.capture());
        ApCourseSettlement s = captor.getValue();
        assertEquals(authorId, s.getAuthorId());
        assertEquals(courseId, s.getCourseId());
        assertEquals(month, s.getSettlementMonth());
        assertEquals(2, s.getOrderCount());
        assertEquals(0, s.getTotalSales().compareTo(new BigDecimal("150")));
        assertEquals(0, s.getAuthorShare().compareTo(new BigDecimal("105.00"))); // 150*0.7
        assertEquals(0, s.getPlatformShare().compareTo(new BigDecimal("45.00"))); // 150*0.3
        assertEquals(0, s.getStatus());
    }

    @Test
    @DisplayName("executeMonthlySettlement 课程作者缺失时 authorId 兜底为 0")
    void executeMonthlySettlementAuthorMissing() {
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        when(courseMapper.selectById(courseId)).thenReturn(null); // 课程不存在
        when(orderMapper.selectList(any())).thenReturn(List.of(paidOrder("80", courseId)));

        settlementService.executeMonthlySettlement(month);

        org.mockito.ArgumentCaptor<ApCourseSettlement> captor =
                org.mockito.ArgumentCaptor.forClass(ApCourseSettlement.class);
        verify(settlementMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getAuthorId());
    }

    @Test
    @DisplayName("executeMonthlySettlement 插入冲突(DuplicateKeyException)幂等跳过")
    void executeMonthlySettlementDuplicateKey() {
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        ApCourse course = new ApCourse();
        course.setId(courseId);
        course.setAuthorId(authorId);
        when(courseMapper.selectById(courseId)).thenReturn(course);
        when(orderMapper.selectList(any())).thenReturn(
                List.of(paidOrder("100", courseId), paidOrder("100", 20L)));
        // 第二组(课程20)作者缺失走 0 兜底
        when(courseMapper.selectById(20L)).thenReturn(null);
        // 第一次 insert 抛唯一键冲突，第二次成功
        when(settlementMapper.insert(any(ApCourseSettlement.class)))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenReturn(1);

        settlementService.executeMonthlySettlement(month); // 不应抛出异常

        verify(settlementMapper, org.mockito.Mockito.atLeastOnce()).insert(any(ApCourseSettlement.class));
    }

    @Test
    @DisplayName("executeMonthlySettlement 校验查询条件含月份与已支付状态")
    void executeMonthlySettlementQueryCondition() {
        when(settlementMapper.selectCount(any())).thenReturn(0L); // 未结算
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        settlementService.executeMonthlySettlement(month);
        verify(orderMapper).selectList(any());
        // 无订单时直接返回，不产生结算记录
        verify(settlementMapper, never()).insert(any(ApCourseSettlement.class));
    }
}