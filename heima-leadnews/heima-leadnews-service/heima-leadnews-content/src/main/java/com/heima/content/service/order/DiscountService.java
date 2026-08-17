package com.heima.content.service.order;

import com.heima.model.course.dtos.CourseDiscountDto;
import com.heima.model.course.pojos.ApCourseDiscount;
import com.heima.model.common.dtos.ResponseResult;

public interface DiscountService {

    /** 创建折扣码 */
    ResponseResult createDiscount(CourseDiscountDto dto, Long userId);

    /** 折扣码列表 */
    ResponseResult listDiscounts(Long courseId, Long userId);

    /** 停用折扣码 */
    ResponseResult disableDiscount(Long discountId, Long userId);

    /** 校验折扣码（内部使用） */
    ApCourseDiscount validateDiscount(String code, Long courseId);

    /** 根据折扣码查询（内部使用） */
    ApCourseDiscount getDiscountByCode(String code);

    /** 原子扣减折扣码使用次数（支付成功后调用，防止并发超卖）；返回是否扣减成功 */
    boolean consumeDiscountCode(String code);

    /** 校验折扣码并返回折扣信息（下单前预览） */
    ResponseResult validateDiscountForPreview(String code, Long courseId);
}