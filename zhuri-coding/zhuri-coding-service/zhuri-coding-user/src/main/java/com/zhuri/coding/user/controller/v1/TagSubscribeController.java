package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.user.service.TagSubscribeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tags")
@Slf4j
public class TagSubscribeController {

    @Autowired
    private TagSubscribeService tagSubscribeService;

    @GetMapping("/discover")
    public ResponseResult discover(@RequestParam(defaultValue = "hottest") String sort,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "20") Integer size) {
        return tagSubscribeService.discover(sort, keyword, page, size);
    }

    @GetMapping("/followed")
    public ResponseResult getFollowed() {
        return tagSubscribeService.getFollowed();
    }

    @PostMapping("/follow/{tagId}")
    public ResponseResult follow(@PathVariable Integer tagId) {
        return tagSubscribeService.follow(tagId);
    }

    @DeleteMapping("/follow/{tagId}")
    public ResponseResult unfollow(@PathVariable Integer tagId) {
        return tagSubscribeService.unfollow(tagId);
    }

    /**
     * 标签详情页：按标签名查询标签详情（id、标签名、关注数、当前用户是否已关注）
     * @param tagName 标签名（URL 编码，可能含特殊字符如 C++、C#）
     * @return {id, tagName, categoryCode, categoryName, followerCount, isFollowed}
     */
    @GetMapping("/{tagName}/detail")
    public ResponseResult tagDetail(@PathVariable String tagName) {
        return tagSubscribeService.tagDetail(tagName);
    }
}