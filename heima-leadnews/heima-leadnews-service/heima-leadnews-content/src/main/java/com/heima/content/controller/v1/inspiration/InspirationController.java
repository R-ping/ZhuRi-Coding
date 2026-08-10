package com.heima.content.controller.v1.inspiration;

import com.heima.content.service.activity.ActivityService;
import com.heima.content.service.topic.TopicService;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inspiration")
public class InspirationController {

    @Autowired
    private TopicService topicService;

    @Autowired
    private ActivityService activityService;

    @GetMapping("/topics")
    public ResponseResult topics(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 @RequestParam(defaultValue = "hot") String sort,
                                 @RequestParam(required = false) Integer themeType) {
        return ResponseResult.okResult(topicService.inspirationTopics(page, size, sort, themeType));
    }

    @GetMapping("/activities")
    public ResponseResult activities(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String category) {
        return ResponseResult.okResult(activityService.list(page, size, type, status, category));
    }
}