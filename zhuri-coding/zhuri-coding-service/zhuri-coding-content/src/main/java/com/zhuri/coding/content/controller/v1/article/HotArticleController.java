package com.heima.content.controller.v1.article;

import com.heima.content.service.hot.HotService;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/hot")
public class HotArticleController {

    @Autowired
    private HotService hotService;

    @GetMapping("/articles")
    public ResponseResult getHotArticles(@RequestParam(required = false) String category,
                                        @RequestParam(required = false) Integer limit) {
        return ResponseResult.okResult(hotService.getHotArticles(category, limit));
    }

    @GetMapping("/collected-articles")
    public ResponseResult getCollectedArticles(@RequestParam(required = false) Integer limit) {
        return ResponseResult.okResult(hotService.getCollectedArticles(limit));
    }

    @GetMapping("/authors")
    public ResponseResult getHotAuthors(@RequestParam(required = false) String period,
                                        @RequestParam(required = false) Integer limit) {
        return ResponseResult.okResult(hotService.getHotAuthors(period, limit));
    }

    @GetMapping("/meta")
    public ResponseResult getHotMeta(@RequestParam(required = false) String tab,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String period) {
        return ResponseResult.okResult(hotService.getHotMeta(tab, category, period));
    }
}