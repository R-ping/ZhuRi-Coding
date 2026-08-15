package com.heima.content.service.course.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.content.mapper.course.ApCourseChapterMapper;
import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.mapper.course.ApCourseReadingProgressMapper;
import com.heima.content.mapper.course.ApUserCourseMapper;
import com.heima.content.service.course.ApCourseService;
import com.heima.content.service.level.LevelService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.course.dtos.CourseDto;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.course.pojos.ApCourseChapter;
import com.heima.model.course.pojos.ApCourseReadingProgress;
import com.heima.model.level.pojos.ApUserLevel;
import com.heima.model.user.pojos.ApUserCourse;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ApCourseServiceImpl extends ServiceImpl<ApCourseMapper, ApCourse> implements ApCourseService {

    @Autowired
    private ApUserCourseMapper userCourseMapper;

    @Autowired
    private ApCourseReadingProgressMapper readingProgressMapper;

    @Autowired
    private ApCourseChapterMapper chapterMapper;

    @Autowired
    private LevelService levelService;

    private static final int COURSE_AUTHOR_REQUIRED_POWER_LEVEL = 7;

    @Override
    public ResponseResult findList(Integer page, Integer size, Byte status) {
        IPage<ApCourse> iPage = new Page<>(page, size);
        LambdaQueryWrapper<ApCourse> queryWrapper = new LambdaQueryWrapper<>();
        
        if (status != null) {
            queryWrapper.eq(ApCourse::getStatus, status);
        }
        
        queryWrapper.orderByDesc(ApCourse::getCreatedTime);
        
        IPage<ApCourse> resultPage = page(iPage, queryWrapper);
        
        Map<String, Object> data = new HashMap<>();
        data.put("list", resultPage.getRecords());
        data.put("total", resultPage.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult deleteById(Long id) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        
        boolean deleted = removeById(id);
        
        if (deleted) {
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        }
        
        return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
    }

    @Override
    public ResponseResult updateStatus(Long id, Byte status, String reason) {
        if (id == null || status == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        
        ApCourse apCourse = getById(id);
        
        if (apCourse == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        
        apCourse.setStatus(status);
        apCourse.setReason(reason);
        
        boolean updated = updateById(apCourse);
        
        if (updated) {
            return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
        }
        
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR);
    }

    @Override
    public ResponseResult getMyCourses(Long userId, String filter) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        LambdaQueryWrapper<ApUserCourse> ucQuery = new LambdaQueryWrapper<>();
        ucQuery.eq(ApUserCourse::getUserId, userId.intValue());
        ucQuery.eq(ApUserCourse::getIsActive, (byte) 1);

        if ("purchased".equals(filter)) {
            ucQuery.eq(ApUserCourse::getAccessType, 1);
        } else if ("vip".equals(filter)) {
            ucQuery.eq(ApUserCourse::getAccessType, 2);
        }

        ucQuery.orderByDesc(ApUserCourse::getLastLearnAt);
        List<ApUserCourse> userCourses = userCourseMapper.selectList(ucQuery);

        if (userCourses.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("list", Collections.emptyList());
            result.put("total", 0);
            return ResponseResult.okResult(result);
        }

        List<Long> courseIds = userCourses.stream()
                .map(ApUserCourse::getCourseId)
                .collect(Collectors.toList());

        LambdaQueryWrapper<ApCourse> courseQuery = new LambdaQueryWrapper<>();
        courseQuery.in(ApCourse::getId, courseIds);
        List<ApCourse> courses = list(courseQuery);
        Map<Long, ApCourse> courseMap = courses.stream()
                .collect(Collectors.toMap(ApCourse::getId, c -> c));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        List<Map<String, Object>> list = new ArrayList<>();
        for (ApUserCourse uc : userCourses) {
            ApCourse course = courseMap.get(uc.getCourseId());
            if (course == null) continue;

            Map<String, Object> item = new HashMap<>();
            item.put("id", course.getId());
            item.put("title", course.getTitle());
            item.put("subtitle", course.getSubtitle());
            item.put("coverImage", course.getCoverImage());
            item.put("authorName", course.getAuthorName());
            item.put("authorId", course.getAuthorId());
            item.put("price", course.getPrice());
            item.put("originalPrice", course.getOriginalPrice());
            item.put("chapterCount", course.getChapterCount());
            item.put("studyCount", course.getStudyCount());
            item.put("categoryId", course.getCategoryId());
            item.put("progress", uc.getProgress() != null ? uc.getProgress() : BigDecimal.ZERO);
            item.put("accessType", uc.getAccessType());
            item.put("isTrial", uc.getIsTrial() != null ? uc.getIsTrial() : 0);
            item.put("borrowExpireAt", uc.getBorrowExpireAt() != null ? sdf.format(uc.getBorrowExpireAt()) : null);
            item.put("lastLearnAt", uc.getLastLearnAt() != null ? sdf.format(uc.getLastLearnAt()) : null);
            item.put("lastLearnChapterId", uc.getLastLearnChapterId());
            list.add(item);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", list.size());
        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult updateProgress(Long userId, Long courseId, Long chapterId, Boolean isCompleted) {
        if (userId == null || courseId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        LambdaQueryWrapper<ApUserCourse> ucQuery = new LambdaQueryWrapper<>();
        ucQuery.eq(ApUserCourse::getUserId, userId.intValue());
        ucQuery.eq(ApUserCourse::getCourseId, courseId);
        ucQuery.eq(ApUserCourse::getIsActive, (byte) 1);
        ApUserCourse userCourse = userCourseMapper.selectOne(ucQuery);

        if (userCourse == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "您未拥有该课程");
        }

        if (chapterId != null) {
            LambdaQueryWrapper<ApCourseReadingProgress> rpQuery = new LambdaQueryWrapper<>();
            rpQuery.eq(ApCourseReadingProgress::getUserId, userId.intValue());
            rpQuery.eq(ApCourseReadingProgress::getChapterId, chapterId);
            ApCourseReadingProgress progress = readingProgressMapper.selectOne(rpQuery);

            if (progress == null) {
                progress = new ApCourseReadingProgress();
                progress.setUserId(userId.intValue());
                progress.setChapterId(chapterId);
                progress.setProgress(100f);
                progress.setIsCompleted(isCompleted != null && isCompleted ? 1 : 0);
                progress.setCompletedAt(isCompleted != null && isCompleted ? new Date() : null);
                progress.setLastReadAt(new Date());
                readingProgressMapper.insert(progress);
            } else {
                progress.setProgress(100f);
                if (isCompleted != null && isCompleted) {
                    progress.setIsCompleted(1);
                    progress.setCompletedAt(new Date());
                }
                progress.setLastReadAt(new Date());
                readingProgressMapper.updateById(progress);
            }

            ApCourse course = getById(courseId);
            if (course != null && course.getChapterCount() != null && course.getChapterCount() > 0) {
                LambdaQueryWrapper<ApCourseReadingProgress> countQuery = new LambdaQueryWrapper<>();
                countQuery.eq(ApCourseReadingProgress::getUserId, userId.intValue());
                countQuery.in(ApCourseReadingProgress::getChapterId, 
                    chapterMapper.selectList(
                        new LambdaQueryWrapper<ApCourseChapter>()
                            .eq(ApCourseChapter::getCourseId, courseId)
                            .select(ApCourseChapter::getId)
                    ).stream().map(ApCourseChapter::getId).collect(Collectors.toList())
                );
                countQuery.eq(ApCourseReadingProgress::getIsCompleted, 1);
                long completedCount = readingProgressMapper.selectCount(countQuery);

                BigDecimal newProgress = BigDecimal.valueOf(completedCount * 100.0 / course.getChapterCount())
                        .setScale(2, BigDecimal.ROUND_HALF_UP);
                userCourse.setProgress(newProgress);
            }
        }

        userCourse.setLastLearnChapterId(chapterId);
        userCourse.setLastLearnAt(new Date());
        userCourseMapper.updateById(userCourse);

        Map<String, Object> result = new HashMap<>();
        result.put("progress", userCourse.getProgress());
        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult getPublicDetail(Long courseId) {
        if (courseId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourse course = getById(courseId);
        if (course == null || course.getIsDeleted() == 1) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        // 查询所有章节
        LambdaQueryWrapper<ApCourseChapter> chapterQuery = new LambdaQueryWrapper<>();
        chapterQuery.eq(ApCourseChapter::getCourseId, courseId);
        chapterQuery.orderByAsc(ApCourseChapter::getSortOrder);
        List<ApCourseChapter> chapters = chapterMapper.selectList(chapterQuery);

        Map<String, Object> result = new HashMap<>();
        result.put("course", course);
        result.put("chapters", chapters != null ? chapters : Collections.emptyList());
        return ResponseResult.okResult(result);
    }

    // ==================== 课程创作管理 ====================

    @Override
    public ResponseResult checkAuthorPermission(Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        ApUserLevel userLevel = levelService.getUserLevel(userId);
        int powerLevel = userLevel.getPowerLevel() != null ? userLevel.getPowerLevel() : 1;
        boolean hasPermission = powerLevel >= COURSE_AUTHOR_REQUIRED_POWER_LEVEL;

        Map<String, Object> result = new HashMap<>();
        result.put("hasPermission", hasPermission);
        result.put("powerLevel", powerLevel);
        result.put("requiredLevel", COURSE_AUTHOR_REQUIRED_POWER_LEVEL);
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createCourse(CourseDto dto, Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        // 权限检查
        ApUserLevel userLevel = levelService.getUserLevel(userId);
        if (userLevel.getPowerLevel() == null || userLevel.getPowerLevel() < COURSE_AUTHOR_REQUIRED_POWER_LEVEL) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH,
                    "课程创作权限需要逐力值 Lv" + COURSE_AUTHOR_REQUIRED_POWER_LEVEL + "，继续努力吧！");
        }

        ApCourse course = new ApCourse();
        course.setTitle(dto.getTitle() != null ? dto.getTitle() : "未命名课程");
        course.setSubtitle(dto.getSubtitle() != null ? dto.getSubtitle() : "");
        course.setDescription(dto.getDescription() != null ? dto.getDescription() : "");
        course.setCoverImage(dto.getCoverImage() != null ? dto.getCoverImage() : "");
        course.setPrice(dto.getPrice() != null ? dto.getPrice() : BigDecimal.ZERO);
        course.setOriginalPrice(dto.getOriginalPrice() != null ? dto.getOriginalPrice() : BigDecimal.ZERO);
        course.setCategoryId(dto.getCategoryId() != null ? dto.getCategoryId() : 0);
        course.setAuthorId(userId.intValue());
        course.setAuthorName("");
        course.setAuthorAvatar("");
        course.setStatus((byte) 0);
        course.setChapterCount(0);
        course.setStudyCount(0);
        course.setEstimatedHours(BigDecimal.ZERO);
        course.setIsDeleted(0);
        course.setVersion(1);
        course.setSalesCount(0);
        course.setTotalRevenue(BigDecimal.ZERO);
        course.setCreatedTime(new Date());
        course.setUpdatedTime(new Date());

        save(course);

        Map<String, Object> result = new HashMap<>();
        // 雪花ID超过 JS Number 安全整数范围，必须转字符串返回，避免前端精度丢失
        result.put("id", String.valueOf(course.getId()));
        result.put("title", course.getTitle());
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateCourse(CourseDto dto, Long userId) {
        if (dto.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourse course = getById(dto.getId());
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        if (!course.getAuthorId().equals(userId.intValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "只能编辑自己的课程");
        }

        if (course.getStatus() == ApCourse.Status.PUBLISHED.getCode()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "已上架课程不可编辑，请先下架");
        }

        if (dto.getTitle() != null) course.setTitle(dto.getTitle());
        if (dto.getSubtitle() != null) course.setSubtitle(dto.getSubtitle());
        if (dto.getDescription() != null) course.setDescription(dto.getDescription());
        if (dto.getCoverImage() != null) course.setCoverImage(dto.getCoverImage());
        if (dto.getPrice() != null) course.setPrice(dto.getPrice());
        if (dto.getOriginalPrice() != null) course.setOriginalPrice(dto.getOriginalPrice());
        if (dto.getCategoryId() != null) course.setCategoryId(dto.getCategoryId());
        course.setUpdatedTime(new Date());

        updateById(course);

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    @Override
    public ResponseResult manageList(Integer page, Integer size, Byte status, String keyword, Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }

        IPage<ApCourse> iPage = new Page<>(page, size);
        LambdaQueryWrapper<ApCourse> query = new LambdaQueryWrapper<>();
        query.eq(ApCourse::getAuthorId, userId.intValue());
        query.eq(ApCourse::getIsDeleted, 0);

        if (status != null) {
            query.eq(ApCourse::getStatus, status);
        }

        if (keyword != null && !keyword.trim().isEmpty()) {
            query.like(ApCourse::getTitle, keyword.trim());
        }

        query.orderByDesc(ApCourse::getUpdatedTime);

        IPage<ApCourse> resultPage = page(iPage, query);

        Map<String, Object> data = new HashMap<>();
        data.put("list", resultPage.getRecords());
        data.put("total", resultPage.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult manageDetail(Long courseId, Long userId) {
        if (courseId == null || userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourse course = getById(courseId);
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        if (!course.getAuthorId().equals(userId.intValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "只能查看自己的课程");
        }

        // 查询所有章节
        LambdaQueryWrapper<ApCourseChapter> chapterQuery = new LambdaQueryWrapper<>();
        chapterQuery.eq(ApCourseChapter::getCourseId, courseId);
        chapterQuery.orderByAsc(ApCourseChapter::getSortOrder);
        List<ApCourseChapter> chapters = chapterMapper.selectList(chapterQuery);

        Map<String, Object> result = new HashMap<>();
        result.put("course", course);
        result.put("chapters", chapters != null ? chapters : Collections.emptyList());
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult softDelete(Long courseId, Long userId) {
        if (courseId == null || userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourse course = getById(courseId);
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        if (!course.getAuthorId().equals(userId.intValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "只能删除自己的课程");
        }

        course.setIsDeleted(1);
        course.setUpdatedTime(new Date());
        updateById(course);

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }
}