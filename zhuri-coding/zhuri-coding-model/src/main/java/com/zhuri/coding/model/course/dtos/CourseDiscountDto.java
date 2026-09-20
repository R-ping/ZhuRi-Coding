package com.heima.model.course.dtos;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class CourseDiscountDto {
    private Long courseId;
    private String code;
    private Integer discountType; // 1固定金额 2百分比
    private BigDecimal discountValue;
    private Integer maxUses;
    private Date startTime;
    private Date endTime;
}