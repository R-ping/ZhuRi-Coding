package com.zhuri.coding.content.controller.v1.course;

import com.zhuri.coding.content.service.course.ApCourseChapterService;
import com.zhuri.coding.model.course.dtos.ChapterDto;
import com.zhuri.coding.model.course.dtos.ChapterSortDto;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/course/chapter")
@Slf4j
public class CourseChapterController {

    @Autowired
    private ApCourseChapterService chapterService;

    /** 公开获取章节详情（用于阅读） */
    @GetMapping("/{id}/detail")
    public ResponseResult getChapterDetail(@PathVariable Long id) {
        return chapterService.getChapterDetail(id);
    }

    /** 创建章节 */
    @PostMapping("/create")
    public ResponseResult createChapter(@RequestBody ChapterDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.zhuri.coding.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return chapterService.createChapter(dto, user.getId().longValue());
    }

    /** 更新章节 */
    @PutMapping("/update")
    public ResponseResult updateChapter(@RequestBody ChapterDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.zhuri.coding.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return chapterService.updateChapter(dto, user.getId().longValue());
    }

    /** 删除章节 */
    @DeleteMapping("/{id}")
    public ResponseResult deleteChapter(@PathVariable Long id) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.zhuri.coding.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return chapterService.deleteChapter(id, user.getId().longValue());
    }

    /** 作者提交小节审核（0草稿→2审核中），body: {chapterId, note} */
    @PostMapping("/{id}/submit-review")
    public ResponseResult submitForReview(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.zhuri.coding.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        String note = body != null && body.get("note") != null ? body.get("note").toString() : null;
        return chapterService.submitForReview(id, note, user.getId().longValue());
    }

    /** 批量更新章节排序 */
    @PutMapping("/sort")
    public ResponseResult updateSort(@RequestBody ChapterSortDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.zhuri.coding.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return chapterService.updateSort(dto, user.getId().longValue());
    }
}