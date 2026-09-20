package com.zhuri.coding.content.controller.v1;

import com.zhuri.coding.content.service.course.ApCourseService;
import com.zhuri.coding.content.service.tag.TagService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.UserSearchDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容库搜索接口（课程/标签）
 *
 * <p>路径约定：CourseController(/api/v1/course) 与 TagController(/api/v1/tag) 的基址派生路径
 * 无法命中 `api/v1/search/...`，故独立于此处承载搜索，保证与前端 conf 中
 * course_search/tag_search 的 url 映射完全一致（sv=content，网关前缀 /content）。</p>
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    @Autowired
    private ApCourseService apCourseService;

    @Autowired
    private TagService tagService;

    /** 课程搜索：按标题 LIKE 分页查询已上架(9)课程，公开只读 */
    @PostMapping("/course")
    public ResponseResult searchCourse(@RequestBody UserSearchDto dto) {
        return apCourseService.searchCourse(dto.getSearchWords(), dto.getPageNum(), dto.getPageSize());
    }

    /** 标签搜索：按标签名 LIKE 分页查询启用中的标签，公开只读 */
    @PostMapping("/tag")
    public ResponseResult searchTag(@RequestBody UserSearchDto dto) {
        return tagService.search(dto.getSearchWords(), dto.getPageNum(), dto.getPageSize());
    }
}