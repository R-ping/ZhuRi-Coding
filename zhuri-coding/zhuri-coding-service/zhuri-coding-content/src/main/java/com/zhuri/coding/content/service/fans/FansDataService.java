package com.heima.content.service.fans;

import com.heima.model.common.dtos.ResponseResult;

public interface FansDataService {

    ResponseResult getFansStatistics(String startDate, String endDate);

    ResponseResult getFansTrend(String startDate, String endDate, Integer days);

    ResponseResult getFansList(Integer page, Integer size);

    ResponseResult followFans(Integer userId);

    ResponseResult getFansPortrait();

    ResponseResult getFansAvatars(Integer page, Integer size);
}