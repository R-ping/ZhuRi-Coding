package com.heima.model.topic.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class TopicDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name = "";
    private String description = "";
    private String coverImage = "";
    private String badge = "";
    private Integer type = 1;
    private Long viewCount = 0L;
    private Long participantCount = 0L;
    private Long postCount = 0L;
    private List<String> availableTabs = new ArrayList<>();
    private List<TopicCircleInfo> circleInfo = new ArrayList<>();

    @Data
    public static class TopicCircleInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long circleId;
        private String circleName = "";
        private Integer memberCount = 0;
    }
}