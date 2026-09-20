package com.heima.model.course.dtos;

import lombok.Data;

@Data
public class ChapterDto {
    private Long id;
    private Long courseId;
    private String title;
    private String content;
    private Integer sortOrder;
    private Byte isFree;
    private Integer estimatedMinutes;
}