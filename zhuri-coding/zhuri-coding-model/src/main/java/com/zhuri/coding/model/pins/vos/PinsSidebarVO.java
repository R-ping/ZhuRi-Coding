package com.zhuri.coding.model.pins.vos;

import lombok.Data;
import lombok.NoArgsConstructor;

import com.zhuri.coding.model.topic.vos.TopicRecommendVO;
import java.util.ArrayList;
import java.util.List;

/**
 * 沸点侧边栏 VO
 */
@Data
@NoArgsConstructor
public class PinsSidebarVO {

    private Integer pinsCount = 0;
    private Integer circleCount = 0;
    private Integer followingCount = 0;
    private Integer followersCount = 0;
    private List<PinsVO> featuredPins = new ArrayList<>();
    private List<TopicRecommendVO> recommendedTopics = new ArrayList<>();
}