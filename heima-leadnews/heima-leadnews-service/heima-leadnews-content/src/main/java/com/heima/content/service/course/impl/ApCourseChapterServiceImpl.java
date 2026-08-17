package com.heima.content.service.course.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.course.ApCourseChapterMapper;
import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.service.course.ApCourseChapterService;
import com.heima.model.course.dtos.ChapterDto;
import com.heima.model.course.dtos.ChapterSortDto;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.course.pojos.ApCourseChapter;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
@Slf4j
public class ApCourseChapterServiceImpl implements ApCourseChapterService {

    @Autowired
    private ApCourseChapterMapper chapterMapper;

    @Autowired
    private ApCourseMapper courseMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createChapter(ChapterDto dto, Long userId) {
        if (dto.getCourseId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "课程ID不能为空");
        }

        // 验证课程归属
        ApCourse course = courseMapper.selectById(dto.getCourseId());
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }
        if (!course.getAuthorId().equals(userId.intValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "只能编辑自己的课程");
        }
        if (course.getStatus() == ApCourse.Status.PUBLISHED.getCode()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "已上架课程不可编辑");
        }

        // 计算排序值
        int sortOrder = dto.getSortOrder() != null ? dto.getSortOrder() : getNextSortOrder(dto.getCourseId());

        ApCourseChapter chapter = new ApCourseChapter();
        chapter.setCourseId(dto.getCourseId());
        chapter.setTitle(dto.getTitle() != null ? dto.getTitle() : "未命名章节");
        chapter.setContent(dto.getContent() != null ? dto.getContent() : "");
        chapter.setSortOrder(sortOrder);
        chapter.setIsFree(dto.getIsFree() != null ? dto.getIsFree() : (byte) 0);
        chapter.setWordCount(dto.getContent() != null ? dto.getContent().length() : 0);
        chapter.setStatus(0);
        chapter.setEstimatedMinutes(dto.getEstimatedMinutes() != null ? dto.getEstimatedMinutes() : 5);
        chapter.setCommentCount(0);
        chapter.setCreatedTime(new Date());
        chapter.setUpdatedTime(new Date());

        chapterMapper.insert(chapter);

        // 更新课程章节数
        updateCourseChapterCount(dto.getCourseId());

        return ResponseResult.okResult(chapter);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateChapter(ChapterDto dto, Long userId) {
        if (dto.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "章节ID不能为空");
        }

        ApCourseChapter chapter = chapterMapper.selectById(dto.getId());
        if (chapter == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "章节不存在");
        }

        // 验证课程归属
        ApCourse course = courseMapper.selectById(chapter.getCourseId());
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }
        if (!course.getAuthorId().equals(userId.intValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "只能编辑自己的课程");
        }
        if (course.getStatus() == ApCourse.Status.PUBLISHED.getCode()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "已上架课程不可编辑");
        }

        if (dto.getTitle() != null) chapter.setTitle(dto.getTitle());
        if (dto.getContent() != null) {
            chapter.setContent(dto.getContent());
            chapter.setWordCount(dto.getContent().length());
        }
        if (dto.getSortOrder() != null) chapter.setSortOrder(dto.getSortOrder());
        if (dto.getIsFree() != null) chapter.setIsFree(dto.getIsFree());
        if (dto.getEstimatedMinutes() != null) chapter.setEstimatedMinutes(dto.getEstimatedMinutes());
        chapter.setUpdatedTime(new Date());

        chapterMapper.updateById(chapter);

        return ResponseResult.okResult(chapter);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult deleteChapter(Long chapterId, Long userId) {
        if (chapterId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "章节不存在");
        }

        // 验证课程归属
        ApCourse course = courseMapper.selectById(chapter.getCourseId());
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }
        if (!course.getAuthorId().equals(userId.intValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH);
        }
        if (course.getStatus() == ApCourse.Status.PUBLISHED.getCode()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "已上架课程不可编辑");
        }

        chapterMapper.deleteById(chapterId);

        // 更新课程章节数
        updateCourseChapterCount(chapter.getCourseId());

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateSort(ChapterSortDto dto, Long userId) {
        if (dto.getCourseId() == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        // 验证课程归属
        ApCourse course = courseMapper.selectById(dto.getCourseId());
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }
        if (!course.getAuthorId().equals(userId.intValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH);
        }

        for (ChapterSortDto.SortItem item : dto.getItems()) {
            ApCourseChapter chapter = chapterMapper.selectById(item.getId());
            if (chapter != null && chapter.getCourseId().equals(dto.getCourseId())) {
                chapter.setSortOrder(item.getSortOrder());
                chapter.setUpdatedTime(new Date());
                chapterMapper.updateById(chapter);
            }
        }

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    /** 获取下一个排序值 */
    private int getNextSortOrder(Long courseId) {
        LambdaQueryWrapper<ApCourseChapter> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseChapter::getCourseId, courseId);
        query.orderByDesc(ApCourseChapter::getSortOrder);
        query.last("LIMIT 1");
        ApCourseChapter last = chapterMapper.selectOne(query);
        return last != null ? last.getSortOrder() + 1 : 1;
    }

    /** 更新课程章节数 */
    private void updateCourseChapterCount(Long courseId) {
        LambdaQueryWrapper<ApCourseChapter> query = new LambdaQueryWrapper<>();
        query.eq(ApCourseChapter::getCourseId, courseId);
        long count = chapterMapper.selectCount(query);

        ApCourse course = courseMapper.selectById(courseId);
        if (course != null) {
            course.setChapterCount((int) count);
            course.setUpdatedTime(new Date());
            courseMapper.updateById(course);
        }
    }

    @Override
    public ResponseResult getChapterDetail(Long chapterId) {
        if (chapterId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "章节不存在");
        }

        return ResponseResult.okResult(chapter);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult submitForReview(Long chapterId, String note, Long userId) {
        if (chapterId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourseChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "章节不存在");
        }

        // 验证课程归属
        ApCourse course = courseMapper.selectById(chapter.getCourseId());
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }
        if (!course.getAuthorId().equals(userId.intValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "只能操作自己的小册");
        }

        // 仅草稿(0)可提交审核
        if (chapter.getStatus() != null && chapter.getStatus() == 1) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "已发布小节无需提交审核");
        }
        if (chapter.getStatus() != null && chapter.getStatus() == 2) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "该小节已在审核中");
        }

        // 草稿(0) -> 审核中(2)
        chapter.setStatus(2);
        chapter.setReviewNote(note != null ? note : "");
        chapter.setUpdatedTime(new Date());
        chapterMapper.updateById(chapter);

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }
}