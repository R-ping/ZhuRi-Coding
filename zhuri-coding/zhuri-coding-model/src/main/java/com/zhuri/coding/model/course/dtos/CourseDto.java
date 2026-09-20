package com.heima.model.course.dtos;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class CourseDto {
    private Long id;
    private String title;
    private String subtitle;
    private String description;
    private String coverImage;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer categoryId;
    private Byte status;
    private String reason;
}