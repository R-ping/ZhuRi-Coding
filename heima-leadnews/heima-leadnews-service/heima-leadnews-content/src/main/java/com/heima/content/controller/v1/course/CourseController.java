package com.heima.content.controller.v1.course;

import com.heima.content.service.course.ApCourseService;
import com.heima.model.course.dtos.CourseDto;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/course")
@Slf4j
public class CourseController {

    @Autowired
    private ApCourseService apCourseService;

    // ========== 公开接口 ==========

    /** 公开课程列表：仅返回已上架课程（由服务端强制过滤 status=9，忽略客户端传入的状态） */
    @GetMapping("/list")
    public ResponseResult findList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return apCourseService.findList(page, size);
    }

    @GetMapping("/my")
    public ResponseResult getMyCourses(@RequestParam(required = false) String filter) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : null;
        return apCourseService.getMyCourses(userId, filter);
    }

    /** 公开课程详情（含章节列表） */
    @GetMapping("/detail")
    public ResponseResult getPublicDetail(@RequestParam Long courseId) {
        return apCourseService.getPublicDetail(courseId);
    }

    @PostMapping("/progress")
    public ResponseResult updateProgress(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : null;
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        Long chapterId = params.get("chapterId") != null ? Long.parseLong(params.get("chapterId").toString()) : null;
        Boolean isCompleted = params.get("isCompleted") != null ? Boolean.parseBoolean(params.get("isCompleted").toString()) : null;
        return apCourseService.updateProgress(userId, courseId, chapterId, isCompleted);
    }

    // ========== 创作者课程管理接口 ==========

    /** 检查当前用户是否有课程创作权限 */
    @GetMapping("/author/check-permission")
    public ResponseResult checkAuthorPermission() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return apCourseService.checkAuthorPermission(user.getId().longValue());
    }

    /** 创建课程草稿 */
    @PostMapping("/manage/create")
    public ResponseResult createCourse(@RequestBody CourseDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return apCourseService.createCourse(dto, user.getId().longValue());
    }

    /** 更新课程信息 */
    @PutMapping("/manage/update")
    public ResponseResult updateCourse(@RequestBody CourseDto dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return apCourseService.updateCourse(dto, user.getId().longValue());
    }

    /** 作者课程管理列表 */
    @GetMapping("/manage/list")
    public ResponseResult manageList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Byte status,
            @RequestParam(required = false) String keyword) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return apCourseService.manageList(page, size, status, keyword, user.getId().longValue());
    }

    /** 课程编辑详情（含所有章节） */
    @GetMapping("/manage/detail")
    public ResponseResult manageDetail(@RequestParam Long courseId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return apCourseService.manageDetail(courseId, user.getId().longValue());
    }

    /** 软删除课程 */
    @PostMapping("/manage/delete")
    public ResponseResult softDelete(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        return apCourseService.softDelete(courseId, user.getId().longValue());
    }

    /** 提交审核 */
    @PostMapping("/manage/submit")
    public ResponseResult submitForReview(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        // 提交审核：草稿(0)/被拒(2) -> 审核中(1)，走状态机校验（含作者归属）
        return apCourseService.submitApply(courseId, null, user.getId().longValue());
    }

    /** 下架课程 */
    @PostMapping("/manage/unpublish")
    public ResponseResult unpublish(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        // 作者下架自己的已上架课程（9→3），走状态机校验（含作者归属）
        return apCourseService.authorUnpublish(courseId, user.getId().longValue());
    }

    // ========== 小册申报（作者侧） ==========

    /** 提交小册申报（作者，0→1），applyContent 为申报表单 JSON 字符串 */
    @PostMapping("/manage/apply")
    public ResponseResult submitApply(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        String applyContent = params.get("applyContent") != null ? params.get("applyContent").toString() : null;
        return apCourseService.submitApply(courseId, applyContent, user.getId().longValue());
    }

    /** 我的小册列表（作者） */
    @GetMapping("/manage/my-booklets")
    public ResponseResult myBooklets(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Byte status) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return apCourseService.getMyBooklets(user.getId().longValue(), page, size, status);
    }
}