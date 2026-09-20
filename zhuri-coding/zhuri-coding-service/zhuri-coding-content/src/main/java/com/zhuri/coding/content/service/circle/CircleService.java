package com.zhuri.coding.content.service.circle;

import com.zhuri.coding.model.circle.vos.CircleVO;

import java.util.List;
import java.util.Map;

public interface CircleService {

    /**
     * 推荐圈子 Top10
     */
    List<CircleVO> recommend();

    /**
     * 圈子广场分页
     */
    Map<String, Object> square(int page, int size);

    /**
     * 人气圈子 5个
     */
    List<CircleVO> hot();

    /**
     * 圈子详情（含 isJoined）
     */
    CircleVO detail(Long circleId, Integer userId);

    /**
     * 加入圈子
     */
    void join(Long circleId, Integer userId);

    /**
     * 退出圈子
     */
    void leave(Long circleId, Integer userId);

    /**
     * 圈子沸点流
     */
    Map<String, Object> feed(Long circleId, String tab, int page, int size);

    /**
     * 我的圈子
     */
    List<CircleVO> myCircles(Integer userId);

    /**
     * 根据分类获取圈子列表（分页）
     */
    List<CircleVO> listByCategory(Long categoryId, int page, int size);
}