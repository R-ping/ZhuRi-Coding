package com.heima.content.controller.v1.topic;

import com.heima.content.service.topic.TopicService;
import com.heima.model.topic.dtos.TopicSquareDto;
import com.heima.model.topic.vos.TopicDetailVO;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/topics")
public class TopicController {

    @Autowired
    private TopicService topicService;

    @GetMapping("/recommend")
    public ResponseResult recommend(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "5") int size) {
        return ResponseResult.okResult(topicService.recommend(page, size));
    }

    @GetMapping("/square")
    public ResponseResult square(TopicSquareDto dto) {
        return ResponseResult.okResult(topicService.square(dto));
    }

    @GetMapping("/{id}")
    public ResponseResult detail(@PathVariable Long id) {
        TopicDetailVO vo = topicService.detail(id);
        if (vo == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        return ResponseResult.okResult(vo);
    }

    @GetMapping("/{id}/feed")
    public ResponseResult feed(@PathVariable Long id,
                               @RequestParam(defaultValue = "hot") String tab,
                               @RequestParam(defaultValue = "0") long cursor,
                               @RequestParam(defaultValue = "20") int size) {
        return ResponseResult.okResult(topicService.feed(id, tab, cursor, size));
    }

    @PostMapping("/{id}/view")
    public ResponseResult view(@PathVariable Long id) {
        Long userId = null;
        try {
            userId = AppThreadLocalUtil.getUser().getId().longValue();
        } catch (Exception e) {
            // 未登录用户也可增加阅读量，使用 IP 或随机标识
        }
        topicService.incrView(id, userId != null ? userId : 0L);
        return ResponseResult.okResult();
    }

    @GetMapping("/search")
    public ResponseResult search(@RequestParam String keyword,
                                 @RequestParam(defaultValue = "10") int limit) {
        return ResponseResult.okResult(topicService.search(keyword, limit));
    }

    @GetMapping("/recommended")
    public ResponseResult recommended(@RequestParam(required = false) Long excludeId,
                                      @RequestParam(defaultValue = "6") int limit) {
        return ResponseResult.okResult(topicService.recommendedTopics(excludeId, limit));
    }

    @GetMapping("/inspiration/topics")
    public ResponseResult inspirationTopics(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(defaultValue = "hot") String sort,
                                            @RequestParam(required = false) Integer themeType) {
        return ResponseResult.okResult(topicService.inspirationTopics(page, size, sort, themeType));
    }
}