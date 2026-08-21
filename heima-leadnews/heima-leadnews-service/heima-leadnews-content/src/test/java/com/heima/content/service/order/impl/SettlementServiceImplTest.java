package com.heima.content.service.order.impl;

import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.mapper.course.ApCourseOrderMapper;
import com.heima.content.mapper.course.ApCourseSettlementMapper;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.course.pojos.ApCourseOrder;
import com.heima.model.course.pojos.ApCourseSettlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SettlementServiceImpl 回归测试（重点关注 A7 月度结算并发幂等）
 *
 * 目标：
 * 1. 同一月份已结算 -> 直接跳过（防重复结算）；
 * 2. 后续 DuplicateKeyException（并发下唯一约束冲突）-> 幂等跳过不向外抛；
 * 3. 作者 ID 来自课程表真实作者，而非订单买家；
 * 4. 正常生成结算记录时作者/平台分成比例正确（0.7 / 0.3）。
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

    private static final String MONTH = "2026-07";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("A7 - 月份已结算则跳过，不查询订单也不落库(防重复结算)")
    void testExecuteMonthlySettlementSkipWhenAlreadySettled() {
        when(settlementMapper.selectCount(any())).thenReturn(1L);

        settlementService.executeMonthlySettlement(MONTH);

        verify(orderMapper, never()).selectList(any());
        verify(settlementMapper, never()).insert(any(ApCourseSettlement.class));
    }

    @Test
    @DisplayName("A7 - 无待结算订单直接返回")
    void testExecuteMonthlySettlementNoOrders() {
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        when(orderMapper.selectList(any())).thenReturn(Collections.emptyList());

        settlementService.executeMonthlySettlement(MONTH);

        // 内联：无订单时不应查询课程、不应落库
        verify(courseMapper, never()).selectById(anyLong());
        verify(settlementMapper, never()).insert(any(ApCourseSettlement.class));
    }

    @Test
    @DisplayName("A7 - 正常结算：作者分成0.7/平台0.3，作者ID取自课程表非订单买家")
    void testExecuteMonthlySettlementSuccess() {
        when(settlementMapper.selectCount(any())).thenReturn(0L);

        ApCourseOrder order = new ApCourseOrder();
        order.setCourseId(5L);
        order.setUserId(555);           // 买家ID
        order.setPaidAmount(new BigDecimal("100.00"));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));

        ApCourse course = new ApCourse();
        course.setId(5L);
        course.setAuthorId(20001);      // 真实作者ID
        when(courseMapper.selectById(5L)).thenReturn(course);

        settlementService.executeMonthlySettlement(MONTH);

        ArgumentCaptor<ApCourseSettlement> captor = ArgumentCaptor.forClass(ApCourseSettlement.class);
        verify(settlementMapper).insert(captor.capture());
        ApCourseSettlement settlement = captor.getValue();
        assertEquals(20001, settlement.getAuthorId());
        assertEquals(5L, settlement.getCourseId());
        assertEquals(MONTH, settlement.getSettlementMonth());
        assertEquals(0, new BigDecimal("100.00").compareTo(settlement.getTotalSales()));
        assertEquals(0, new BigDecimal("70.00").compareTo(settlement.getAuthorShare()));
        assertEquals(0, new BigDecimal("30.00").compareTo(settlement.getPlatformShare()));
        assertEquals(1, settlement.getOrderCount());
    }

    @Test
    @DisplayName("A7 - 并发下唯一约束冲突(DuplicateKeyException)幂等跳过，不向外抛")
    void testExecuteMonthlySettlementDuplicateKeyIdempotent() {
        when(settlementMapper.selectCount(any())).thenReturn(0L);

        ApCourseOrder order = new ApCourseOrder();
        order.setCourseId(5L);
        order.setPaidAmount(new BigDecimal("50.00"));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));

        ApCourse course = new ApCourse();
        course.setId(5L);
        course.setAuthorId(20001);
        when(courseMapper.selectById(5L)).thenReturn(course);

        // 并发下已有的同作者/课程/月份结算记录触发唯一键冲突
        when(settlementMapper.insert(any(ApCourseSettlement.class)))
                .thenThrow(new DuplicateKeyException("duplicate uk_author_course_month"));

        // 关键断言：insert 抛唯一键冲突不能向外传播，结算照常完成但不重复落账
        assertDoesNotThrow(() -> settlementService.executeMonthlySettlement(MONTH));
        verify(settlementMapper).insert(any(ApCourseSettlement.class));
    }

    @Test
    @DisplayName("月度结算列表 - 汇总销售/平台/作者分成合计")
    void testGetMonthlyListTotals() {
        ApCourseSettlement s1 = new ApCourseSettlement();
        s1.setTotalSales(new BigDecimal("100"));
        s1.setPlatformShare(new BigDecimal("30"));
        s1.setAuthorShare(new BigDecimal("70"));
        ApCourseSettlement s2 = new ApCourseSettlement();
        s2.setTotalSales(new BigDecimal("200"));
        s2.setPlatformShare(new BigDecimal("60"));
        s2.setAuthorShare(new BigDecimal("140"));
        when(settlementMapper.selectList(any())).thenReturn(List.of(s1, s2));

        ResponseResult result = settlementService.getMonthlyList(20001L);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        assertEquals(0, new BigDecimal("300").compareTo((BigDecimal) data.get("totalSales")));
        assertEquals(0, new BigDecimal("90").compareTo((BigDecimal) data.get("totalPlatformShare")));
        assertEquals(0, new BigDecimal("210").compareTo((BigDecimal) data.get("totalAuthorShare")));
    }
}