package com.heima.model.pins.dtos;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 发布沸点请求 DTO
 */
@Data
@NoArgsConstructor
public class PinsPublishDTO {

    private String content = "";

    private List<String> imageUrls = new ArrayList<>();

    private List<String> topicTags = new ArrayList<>();

    private Long topicId;

    private Long circleId;

    private String linkUrl = "";

    private String linkTitle = "";
}