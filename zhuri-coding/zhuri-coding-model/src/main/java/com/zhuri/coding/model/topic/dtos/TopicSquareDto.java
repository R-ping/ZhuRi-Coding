package com.heima.model.topic.dtos;

import lombok.Data;

@Data
public class TopicSquareDto {
    private String keyword;
    private String sort = "hot";
    private Long cursor = 0L;
    private Integer size = 20;
}