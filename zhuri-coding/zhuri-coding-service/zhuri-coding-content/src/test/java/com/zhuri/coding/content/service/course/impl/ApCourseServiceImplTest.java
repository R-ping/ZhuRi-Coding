package com.zhuri.coding.content.service.course.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.course.ApCourseChapterMapper;
import com.zhuri.coding.content.mapper.course.ApCourseMapper;
import com.zhuri.coding.content.mapper.course.ApCourseReadingProgressMapper;
import com.zhuri.coding.content.mapper.course.ApUserCourseMapper;
import com.zhuri.coding.content.service.course.AuthorProfileService;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.course.dtos.CourseDto;
import com.zhuri.coding.model.course.pojos.ApCourse;
import com.zhuri.coding.model.course.pojos.ApCourseChapter;
import com.zhuri.coding.model.course.pojos.ApCourseReadingProgress;
import com.zhuri.coding.model.level.pojos.ApUserLevel;
import com.zhuri.coding.model.user.pojos.ApUserCourse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApCourseServiceImpl 单元测试（小册/课程全流程：公开、购买、进度、创作管理、申报与状态机）
 *
 * 通过 @Mock ApCourseMapper 注入 ServiceImpl 的 baseMapper，mock getById/save/updateById/selectPage。
 * 覆盖：
 * - 公开侧：findList 仅上架、getPublicDetail 已上架+作者头像回退、getMyCourses 三类过滤器；
 * - 进度侧：updateProgress 未拥有/新建/更新/完成率重算；
 * - 创作侧：checkAuthorPermission、createCourse 权限、updateCourse 归属/上架保护、softDelete；
 * - 申报流：submitApply 的 applyContent 校验(空主题/超长/非法渠道/非法JSON)、建稿/重提；
 * - 状态机：submitForReview、authorUnpublish、editorTransition 的合法/非法跳转。
 */
class ApCourseServiceImplTest {

    @Mock
    private ApCourseMapper baseMapper;
    @Mock
    private ApUserCourseMapper userCourseMapper;
    @Mock
    private ApCourseReadingProgressMapper readingProgressMapper;
    @Mock
    private ApCourseChapterMapper chapterMapper;
    @Mock
    private IUserClient userClient;
    @Mock
    private LevelService levelService;
    @Mock
    private AuthorProfileService authorProfileService;

