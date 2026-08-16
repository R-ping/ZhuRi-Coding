package com.heima.content.service.course;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.course.dtos.CourseDto;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.common.dtos.ResponseResult;

public interface ApCourseService extends IService<ApCourse> {

    ResponseResult findList(Integer page, Integer size, Byte status);

    ResponseResult deleteById(Long id);

    ResponseResult updateStatus(Long id, Byte status, String reason);

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

    // ===== 小册申报/审核流程 =====

    /** 提交小册申报（作者，0→1），applyContent 为申报内容 JSON 字符串 */
    ResponseResult submitApply(Long courseId, String applyContent, Long userId);

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