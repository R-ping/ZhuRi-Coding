package com.heima.content.service.order.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.mapper.course.ApCourseOrderMapper;
import com.heima.content.mapper.course.ApCourseSettlementMapper;
import com.heima.content.service.order.SettlementService;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.course.pojos.ApCourseOrder;
import com.heima.model.course.pojos.ApCourseSettlement;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@Slf4j
public class SettlementServiceImpl implements SettlementService {

    private static final BigDecimal AUTHOR_SHARE_RATE = new BigDecimal("0.7");
    private static final BigDecimal PLATFORM_SHARE_RATE = new BigDecimal("0.3");

    @Autowired
    private ApCourseSettlementMapper settlementMapper;

    @Autowired
    private ApCourseOrderMapper orderMapper;

    @Autowired
    private ApCourseMapper courseMapper;

    @Override
    public ResponseResult getMonthlyList(Long authorId) {
        LambdaQueryWrapper<ApCourseSettlement> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseSettlement::getAuthorId, authorId.intValue());
        query.orderByDesc(ApCourseSettlement::getSettlementMonth);
        List<ApCourseSettlement> list = settlementMapper.selectList(query);

        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal totalPlatformShare = BigDecimal.ZERO;
        BigDecimal totalAuthorShare = BigDecimal.ZERO;
        for (ApCourseSettlement s : list) {
            totalSales = totalSales.add(s.getTotalSales() != null ? s.getTotalSales() : BigDecimal.ZERO);
            totalPlatformShare = totalPlatformShare.add(s.getPlatformShare() != null ? s.getPlatformShare() : BigDecimal.ZERO);
            totalAuthorShare = totalAuthorShare.add(s.getAuthorShare() != null ? s.getAuthorShare() : BigDecimal.ZERO);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("list", list != null ? list : Collections.emptyList());
        result.put("totalSales", totalSales);
        result.put("totalPlatformShare", totalPlatformShare);
        result.put("totalAuthorShare", totalAuthorShare);
        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult getSettlementDetail(Long settlementId) {
        ApCourseSettlement settlement = settlementMapper.selectById(settlementId);
        if (settlement == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        return ResponseResult.okResult(settlement);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeMonthlySettlement(String month) {
        log.info("开始执行月度结算: {}", month);

        // 幂等保护：同一月份已存在结算记录则跳过，避免重复执行产生重复结算（收入失真）
        LambdaQueryWrapper<ApCourseSettlement> existQuery = new LambdaQueryWrapper<>();
        existQuery.eq(ApCourseSettlement::getSettlementMonth, month);
        if (settlementMapper.selectCount(existQuery) > 0) {
            log.warn("月份 {} 已结算过，跳过本次执行，防止重复结算", month);
            return;
        }

        // 查询上月已支付订单
        LambdaQueryWrapper<ApCourseOrder> orderQuery = new LambdaQueryWrapper<>();
        orderQuery.eq(ApCourseOrder::getStatus, ApCourseOrder.Status.PAID.getCode());
        orderQuery.apply("DATE_FORMAT(pay_time, '%Y-%m') = {0}", month);
        List<ApCourseOrder> orders = orderMapper.selectList(orderQuery);

        if (orders.isEmpty()) {
            log.info("月份 {} 无待结算订单", month);
            return;
        }

        // 按课程分组统计（每个课程归属一个作者，从课程表取真实作者ID，而非订单买家ID）
        Map<Long, SettlementGroup> groupMap = new HashMap<>();
        for (ApCourseOrder order : orders) {
            SettlementGroup group = groupMap.computeIfAbsent(order.getCourseId(), k -> {
                SettlementGroup g = new SettlementGroup();
                // 从课程表查询作者ID：order.getUserId() 是买家，不能用作作者结算
                ApCourse course = courseMapper.selectById(k);
                g.authorId = course != null && course.getAuthorId() != null ? course.getAuthorId() : 0;
                g.courseId = k;
                return g;
            });
            group.totalSales = group.totalSales.add(order.getPaidAmount());
            group.orderCount++;
        }

        // 生成结算记录
        for (SettlementGroup group : groupMap.values()) {
            ApCourseSettlement settlement = new ApCourseSettlement();
            settlement.setAuthorId(group.authorId);
            settlement.setCourseId(group.courseId);
            settlement.setSettlementMonth(month);
            settlement.setTotalSales(group.totalSales);
            settlement.setPlatformShare(group.totalSales.multiply(PLATFORM_SHARE_RATE).setScale(2, BigDecimal.ROUND_HALF_UP));
            settlement.setAuthorShare(group.totalSales.multiply(AUTHOR_SHARE_RATE).setScale(2, BigDecimal.ROUND_HALF_UP));
            settlement.setOrderCount(group.orderCount);
            settlement.setStatus(0);
            settlement.setCreatedTime(new Date());

            // 数据库唯一约束 uk_author_course_month 兜底：并发下已有同作者/课程/月份结算记录时，幂等跳过，避免重复结算
            try {
                settlementMapper.insert(settlement);
            } catch (DuplicateKeyException e) {
                log.warn("并发结算冲突已存在记录，幂等跳过: authorId={}, courseId={}, month={}",
                        group.authorId, group.courseId, month);
                continue;
            }
            log.info("结算记录: authorId={}, courseId={}, month={}, totalSales={}, authorShare={}",
                    group.authorId, group.courseId, month, group.totalSales, settlement.getAuthorShare());
        }

        log.info("月度结算完成: {}, 共{}条记录", month, groupMap.size());
    }

    private static class SettlementGroup {
        int authorId;
        Long courseId;
        BigDecimal totalSales = BigDecimal.ZERO;
        int orderCount = 0;
    }
}