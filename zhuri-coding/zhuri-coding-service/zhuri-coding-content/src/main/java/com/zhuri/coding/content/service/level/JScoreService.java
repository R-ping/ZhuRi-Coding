package com.heima.content.service.level;

import com.heima.model.level.dtos.JScoreDetailVO;
import com.heima.model.level.dtos.JScoreOverviewVO;

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