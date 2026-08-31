package com.heima.content.service.course;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.course.dtos.AuthorProfileDto;
import com.heima.model.course.dtos.CourseDto;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.common.dtos.ResponseResult;

public interface ApCourseService extends IService<ApCourse> {

    /** 公开课程列表：仅返回已上架(9)且未删除的课程，防止泄露草稿/审核中内容 */
    ResponseResult findList(Integer page, Integer size);

    /**
     * 课程搜索：按课程标题 LIKE 分页查询已上架(9)且未删除的课程
     * @param keyword 标题关键词，null/空 表示不过滤
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return okResult(list)，list 项对齐前端搜索展示的 id/title/summary/coverImage/authorName 等字段
     */
    ResponseResult searchCourse(String keyword, Integer page, Integer size);

    ResponseResult getMyCourses(Long userId, String filter);

    ResponseResult updateProgress(Long userId, Long courseId, Long chapterId, Boolean isCompleted);

    /** 公开课程详情（含章节列表） */
    ResponseResult getPublicDetail(Long courseId);

    // ===== 课程创作管理 =====

    /** 检查用户是否有课程创作权限（逐力值 >= Lv5） */
    ResponseResult checkAuthorPermission(Long userId);

    /** 创建课程草稿 */
    ResponseResult createCourse(CourseDto dto, Long userId);

    /** 更新课程信息 */
    ResponseResult updateCourse(CourseDto dto, Long userId);

    /** 作者课程管理列表 */
    ResponseResult manageList(Integer page, Integer size, Byte status, String keyword, Long userId);

    /** 课程编辑详情（含所有章节） */
    ResponseResult manageDetail(Long courseId, Long userId);

    /** 软删除课程 */
    ResponseResult softDelete(Long courseId, Long userId);

    /** 作者下架自己的已上架课程（作者，9→3，走状态机校验） */
    ResponseResult authorUnpublish(Long courseId, Long userId);

    // ===== 小册申报/审核流程 =====

    /**
     * 提交小册申报（作者，0→1）。
     * @param courseId 小册课程ID
     * @param applyContent 申请单 JSON（主题/介绍/目标/大纲/进度/样章/渠道/联系方式等），落 apply_content
     * @param authorProfile 作者基础信息（姓名/职位/履历等），落 ap_author_profile（同事务 upsert）
     * @param userId 作者用户ID
     */
    ResponseResult submitApply(Long courseId, String applyContent, AuthorProfileDto authorProfile, Long userId);

    /** 提交上架审核（作者，4→5） */
    ResponseResult submitForReview(Long courseId, Long userId);

    /** 我的小册列表（作者） */
    ResponseResult getMyBooklets(Long userId, Integer page, Integer size, Byte status);

    /** 编辑状态迁移（编辑专属，走状态机校验），返回成功后课程状态已更新 */
    ResponseResult editorTransition(Long courseId, byte targetStatus, String reason);

    /** 编辑审核列表（按状态 + 关键词，不分作者，用于申报/上架待审列表） */
    ResponseResult reviewList(Integer page, Integer size, Byte status, String keyword);

    /** 编辑发布小节（0→1）；chapterIds 为空则发布该小册全部草稿小节 */
    ResponseResult publishSections(Long courseId, java.util.List<Long> chapterIds);
}