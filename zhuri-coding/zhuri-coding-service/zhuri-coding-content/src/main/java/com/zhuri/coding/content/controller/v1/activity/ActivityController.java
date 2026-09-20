package com.heima.content.controller.v1.activity;

import com.heima.content.service.activity.ActivityService;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {

    @Autowired
    private ActivityService activityService;

    @GetMapping("/list")
    public ResponseResult list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category) {
        return ResponseResult.okResult(activityService.list(page, size, type, status, category));
    }

    @GetMapping("/{id}")
    public ResponseResult getById(@PathVariable Long id) {
        return ResponseResult.okResult(activityService.getById(id));
    }
}