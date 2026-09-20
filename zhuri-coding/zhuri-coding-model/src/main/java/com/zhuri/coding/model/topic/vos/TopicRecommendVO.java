package com.heima.model.topic.vos;

import lombok.Data;

import java.io.Serializable;

@Data
public class TopicRecommendVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name = "";
    private String badge = "";
    private Long participantCount = 0L;
    private Long viewCount = 0L;
}