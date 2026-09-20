package com.zhuri.coding.content.service.level;

import com.zhuri.coding.model.level.dtos.JScoreDetailVO;
import com.zhuri.coding.model.level.dtos.JScoreOverviewVO;

public interface JScoreService {
    /**
     * 获取积分概览
     */
    JScoreOverviewVO getOverview(Long userId);

    /**
     * 获取积分明细
     */
    JScoreDetailVO getDetail(Long userId, String category, String cursor, Integer size);
}