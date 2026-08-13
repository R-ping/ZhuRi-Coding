package com.heima.content.service.topic;

import com.heima.model.topic.dtos.TopicSquareDto;
import com.heima.model.topic.vos.TopicDetailVO;
import com.heima.model.topic.vos.TopicRecommendVO;

import java.util.List;
import java.util.Map;

public interface TopicService {

    /**
     * 推荐话题（侧边栏"换一换"，支持分页轮换）
     */
    Map<String, Object> recommend(int page, int size);

    /**
     * 话题广场列表
     */
    Map<String, Object> square(TopicSquareDto dto);

    /**
     * 话题详情
     */
    TopicDetailVO detail(Long id);

    /**
     * 话题内容 Feed 流
     */
    Map<String, Object> feed(Long id, String tab, long cursor, int size);

    /**
     * 搜索话题
     */
    List<TopicRecommendVO> search(String keyword, int limit);

    /**
     * 灵感话题列表（分页，支持 themeType 过滤）
     */
    Map<String, Object> inspirationTopics(int page, int size, String sort, Integer themeType);

    /**
     * 推荐话题列表（排除指定话题，按阅读量排序）
     */
    List<TopicRecommendVO> recommendedTopics(Long excludeId, int limit);
}