    @InjectMocks
    private ApCourseServiceImpl apCourseService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Mockito 无法自动把 @Mock 注入到 ServiceImpl 的 baseMapper（类型擦除/私有字段），
        // 不同 MP 版本的 setBaseMapper 存在差异，此处统一通过反射直接写私有字段 baseMapper，
        // 使 getById/save/updateById/page 走 mock，规避跨版本兼容问题。
        injectBaseMapper(apCourseService, baseMapper);
        // 预热 MybatisPlus 实体表元数据，使单测自足、CI 无库也能稳定运行
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCourse.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserCourse.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCourseReadingProgress.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCourseChapter.class);
    }

    // ---------- 辅助 ----------

    /** 通过反射把 mock 的 Mapper 写入 ServiceImpl 的私有字段 baseMapper，规避 MP 版本差异导致的注入失败。 */
    private void injectBaseMapper(Object service, Object mapper) {
        // 3.5.12 起 baseMapper 上移至父类 CrudRepository，ReflectionTestUtils 沿继承链查找
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    private ApCourse course(long id, int authorId, byte status) {
        ApCourse c = new ApCourse();
        c.setId(id);
        c.setAuthorId(authorId);
        c.setStatus(status);
        c.setIsDeleted(0);
        c.setTitle("测试小册");
        c.setAuthorAvatar("");
        c.setChapterCount(2);
        return c;
    }

    private ApUserLevel level(int powerLevel) {
        ApUserLevel ul = new ApUserLevel();
        ul.setPowerLevel(powerLevel);
        return ul;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseResult r) {
        return (Map<String, Object>) r.getData();
    }

    // ==================== 公开列表 ====================

    @Test
    @DisplayName("findList - 仅返回已上架未删除课程")
    void testFindList() {
        ApCourse c = course(1L, 10, ApCourse.Status.PUBLISHED.getCode());
        Page<ApCourse> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(c));
        page.setTotal(1);
        when(baseMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        ResponseResult r = apCourseService.findList(1, 10);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals(1, ((List<?>) dataOf(r).get("list")).size());
        assertEquals(1L, ((Number) dataOf(r).get("total")).longValue());
    }

    // ==================== 我的课程 ====================

    @Test
    @DisplayName("getMyCourses - 未登录")
    void testGetMyCoursesNeedLogin() {
        ResponseResult r = apCourseService.getMyCourses(null, "purchased");
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), r.getCode());
    }

    @Test
    @DisplayName("getMyCourses - 空列表")
    void testGetMyCoursesEmpty() {
        when(userCourseMapper.selectList(any())).thenReturn(Collections.emptyList());
        ResponseResult r = apCourseService.getMyCourses(userId, "purchased");
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals(0, ((List<?>) dataOf(r).get("list")).size());
        assertEquals(0, dataOf(r).get("total"));
    }

    @Test
    @DisplayName("getMyCourses - 购买过滤器返回关联课程")
    void testGetMyCoursesPurchased() {
        ApUserCourse uc = new ApUserCourse();
        uc.setCourseId(1L);
        uc.setProgress(BigDecimal.valueOf(50));
        uc.setAccessType(1);
        when(userCourseMapper.selectList(any())).thenReturn(Collections.singletonList(uc));
        when(baseMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(course(1L, 10, ApCourse.Status.PUBLISHED.getCode())));

        ResponseResult r = apCourseService.getMyCourses(userId, "purchased");

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        List<?> list = (List<?>) dataOf(r).get("list");
        assertEquals(1, list.size());
        assertEquals(1L, ((Map<?, ?>) list.get(0)).get("id"));
    }

    // ==================== 学习进度 ====================

    @Test
    @DisplayName("updateProgress - 参数缺失")
    void testUpdateProgressParamInvalid() {
        ResponseResult r = apCourseService.updateProgress(null, 1L, 1L, true);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("updateProgress - 未拥有课程")
    void testUpdateProgressNotOwned() {
        when(userCourseMapper.selectOne(any())).thenReturn(null);
        ResponseResult r = apCourseService.updateProgress(userId, 1L, 1L, true);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), r.getCode());
    }

    @Test
    @DisplayName("updateProgress - 新建进度记录并按完成比例重算总进度")
    void testUpdateProgressInsertNew() {
        ApUserCourse userCourse = new ApUserCourse();
        userCourse.setProgress(BigDecimal.ZERO);
        when(userCourseMapper.selectOne(any())).thenReturn(userCourse);

        when(readingProgressMapper.selectOne(any())).thenReturn(null); // 无既有进度
        when(baseMapper.selectById(1L)).thenReturn(course(1L, 10, ApCourse.Status.PUBLISHED.getCode()));
        ApCourseChapter ch = new ApCourseChapter();
        ch.setId(11L);
        when(chapterMapper.selectList(any())).thenReturn(Collections.singletonList(ch));
        when(readingProgressMapper.selectCount(any(Wrapper.class))).thenReturn(2L); // 2个已完成章节

        ResponseResult r = apCourseService.updateProgress(userId, 1L, 11L, true);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(readingProgressMapper).insert((ApCourseReadingProgress) any());
        assertEquals(0, BigDecimal.valueOf(100.00).compareTo(userCourse.getProgress())); // 2/2*100=100.00
        verify(userCourseMapper).updateById(userCourse);
    }

    @Test
    @DisplayName("updateProgress - 更新既有进度记录")
    void testUpdateProgressUpdateExisting() {
        ApUserCourse userCourse = new ApUserCourse();
        userCourse.setProgress(BigDecimal.ZERO);
        when(userCourseMapper.selectOne(any())).thenReturn(userCourse);

        ApCourseReadingProgress progress = new ApCourseReadingProgress();
        when(readingProgressMapper.selectOne(any())).thenReturn(progress); // 已有进度
        when(baseMapper.selectById(1L)).thenReturn(course(1L, 10, ApCourse.Status.PUBLISHED.getCode()));
        when(chapterMapper.selectList(any())).thenReturn(Collections.emptyList());

        ResponseResult r = apCourseService.updateProgress(userId, 1L, 11L, false);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(readingProgressMapper, never()).insert((ApCourseReadingProgress) any());
        verify(readingProgressMapper).updateById(progress);
    }

    // ==================== 公开详情 ====================

    @Test
    @DisplayName("getPublicDetail - 参数缺失")
    void testPublicDetailParamInvalid() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), apCourseService.getPublicDetail(null).getCode());
    }

    @Test
    @DisplayName("getPublicDetail - 不存在返回课程不存在")
    void testPublicDetailNotFound() {
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), apCourseService.getPublicDetail(1L).getCode());
    }

    @Test
    @DisplayName("getPublicDetail - 未上架不泄露")
    void testPublicDetailUnpublished() {
        when(baseMapper.selectById(1L)).thenReturn(course(1L, 10, ApCourse.Status.WRITING.getCode()));
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), apCourseService.getPublicDetail(1L).getCode());
    }

    @Test
    @DisplayName("getPublicDetail - 已上架返回课程+章节，空头像走用户真实头像回退")
    void testPublicDetailSuccess() {
        ApCourse c = course(1L, 10, ApCourse.Status.PUBLISHED.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        when(chapterMapper.selectList(any())).thenReturn(Collections.emptyList());
        Map<String, Object> userData = new HashMap<>();
        userData.put("avatar", "http://avatar");
        when(userClient.getPublicInfo(10L))
                .thenReturn(ResponseResult.okResult(userData));

        ResponseResult r = apCourseService.getPublicDetail(1L);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals("http://avatar", c.getAuthorAvatar());
        assertEquals(1L, dataOf(r).get("course") == null ? null : ((ApCourse) dataOf(r).get("course")).getId());
    }

    // ==================== 创作权限 / 创建 ====================

    @Test
    @DisplayName("checkAuthorPermission - 未登录")
    void testCheckAuthorPermissionNeedLogin() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), apCourseService.checkAuthorPermission(null).getCode());
    }

    @Test
    @DisplayName("checkAuthorPermission - Lv7 有权限")
    void testCheckAuthorPermissionGranted() {
        when(levelService.getUserLevel(userId)).thenReturn(level(7));
        Map<String, Object> data = dataOf(apCourseService.checkAuthorPermission(userId));
        assertEquals(Boolean.TRUE, data.get("hasPermission"));
        assertEquals(7, data.get("powerLevel"));
    }

    @Test
    @DisplayName("createCourse - 权限不足拒绝")
    void testCreateCourseDenied() {
        when(levelService.getUserLevel(userId)).thenReturn(level(5));
        CourseDto dto = new CourseDto();
        dto.setTitle("标题");
        ResponseResult r = apCourseService.createCourse(dto, userId);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), r.getCode());
    }

    @Test
    @DisplayName("createCourse - 成功后返回雪花ID字符串(防前端精度丢失)")
    void testCreateCourseSuccess() {
        when(levelService.getUserLevel(userId)).thenReturn(level(8));
        CourseDto dto = new CourseDto();
        dto.setTitle("新课程");
        dto.setPrice(BigDecimal.valueOf(99));
        when(baseMapper.insert((ApCourse) any())).thenReturn(1);

        ResponseResult r = apCourseService.createCourse(dto, userId);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(baseMapper).insert((ApCourse) any());
    }

    // ==================== 更新 / 删除 ====================

    @Test
    @DisplayName("updateCourse - 缺id")
    void testUpdateCourseNoId() {
        CourseDto dto = new CourseDto();
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), apCourseService.updateCourse(dto, userId).getCode());
    }

    @Test
    @DisplayName("updateCourse - 非作者拒绝")
    void testUpdateCourseNotOwner() {
        CourseDto dto = new CourseDto();
        dto.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(course(1L, 999, ApCourse.Status.WRITING.getCode()));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), apCourseService.updateCourse(dto, userId).getCode());
    }

    @Test
    @DisplayName("updateCourse - 已上架不可编辑")
    void testUpdateCoursePublishedDenied() {
        CourseDto dto = new CourseDto();
        dto.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(course(1L, 100, ApCourse.Status.PUBLISHED.getCode()));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), apCourseService.updateCourse(dto, userId).getCode());
    }

    @Test
    @DisplayName("updateCourse - 作者编辑成功")
    void testUpdateCourseSuccess() {
        CourseDto dto = new CourseDto();
        dto.setId(1L);
        dto.setTitle("新标题");
        when(baseMapper.selectById(1L)).thenReturn(course(1L, 100, ApCourse.Status.WRITING.getCode()));
        ResponseResult r = apCourseService.updateCourse(dto, userId);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(baseMapper).updateById((ApCourse) any());
    }

    @Test
    @DisplayName("softDelete - 非作者拒绝")
    void testSoftDeleteNotOwner() {
        when(baseMapper.selectById(1L)).thenReturn(course(1L, 999, ApCourse.Status.WRITING.getCode()));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), apCourseService.softDelete(1L, userId).getCode());
    }

    @Test
    @DisplayName("softDelete - 作者软删除成功")
    void testSoftDeleteSuccess() {
        ApCourse c = course(1L, 100, ApCourse.Status.WRITING.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), apCourseService.softDelete(1L, userId).getCode());
        assertEquals(1, c.getIsDeleted());
    }

    // ==================== 申报校验 ====================

    @Test
    @DisplayName("submitApply - 主题为空")
    void testApplyEmptyTitle() {
        ResponseResult r = apCourseService.submitApply(null, "{\"title\":\"\",\"channel\":\"其他\"}", null, userId);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        assertEquals("小册主题不能为空", r.getMessage());
    }

    @Test
    @DisplayName("submitApply - 主题超长")
    void testApplyTitleTooLong() {
        String title = "一二三四五六七八九十一二三四五六七八九十一二三四五六七"; // 27字
        ResponseResult r = apCourseService.submitApply(null, "{\"title\":\"" + title + "\",\"channel\":\"其他\"}", null, userId);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        assertTrue(r.getMessage().contains("不能超过20字"));
    }

    @Test
    @DisplayName("submitApply - 渠道不合法")
    void testApplyInvalidChannel() {
        ResponseResult r = apCourseService.submitApply(null, "{\"title\":\"主题\",\"channel\":\"QQ群\"}", null, userId);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        assertEquals("申请渠道不合法", r.getMessage());
    }

    @Test
    @DisplayName("submitApply - 非法JSON")
    void testApplyInvalidJson() {
        ResponseResult r = apCourseService.submitApply(null, "not-json", null, userId);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        assertEquals("申请单格式错误", r.getMessage());
    }

    @Test
    @DisplayName("submitApply - 新申请建草稿并置为申报待审")
    void testApplyCreateDraft() {
        when(baseMapper.insert((ApCourse) any())).thenReturn(1);
        ResponseResult r = apCourseService.submitApply(
                null, "{\"title\":\"精选主题\",\"channel\":\"其他\"}", null, userId);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(baseMapper).insert((ApCourse) any());
    }

    @Test
    @DisplayName("submitApply - 已有草稿重提申报")
    void testApplyResubmit() {
        ApCourse c = course(1L, 100, ApCourse.Status.NORMAL.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        ResponseResult r = apCourseService.submitApply(
                1L, "{\"title\":\"精选主题\",\"channel\":\"其他\"}", null, userId);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals(ApCourse.Status.SUBMIT.getCode(), c.getStatus());
        verify(baseMapper).updateById((ApCourse) any());
    }

    // ==================== 状态机：提交/下架/编辑流转 ====================

    @Test
    @DisplayName("submitForReview - 写作中(4)可提交上架审核")
    void testSubmitForReviewSuccess() {
        ApCourse c = course(1L, 100, ApCourse.Status.WRITING.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), apCourseService.submitForReview(1L, userId).getCode());
        assertEquals(ApCourse.Status.REVIEW.getCode(), c.getStatus());
    }

    @Test
    @DisplayName("submitForReview - 非法状态(草稿0)不可直接上架审核")
    void testSubmitForReviewIllegal() {
        ApCourse c = course(1L, 100, ApCourse.Status.NORMAL.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), apCourseService.submitForReview(1L, userId).getCode());
    }

    @Test
    @DisplayName("authorUnpublish - 作者可下架已上架课程")
    void testAuthorUnpublishSuccess() {
        ApCourse c = course(1L, 100, ApCourse.Status.PUBLISHED.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), apCourseService.authorUnpublish(1L, userId).getCode());
        assertEquals(ApCourse.Status.OFFLINE.getCode(), c.getStatus());
    }

    @Test
    @DisplayName("authorUnpublish - 非法迁移(写作中直接下架)拒绝")
    void testAuthorUnpublishIllegal() {
        ApCourse c = course(1L, 100, ApCourse.Status.WRITING.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), apCourseService.authorUnpublish(1L, userId).getCode());
    }

    @Test
    @DisplayName("editorTransition - 通过申报(1->4)并记录审核时间")
    void testEditorTransitionApprove() {
        ApCourse c = course(1L, 100, ApCourse.Status.SUBMIT.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(),
                apCourseService.editorTransition(1L, ApCourse.Status.WRITING.getCode(), null).getCode());
        assertEquals(ApCourse.Status.WRITING.getCode(), c.getStatus());
        assertNotNull(c.getReviewTime());
    }

    @Test
    @DisplayName("editorTransition - 拒绝申报(1->2)写拒绝原因")
    void testEditorTransitionReject() {
        ApCourse c = course(1L, 100, ApCourse.Status.SUBMIT.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        apCourseService.editorTransition(1L, ApCourse.Status.FAIL.getCode(), "选题不符");
        assertEquals("选题不符", c.getApplyReason());
    }

    @Test
    @DisplayName("editorTransition - 上架(5->9)写入发布时间")
    void testEditorTransitionPublish() {
        ApCourse c = course(1L, 100, ApCourse.Status.REVIEW.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        apCourseService.editorTransition(1L, ApCourse.Status.PUBLISHED.getCode(), null);
        assertEquals(ApCourse.Status.PUBLISHED.getCode(), c.getStatus());
        assertNotNull(c.getPublishedAt());
    }

    @Test
    @DisplayName("editorTransition - 非法跳转(草稿0->已上架9)拒绝")
    void testEditorTransitionIllegal() {
        ApCourse c = course(1L, 100, ApCourse.Status.NORMAL.getCode());
        when(baseMapper.selectById(1L)).thenReturn(c);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                apCourseService.editorTransition(1L, ApCourse.Status.PUBLISHED.getCode(), null).getCode());
    }

    // ==================== 编辑发布小节 ====================

    @Test
    @DisplayName("publishSections - 无发布小节返回错误")
    void testPublishSectionsEmpty() {
        when(baseMapper.selectById(1L)).thenReturn(course(1L, 100, ApCourse.Status.PUBLISHED.getCode()));
        when(chapterMapper.selectList(any())).thenReturn(Collections.emptyList());
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                apCourseService.publishSections(1L, Collections.singletonList(11L)).getCode());
    }

    @Test
    @DisplayName("publishSections - 批量置为已发布")
    void testPublishSectionsSuccess() {
        when(baseMapper.selectById(1L)).thenReturn(course(1L, 100, ApCourse.Status.PUBLISHED.getCode()));
        ApCourseChapter c1 = new ApCourseChapter();
        ApCourseChapter c2 = new ApCourseChapter();
        List<ApCourseChapter> chapters = new ArrayList<>();
        chapters.add(c1);
        chapters.add(c2);
        when(chapterMapper.selectList(any())).thenReturn(chapters);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), apCourseService.publishSections(1L, new ArrayList<>()).getCode());
        verify(chapterMapper, times(2)).updateById((ApCourseChapter) any());
    }
}