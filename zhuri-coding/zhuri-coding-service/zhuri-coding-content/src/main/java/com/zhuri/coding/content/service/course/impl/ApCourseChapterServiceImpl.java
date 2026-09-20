package com.zhuri.coding.content.service.course.impl;

import com.zhuri.coding.content.service.aigc.AigcDetectService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.course.ApCourseChapterMapper;
import com.zhuri.coding.content.mapper.course.ApCourseMapper;
import com.zhuri.coding.content.mapper.course.ApUserCourseMapper;
import com.zhuri.coding.content.service.course.ApCourseChapterService;
import com.zhuri.coding.model.course.dtos.ChapterDto;
import com.zhuri.coding.model.course.dtos.ChapterSortDto;
import com.zhuri.coding.model.course.pojos.ApCourse;
import com.zhuri.coding.model.course.pojos.ApCourseChapter;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.model.user.pojos.ApUserCourse;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;

@Service
@Slf4j
public class ApCourseChapterServiceImpl implements ApCourseChapterService {

    @Autowired
    private ApCourseChapterMapper chapterMapper;

    @Autowired
    private ApCourseMapper courseMapper;

    @Autowired
    private AigcDetectService aigcDetectService;

    @Autowired
    private ApUserCourseMapper userCourseMapper;

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

        // Step4 内容诚信：AIGC 水文检测（同步 L1 打标，售课接口按 is_aigc 拦截）
        aigcDetectService.detectAndFlagChapter(chapter.getId());

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

        // Step4 内容诚信：内容变更后重跑检测
        aigcDetectService.detectAndFlagChapter(chapter.getId());

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

        // ---- 阅读权限校验（免费整本 / 试读节可匿名阅读；付费非试读节需登录且已购） ----
        // 1. 查询所属课程：免费整本=价格<=0（价格为空视为免费兜底）
        ApCourse course = courseMapper.selectById(chapter.getCourseId());
        boolean isFreeCourse = course == null
                || course.getPrice() == null
                || course.getPrice().compareTo(BigDecimal.ZERO) <= 0;
        // 2. 字段判定：is_free=1 表示该小节为免费/试读节
        boolean isTrialChapter = chapter.getIsFree() != null && chapter.getIsFree() == 1;

        // 免费整本或试读节：未登录也可阅读（公开只读）
        if (isFreeCourse || isTrialChapter) {
            return ResponseResult.okResult(chapter);
        }

        // 付费非试读节：需登录后操作
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 已登录但须已购买该课程（有效购买记录）
        LambdaQueryWrapper<ApUserCourse> qw = new LambdaQueryWrapper<>();
        qw.eq(ApUserCourse::getUserId, user.getId().intValue());
        qw.eq(ApUserCourse::getCourseId, chapter.getCourseId());
        qw.eq(ApUserCourse::getIsActive, (byte) 1);
        Long purchaseCount = userCourseMapper.selectCount(qw);
        if (purchaseCount == null || purchaseCount <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "该章节需购买后阅读");
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