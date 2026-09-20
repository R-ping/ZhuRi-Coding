package com.zhuri.coding.content.service.course.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.course.ApCourseChapterMapper;
import com.zhuri.coding.content.mapper.course.ApCourseMapper;
import com.zhuri.coding.content.mapper.course.ApCourseReadingProgressMapper;
import com.zhuri.coding.content.mapper.course.ApUserCourseMapper;
import com.zhuri.coding.content.service.course.ApCourseService;
import com.zhuri.coding.content.service.course.AuthorProfileService;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.course.dtos.AuthorProfileDto;
import com.zhuri.coding.model.course.dtos.CourseDto;
import com.zhuri.coding.model.course.pojos.ApCourse;
import com.zhuri.coding.model.course.pojos.ApCourseChapter;
import com.zhuri.coding.model.course.pojos.ApCourseReadingProgress;
import com.zhuri.coding.model.level.pojos.ApUserLevel;
import com.zhuri.coding.model.user.pojos.ApUserCourse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.commons.lang3.StringUtils;
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
    private IUserClient userClient;

    @Autowired
    private LevelService levelService;

    @Autowired
    private AuthorProfileService authorProfileService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int COURSE_AUTHOR_REQUIRED_POWER_LEVEL = 7;
    /** 申请单主题（小册标题）最大字数 */
    private static final int APPLY_TITLE_MAX_LEN = 20;

    @Override
    public ResponseResult findList(Integer page, Integer size) {
        IPage<ApCourse> iPage = new Page<>(page, size);
        LambdaQueryWrapper<ApCourse> queryWrapper = new LambdaQueryWrapper<>();
        // 公开列表仅返回已上架(9)且未删除的课程，防止泄露草稿/审核中/已下架内容
        queryWrapper.eq(ApCourse::getIsDeleted, 0);
        queryWrapper.eq(ApCourse::getStatus, ApCourse.Status.PUBLISHED.getCode());

        queryWrapper.orderByDesc(ApCourse::getCreatedTime);
        
        IPage<ApCourse> resultPage = page(iPage, queryWrapper);
        
        Map<String, Object> data = new HashMap<>();
        data.put("list", resultPage.getRecords());
        data.put("total", resultPage.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult searchCourse(String keyword, Integer page, Integer size) {
        // 分页参数兜底
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 10 : Math.min(size, 50);

        IPage<ApCourse> iPage = new Page<>(safePage, safeSize);
        LambdaQueryWrapper<ApCourse> queryWrapper = new LambdaQueryWrapper<>();
        // 课程搜索仅返回已上架(9)且未删除的课程，防止泄露草稿/审核中内容
        queryWrapper.eq(ApCourse::getIsDeleted, 0);
        queryWrapper.eq(ApCourse::getStatus, ApCourse.Status.PUBLISHED.getCode());
        if (StringUtils.isNotBlank(keyword)) {
            queryWrapper.like(ApCourse::getTitle, keyword.trim());
        }
        queryWrapper.orderByDesc(ApCourse::getCreatedTime);

        IPage<ApCourse> resultPage = page(iPage, queryWrapper);

        // 组装与课程列表一致的返回字段（雪花ID超 JS 安全整数，转为字符串避免前端精度丢失）
        List<Map<String, Object>> list = new ArrayList<>();
        for (ApCourse course : resultPage.getRecords()) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", String.valueOf(course.getId()));
            item.put("title", course.getTitle());
            item.put("subtitle", course.getSubtitle());
            item.put("summary", course.getDescription());
            item.put("coverImage", course.getCoverImage());
            item.put("authorId", course.getAuthorId());
            item.put("authorName", course.getAuthorName());
            item.put("authorAvatar", course.getAuthorAvatar());
            item.put("price", course.getPrice());
            item.put("originalPrice", course.getOriginalPrice());
            item.put("chapterCount", course.getChapterCount());
            item.put("studyCount", course.getStudyCount());
            list.add(item);
        }
        return ResponseResult.okResult(list);
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
        // 公开详情仅对已上架(9)课程可见，防止泄露写作中/审核中小册内容
        if (course.getStatus() != ApCourse.Status.PUBLISHED.getCode()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        // 查询已发布(1)的章节（未发布小节不外泄）
        LambdaQueryWrapper<ApCourseChapter> chapterQuery = new LambdaQueryWrapper<>();
        chapterQuery.eq(ApCourseChapter::getCourseId, courseId);
        chapterQuery.eq(ApCourseChapter::getStatus, 1);
        chapterQuery.orderByAsc(ApCourseChapter::getSortOrder);
        List<ApCourseChapter> chapters = chapterMapper.selectList(chapterQuery);

        // 作者头像：优先拉取用户真实头像（用户改头像后课程冗余头像会过期），失败/为空时回退课程冗余头像
        if (StringUtils.isBlank(course.getAuthorAvatar()) && course.getAuthorId() != null) {
            try {
                ResponseResult userResult = userClient.getPublicInfo(course.getAuthorId().longValue());
                if (userResult != null && userResult.getCode() == 200 && userResult.getData() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> userData = (Map<String, Object>) userResult.getData();
                    if (userData.get("avatar") != null && StringUtils.isNotBlank(userData.get("avatar").toString())) {
                        course.setAuthorAvatar(userData.get("avatar").toString());
                    }
                }
            } catch (Exception e) {
                log.warn("获取课程作者头像失败, courseId={}, authorId={}", courseId, course.getAuthorId(), e);
            }
        }

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult authorUnpublish(Long courseId, Long userId) {
        if (courseId == null || userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourse course = getById(courseId);
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        // 状态机校验：仅作者可下架自己的已上架(9)课程
        String error = transitionTo(course, (byte) 3, userId.intValue(), false);
        if (error != null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, error);
        }

        course.setStatus((byte) 3);
        course.setUpdatedTime(new Date());
        updateById(course);

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    // ==================== 小册申报/审核流程 ====================

    /**
     * 状态迁移校验（集中约束，禁止非法跳转）
     * @param course 当前课程
     * @param targetStatus 目标状态
     * @param operatorId 操作人ID
     * @param isEditor 是否为编辑操作
     * @return 错误信息，null 表示允许迁移
     */
    private String transitionTo(ApCourse course, byte targetStatus, Integer operatorId, boolean isEditor) {
        byte current = course.getStatus();

        if (!isEditor) {
            // 作者只能操作自己的课程
            if (!course.getAuthorId().equals(operatorId)) {
                return "只能操作自己的课程";
            }
            // 草稿(0) -> 申报待审(1)：提交申报
            if (current == 0 && targetStatus == 1) return null;
            // 申报被拒(2) -> 申报待审(1)：作者修改后重提申报
            if (current == 2 && targetStatus == 1) return null;
            // 写作中(4) -> 上架待审(5)：提交上架审核
            if (current == 4 && targetStatus == 5) return null;
            // 已上架(9) -> 已下架(3)：作者下架自己的已上架课程
            if (current == 9 && targetStatus == 3) return null;
            return "非法状态迁移";
        }

        // 编辑操作
        // 申报待审(1) -> 写作中(4)：通过申报
        if (current == 1 && targetStatus == 4) return null;
        // 申报待审(1) -> 申报被拒(2)：拒绝申报
        if (current == 1 && targetStatus == 2) return null;
        // 上架待审(5) -> 已上架(9)：上架
        if (current == 5 && targetStatus == 9) return null;
        // 上架待审(5) -> 写作中(4)：驳回上架
        if (current == 5 && targetStatus == 4) return null;
        // 已上架(9) -> 已下架(3)：下架
        if (current == 9 && targetStatus == 3) return null;
        // 已下架(3) -> 已上架(9)：重新上架
        if (current == 3 && targetStatus == 9) return null;
        return "非法状态迁移";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult submitApply(Long courseId, String applyContent, AuthorProfileDto authorProfile, Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        // 校验申请单 JSON：主题长度 ≤ 20 字、申请渠道必须在允许集合内（仅当提交了申请单内容时校验）
        String applyValidateError = validateApplyContent(applyContent);
        if (applyValidateError != null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, applyValidateError);
        }

        // 申请主题（用于新建草稿/校验）；无申请单时用空串
        String applyTitle = "";
        try {
            if (StringUtils.isNotBlank(applyContent)) {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(applyContent);
                if (node.hasNonNull("title")) {
                    applyTitle = node.get("title").asText().trim();
                }
            }
        } catch (Exception ignored) {
            // 申请单已被 validateApplyContent 校验，解析失败不会走到这里
        }

        ApCourse course;
        if (courseId == null) {
            // 申请入口可能由未达标作者发起（申请即开通资格），此处新建草稿不校验 Lv7
            course = createApplyDraftCourse(applyTitle, userId.intValue());
        } else {
            course = getById(courseId);
            if (course == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
            }
            // 校验状态迁移（草稿0→申报1，或申报被拒2→重提1）
            String error = transitionTo(course, (byte) 1, userId.intValue(), false);
            if (error != null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, error);
            }
        }

        // 同事务保存作者基础信息（ap_author_profile，按 user_id upsert，允许覆盖；仅当提交了基础信息时）
        if (authorProfile != null) {
            ResponseResult profileResult = authorProfileService.saveProfile(userId.intValue(), authorProfile);
            if (profileResult != null && profileResult.getCode() != AppHttpCodeEnum.SUCCESS.getCode()) {
                return profileResult;
            }
        }

        course.setStatus((byte) 1);
        if (StringUtils.isNotBlank(applyContent)) {
            // 申请单完整 JSON 独立存储，不覆盖 description（小册介绍）
            course.setApplyContent(applyContent);
        }
        course.setApplyTime(new Date());
        course.setUpdatedTime(new Date());
        updateById(course);

        Map<String, Object> result = new HashMap<>();
        result.put("id", String.valueOf(course.getId()));
        return ResponseResult.okResult(result);
    }

    /**
     * 为申请作者新建一条小册草稿（不校验逐力值等级；申请即开通资格）
     * @param title 申请主题（小册标题）
     * @param authorId 作者用户ID
     * @return 新建草稿课程
     */
    private ApCourse createApplyDraftCourse(String title, int authorId) {
        ApCourse draft = new ApCourse();
        draft.setTitle(StringUtils.isNotBlank(title) ? title : "未命名小册");
        draft.setSubtitle("");
        draft.setDescription("");
        draft.setCoverImage("");
        draft.setPrice(BigDecimal.ZERO);
        draft.setOriginalPrice(BigDecimal.ZERO);
        draft.setCategoryId(0);
        draft.setAuthorId(authorId);
        draft.setAuthorName("");
        draft.setAuthorAvatar("");
        draft.setStatus((byte) 0);
        draft.setChapterCount(0);
        draft.setStudyCount(0);
        draft.setEstimatedHours(BigDecimal.ZERO);
        draft.setIsDeleted(0);
        draft.setVersion(1);
        draft.setSalesCount(0);
        draft.setTotalRevenue(BigDecimal.ZERO);
        draft.setCreatedTime(new Date());
        draft.setUpdatedTime(new Date());
        save(draft);
        return draft;
    }

    /**
     * 校验申请单 JSON：
     * - title（小册主题）非空且长度 ≤ 20 字
     * - channel（申请渠道）必须在允许集合内（参照掘金小册官方渠道）
     * @param applyContent 申请单 JSON；为 null/空 时跳过校验（兼容仅切换状态的旧提交审核流程）
     * @return 校验失败原因；校验通过或无需校验返回 null
     */
    private String validateApplyContent(String applyContent) {
        if (StringUtils.isBlank(applyContent)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(applyContent);

            String title = node.hasNonNull("title") ? node.get("title").asText().trim() : "";
            if (StringUtils.isBlank(title)) {
                return "小册主题不能为空";
            }
            if (title.length() > APPLY_TITLE_MAX_LEN) {
                return "小册主题不能超过" + APPLY_TITLE_MAX_LEN + "字";
            }

            String channel = node.hasNonNull("channel") ? node.get("channel").asText().trim() : "";
            if (StringUtils.isBlank(channel)) {
                return "申请渠道不能为空";
            }
            if (!ALLOWED_APPLY_CHANNELS.contains(channel)) {
                return "申请渠道不合法";
            }
            return null;
        } catch (Exception e) {
            return "申请单格式错误";
        }
    }

    /** 允许的申请渠道（与掘金小册官方渠道一致） */
    private static final java.util.Set<String> ALLOWED_APPLY_CHANNELS = java.util.Set.of(
            "掘金小册微信公众号",
            "《如何写一本掘金小册》小册文章",
            "小册姐微信",
            "掘金社区LV7级及以上用户",
            "经推荐人介绍和推荐",
            "其他");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult submitForReview(Long courseId, Long userId) {
        if (courseId == null || userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourse course = getById(courseId);
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        // 写作中(4) -> 上架待审(5)
        String error = transitionTo(course, (byte) 5, userId.intValue(), false);
        if (error != null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, error);
        }

        course.setStatus((byte) 5);
        course.setUpdatedTime(new Date());
        updateById(course);

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    @Override
    public ResponseResult getMyBooklets(Long userId, Integer page, Integer size, Byte status) {
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

        query.orderByDesc(ApCourse::getUpdatedTime);

        IPage<ApCourse> resultPage = page(iPage, query);

        Map<String, Object> data = new HashMap<>();
        data.put("list", resultPage.getRecords());
        data.put("total", resultPage.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult editorTransition(Long courseId, byte targetStatus, String reason) {
        if (courseId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourse course = getById(courseId);
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        // 状态机校验（编辑白名单校验在 Controller 完成，此处只做状态迁移约束）
        String error = transitionTo(course, targetStatus, null, true);
        if (error != null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, error);
        }

        course.setStatus(targetStatus);
        if (reason != null && !reason.isEmpty()) {
            // 申报被拒原因写 apply_reason，上架驳回原因写 reason
            if (targetStatus == 2) {
                course.setApplyReason(reason);
            } else {
                course.setReason(reason);
            }
        }
        if (targetStatus == 9) {
            course.setPublishedAt(new Date());
        }
        course.setReviewTime(new Date());
        course.setUpdatedTime(new Date());
        updateById(course);

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    @Override
    public ResponseResult reviewList(Integer page, Integer size, Byte status, String keyword) {
        IPage<ApCourse> iPage = new Page<>(page, size);
        LambdaQueryWrapper<ApCourse> query = new LambdaQueryWrapper<>();
        query.eq(ApCourse::getStatus, status);
        query.eq(ApCourse::getIsDeleted, 0);

        if (keyword != null && !keyword.trim().isEmpty()) {
            query.like(ApCourse::getTitle, keyword.trim());
        }

        query.orderByDesc(ApCourse::getApplyTime);
        query.orderByDesc(ApCourse::getUpdatedTime);

        IPage<ApCourse> resultPage = page(iPage, query);

        Map<String, Object> data = new HashMap<>();
        data.put("list", resultPage.getRecords());
        data.put("total", resultPage.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult publishSections(Long courseId, List<Long> chapterIds) {
        if (courseId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        ApCourse course = getById(courseId);
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        LambdaQueryWrapper<ApCourseChapter> chapterQuery = new LambdaQueryWrapper<>();
        chapterQuery.eq(ApCourseChapter::getCourseId, courseId);
        if (chapterIds != null && !chapterIds.isEmpty()) {
            chapterQuery.in(ApCourseChapter::getId, chapterIds);
        }
        List<ApCourseChapter> chapters = chapterMapper.selectList(chapterQuery);

        if (chapters.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "无可发布的小节");
        }

        Date now = new Date();
        for (ApCourseChapter ch : chapters) {
            ch.setStatus(1);
            ch.setUpdatedTime(now);
            chapterMapper.updateById(ch);
        }

        log.info("编辑发布小节: courseId={}, count={}", courseId, chapters.size());
        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }
}