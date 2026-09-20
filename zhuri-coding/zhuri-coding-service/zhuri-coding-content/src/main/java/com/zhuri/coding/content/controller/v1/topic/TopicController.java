package com.zhuri.coding.content.controller.v1.topic;

import com.zhuri.coding.content.service.topic.TopicService;
import com.zhuri.coding.model.topic.dtos.TopicSquareDto;
import com.zhuri.coding.model.topic.vos.TopicDetailVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/topics")
public class TopicController {

    @Autowired
    private TopicService topicService;

    // 侧栏“推荐话题(换一换)”：路径命令为 recommend-topics，避免与文章列表的
    // /article/recommend_all|recommend_cate|recommend_follow 系列在抓包时混淆
    @GetMapping("/recommend-topics")
    public ResponseResult recommendTopics(@RequestParam(defaultValue = "0") int page,
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
        // 话题浏览数改为聚合其关联沸点/文章的浏览量总和，不再单独递增，故此处直接返回成功
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