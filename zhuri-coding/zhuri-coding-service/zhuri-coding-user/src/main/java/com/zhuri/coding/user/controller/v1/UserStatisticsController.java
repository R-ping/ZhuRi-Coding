package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.user.service.UserStatisticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
@Slf4j
public class UserStatisticsController {

    @Autowired
    private UserStatisticsService userStatisticsService;

    @GetMapping("/statistics")
    public ResponseResult<Map<String, Object>> getStatistics() {
        return userStatisticsService.getUserStatistics();
    }
}