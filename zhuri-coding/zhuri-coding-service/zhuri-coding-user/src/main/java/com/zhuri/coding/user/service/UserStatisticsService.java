package com.heima.user.service;

import com.heima.model.common.dtos.ResponseResult;

import java.util.Map;

public interface UserStatisticsService {

    ResponseResult<Map<String, Object>> getUserStatistics();
}