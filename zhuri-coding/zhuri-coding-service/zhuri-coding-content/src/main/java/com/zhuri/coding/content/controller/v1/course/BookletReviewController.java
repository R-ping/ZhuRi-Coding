package com.heima.content.controller.v1.course;

import com.heima.content.config.EditorConfig;
import com.heima.content.service.course.ApCourseService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 小册编辑审核控制器（编辑专属，全部接口校验编辑白名单）
 * 作者只负责存稿与提交审核；申报通过、上架、发布小节、下架均由编辑在此完成。
 */
@RestController
@RequestMapping("/api/v1/course/review")
@Slf4j
public class BookletReviewController {

    @Autowired
    private ApCourseService apCourseService;

    /**
     * 编辑白名单校验
     * @return null 表示通过，否则返回错误结果
     */
    private ResponseResult checkEditor() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (!EditorConfig.isEditor(user.getId())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无编辑权限");
        }
        return null;
    }

    // ========== 申报审核 ==========

    /** 申报待审列表（status=1，分页/关键词） */
    @GetMapping("/apply-list")
    public ResponseResult applyList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        ResponseResult check = checkEditor();
        if (check != null) {
            return check;
        }
        return apCourseService.reviewList(page, size, (byte) 1, keyword);
    }

    /** 通过申报：1→4（开通写作权限） */
    @PostMapping("/apply-approve")
    public ResponseResult approveApply(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) {
            return check;
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        return apCourseService.editorTransition(courseId, (byte) 4, null);
    }

    /** 拒绝申报：1→2，写 apply_reason */
    @PostMapping("/apply-reject")
    public ResponseResult rejectApply(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) {
            return check;
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        String reason = params.get("reason") != null ? params.get("reason").toString() : "";
        return apCourseService.editorTransition(courseId, (byte) 2, reason);
    }

    // ========== 上架审核 ==========

    /** 上架审核/运营列表（status=5 待上架，9 已上架，3 已下架；分页/关键词） */
    @GetMapping("/publish-list")
    public ResponseResult publishList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Byte status,
            @RequestParam(required = false) String keyword) {
        ResponseResult check = checkEditor();
        if (check != null) {
            return check;
        }
        // 默认查上架待审；前端可传 5/9/3 切换
        byte targetStatus = status != null ? status : (byte) 5;
        return apCourseService.reviewList(page, size, targetStatus, keyword);
    }

    /** 上架：5→9，写 published_at，并批量发布全部草稿小节（0→1） */
    @PostMapping("/publish-approve")
    public ResponseResult approvePublish(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) {
            return check;
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        ResponseResult transition = apCourseService.editorTransition(courseId, (byte) 9, null);
        if (transition.getCode() != AppHttpCodeEnum.SUCCESS.getCode()) {
            return transition;
        }
        return apCourseService.publishSections(courseId, null);
    }

    /** 驳回上架：5→4，写 reason */
    @PostMapping("/publish-reject")
    public ResponseResult rejectPublish(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) {
            return check;
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        String reason = params.get("reason") != null ? params.get("reason").toString() : "";
        return apCourseService.editorTransition(courseId, (byte) 4, reason);
    }

    /** 发布单个/批量小节：0→1 */
    @PostMapping("/publish-section")
    public ResponseResult publishSection(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) {
            return check;
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        List<Long> chapterIds = null;
        if (params.get("chapterIds") != null) {
            chapterIds = new java.util.ArrayList<>();
            for (Object o : (List<?>) params.get("chapterIds")) {
                chapterIds.add(Long.parseLong(o.toString()));
            }
        }
        return apCourseService.publishSections(courseId, chapterIds == null ? Collections.emptyList() : chapterIds);
    }

    /** 下架：9→3 */
    @PostMapping("/unpublish")
    public ResponseResult unpublish(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) {
            return check;
        }
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        return apCourseService.editorTransition(courseId, (byte) 3, null);
    }
}
