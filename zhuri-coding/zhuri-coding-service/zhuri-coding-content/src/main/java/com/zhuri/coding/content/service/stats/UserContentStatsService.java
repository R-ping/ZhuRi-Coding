package com.zhuri.coding.content.service.stats;

import com.zhuri.coding.model.user.vo.UserStatsVO;

/**
 * 用户/作者内容统计聚合服务。
 */
public interface UserContentStatsService {

    /**
     * 统计指定用户的文章/沸点/反馈/关注等掘金式对象属性。
     *
     * @param userId 用户ID
     * @return 聚合统计（缺省为 0）
     */
    UserStatsVO stats(Long userId);
}