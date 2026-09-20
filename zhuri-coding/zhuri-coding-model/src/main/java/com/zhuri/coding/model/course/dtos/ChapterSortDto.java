package com.heima.model.course.dtos;

import lombok.Data;
import java.util.List;

@Data
public class ChapterSortDto {
    private Long courseId;
    private List<SortItem> items;

    @Data
    public static class SortItem {
        private Long id;
        private Integer sortOrder;
    }
}