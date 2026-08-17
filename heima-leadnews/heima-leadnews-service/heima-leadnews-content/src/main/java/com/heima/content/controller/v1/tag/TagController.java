
package com.heima.content.controller.v1.tag;

import com.heima.content.service.tag.TagService;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tag")
public class TagController {

    @Autowired
    private TagService tagService;

    @GetMapping("/list")
    public ResponseResult findList(@RequestParam(required = false) String keyword) {
        return ResponseResult.okResult(tagService.findList(keyword));
    }

    @GetMapping("/by-category")
    public ResponseResult findTagsByCategory(@RequestParam Integer categoryId) {
        return ResponseResult.okResult(tagService.findTagsByCategory(categoryId));
    }

    /**
     * 标签详情页：分页查询某标签下的文章列表
     * @param tagName 标签名（URL 编码，可能含特殊字符如 C++、C#）
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @param sort hot-热门 latest-最新 hottest-最热
     * @return {total, page, size, list}
     */
    @GetMapping("/{tagName}/articles")
    public ResponseResult getTagArticles(@PathVariable String tagName,
                                         @RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "20") Integer size,
                                         @RequestParam(defaultValue = "hot") String sort) {
        return tagService.getArticles(tagName, page, size, sort);
    }
}
