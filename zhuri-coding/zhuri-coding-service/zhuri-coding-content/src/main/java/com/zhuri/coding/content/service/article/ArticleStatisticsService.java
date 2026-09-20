package com.zhuri.coding.content.service.article;

import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface ArticleStatisticsService {

    ResponseResult getUserStatistics(Long userId);
}