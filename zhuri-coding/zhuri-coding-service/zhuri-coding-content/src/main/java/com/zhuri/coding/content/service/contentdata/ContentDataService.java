package com.heima.content.service.contentdata;

import com.heima.model.common.dtos.ResponseResult;

public interface ContentDataService {

    ResponseResult getArticleStatistics(Long userId, String startDate, String endDate);

    ResponseResult getArticleTrend(Long userId, String startDate, String endDate, Integer days);

    ResponseResult getArticleDetail(Long userId, String startDate, String endDate, Integer page, Integer size);

    ResponseResult getColumnStatistics(Long userId, String startDate, String endDate);

    ResponseResult getColumnTrend(Long userId, String startDate, String endDate, Integer days);

    ResponseResult getColumnDetail(Long userId, String startDate, String endDate, Integer page, Integer size);

    ResponseResult getPinStatistics(Long userId, String startDate, String endDate);

    ResponseResult getPinTrend(Long userId, String startDate, String endDate, Integer days);

    ResponseResult getPinDetail(Long userId, String startDate, String endDate, Integer page, Integer size);
}