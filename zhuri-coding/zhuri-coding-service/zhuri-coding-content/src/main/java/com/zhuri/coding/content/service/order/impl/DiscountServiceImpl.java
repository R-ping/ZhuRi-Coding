package com.zhuri.coding.content.service.order.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.course.ApCourseDiscountMapper;
import com.zhuri.coding.content.service.order.DiscountService;
import com.zhuri.coding.model.course.dtos.CourseDiscountDto;
import com.zhuri.coding.model.course.pojos.ApCourseDiscount;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class DiscountServiceImpl implements DiscountService {

    @Autowired
    private ApCourseDiscountMapper discountMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createDiscount(CourseDiscountDto dto, Long userId) {
        if (dto.getCourseId() == null || dto.getDiscountType() == null || dto.getDiscountValue() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourseDiscount discount = new ApCourseDiscount();
        discount.setCourseId(dto.getCourseId());
        discount.setCode(dto.getCode() != null ? dto.getCode() : generateCode());
        discount.setDiscountType(dto.getDiscountType());
        discount.setDiscountValue(dto.getDiscountValue());
        discount.setMaxUses(dto.getMaxUses() != null ? dto.getMaxUses() : 100);
        discount.setUsedCount(0);
        discount.setStartTime(dto.getStartTime() != null ? dto.getStartTime() : new Date());
        discount.setEndTime(dto.getEndTime() != null ? dto.getEndTime() : new Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000));
        discount.setStatus(1);
        discount.setCreatedTime(new Date());

        discountMapper.insert(discount);
        return ResponseResult.okResult(discount);
    }

    @Override
    public ResponseResult listDiscounts(Long courseId, Long userId) {
        LambdaQueryWrapper<ApCourseDiscount> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseDiscount::getCourseId, courseId);
        query.orderByDesc(ApCourseDiscount::getCreatedTime);
        List<ApCourseDiscount> list = discountMapper.selectList(query);
        Map<String, Object> result = new HashMap<>();
        result.put("list", list != null ? list : Collections.emptyList());
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult disableDiscount(Long discountId, Long userId) {
        ApCourseDiscount discount = discountMapper.selectById(discountId);
        if (discount == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "折扣码不存在");
        }
        discount.setStatus(0);
        discountMapper.updateById(discount);
        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    @Override
    public ApCourseDiscount validateDiscount(String code, Long courseId) {
        if (code == null || code.isEmpty()) return null;

        LambdaQueryWrapper<ApCourseDiscount> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseDiscount::getCode, code);
        query.eq(ApCourseDiscount::getStatus, 1);
        ApCourseDiscount discount = discountMapper.selectOne(query);

        if (discount == null) return null;

        // 验证是否匹配课程
        if (!discount.getCourseId().equals(courseId)) return null;

        // 验证是否过期
        Date now = new Date();
        if (now.before(discount.getStartTime()) || now.after(discount.getEndTime())) return null;

        // 验证使用次数
        if (discount.getUsedCount() >= discount.getMaxUses()) return null;

        return discount;
    }

    @Override
    public ApCourseDiscount getDiscountByCode(String code) {
        if (code == null || code.isEmpty()) return null;
        LambdaQueryWrapper<ApCourseDiscount> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseDiscount::getCode, code);
        return discountMapper.selectOne(query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean consumeDiscountCode(String code) {
        if (code == null || code.isEmpty()) return false;
        return discountMapper.incrementUsedCountAtomic(code) > 0;
    }

    @Override
    public ResponseResult validateDiscountForPreview(String code, Long courseId) {
        ApCourseDiscount discount = validateDiscount(code, courseId);
        if (discount == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "折扣码无效或已过期");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("discountType", discount.getDiscountType());
        result.put("discountValue", discount.getDiscountValue());
        result.put("code", discount.getCode());
        return ResponseResult.okResult(result);
    }

    private String generateCode() {
        return "COURSE" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}