package com.zhuri.coding.content.controller.v1.course;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.course.ApCourseService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 小册编辑审核控制器（编辑专属）。
 * 作者只负责存稿与提交审核；申报通过、上架、发布小节、下架均由编辑在此完成。
 *
 * <p><b>权限来源已从代码白名单换成角色表</b>：本控制器原先逐个方法调用
 * {@code EditorConfig.isEditor(user.getId())}，即"谁能当编辑"这件事写死在代码里（{@code {4}}），
 * 增减一个人要改代码重新发版，也无法表达"能看不能改"。现在改由
 * {@link RequireAdminPermission} 声明所需权限点（{@code BOOKLET_MANAGE}），
 * 由 {@code AdminAuthInterceptor} 统一校验，运营账号与角色的绑定落在 {@code ap_admin_account_role} 表。
 *
 * <p>⚠️ 注解只是**声明**，真正生效的前提是本控制器的路径被注册进拦截器；
 * 见 {@code ContentWebMvcConfig#addInterceptors} 中的 {@code /api/v1/course/review/**}。
 */
@RestController
@RequestMapping("/api/v1/course/review")
@Slf4j
public class BookletReviewController {

    @Autowired
    private ApCourseService apCourseService;

    // ========== 申报审核 ==========

    /** 申报待审列表（status=1，分页/关键词） */
    @GetMapping("/apply-list")
    @RequireAdminPermission(AdminPermission.BOOKLET_MANAGE)
    public ResponseResult applyList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        return apCourseService.reviewList(page, size, (byte) 1, keyword);
    }

    /** 通过申报：1→4（开通写作权限） */
    @PostMapping("/apply-approve")
    @RequireAdminPermission(AdminPermission.BOOKLET_MANAGE)
    public ResponseResult approveApply(@RequestBody Map<String, Object> params) {
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        return apCourseService.editorTransition(courseId, (byte) 4, null);
    }

    /** 拒绝申报：1→2，写 apply_reason */
    @PostMapping("/apply-reject")
    @RequireAdminPermission(AdminPermission.BOOKLET_MANAGE)
    public ResponseResult rejectApply(@RequestBody Map<String, Object> params) {
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        String reason = params.get("reason") != null ? params.get("reason").toString() : "";
        return apCourseService.editorTransition(courseId, (byte) 2, reason);
    }

    // ========== 上架审核 ==========

    /** 上架审核/运营列表（status=5 待上架，9 已上架，3 已下架；分页/关键词） */
    @GetMapping("/publish-list")
    @RequireAdminPermission(AdminPermission.BOOKLET_MANAGE)
    public ResponseResult publishList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Byte status,
            @RequestParam(required = false) String keyword) {
        // 默认查上架待审；前端可传 5/9/3 切换
        byte targetStatus = status != null ? status : (byte) 5;
        return apCourseService.reviewList(page, size, targetStatus, keyword);
    }

    /** 上架：5→9，写 published_at，并批量发布全部草稿小节（0→1） */
    @PostMapping("/publish-approve")
    @RequireAdminPermission(AdminPermission.BOOKLET_MANAGE)
    public ResponseResult approvePublish(@RequestBody Map<String, Object> params) {
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        ResponseResult transition = apCourseService.editorTransition(courseId, (byte) 9, null);
        if (transition.getCode() != AppHttpCodeEnum.SUCCESS.getCode()) {
            return transition;
        }
        return apCourseService.publishSections(courseId, null);
    }

    /** 驳回上架：5→4，写 reason */
    @PostMapping("/publish-reject")
    @RequireAdminPermission(AdminPermission.BOOKLET_MANAGE)
    public ResponseResult rejectPublish(@RequestBody Map<String, Object> params) {
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        String reason = params.get("reason") != null ? params.get("reason").toString() : "";
        return apCourseService.editorTransition(courseId, (byte) 4, reason);
    }

    /** 发布单个/批量小节：0→1 */
    @PostMapping("/publish-section")
    @RequireAdminPermission(AdminPermission.BOOKLET_MANAGE)
    public ResponseResult publishSection(@RequestBody Map<String, Object> params) {
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
    @RequireAdminPermission(AdminPermission.BOOKLET_MANAGE)
    public ResponseResult unpublish(@RequestBody Map<String, Object> params) {
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        return apCourseService.editorTransition(courseId, (byte) 3, null);
    }
}
