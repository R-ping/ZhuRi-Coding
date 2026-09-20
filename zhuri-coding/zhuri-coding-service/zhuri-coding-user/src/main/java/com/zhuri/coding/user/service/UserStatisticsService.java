package com.zhuri.coding.user.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;

import java.util.Map;

public interface UserStatisticsService {

    ResponseResult<Map<String, Object>> getUserStatistics();
}