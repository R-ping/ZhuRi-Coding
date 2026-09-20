package com.heima.content.service.article;

import com.heima.model.common.dtos.ResponseResult;

public interface ArticleStatisticsService {

    ResponseResult getUserStatistics(Long userId);
}