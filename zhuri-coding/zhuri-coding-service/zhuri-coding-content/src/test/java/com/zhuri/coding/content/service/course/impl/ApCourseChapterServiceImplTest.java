package com.heima.content.service.course.impl;

import com.heima.content.mapper.course.ApCourseChapterMapper;
import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.mapper.course.ApUserCourseMapper;
import com.heima.content.service.aigc.AigcDetectService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.course.dtos.ChapterDto;
import com.heima.model.course.dtos.ChapterSortDto;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.course.pojos.ApCourseChapter;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApCourseChapterServiceImpl 单元测试（小册章节：建章/改章/删章/排序/详情/提交审核）
 *
 * 普通 @Service，两个 Mapper 字段由 Mockito @InjectMocks 注入。
 * 覆盖：
 * - createChapter：课程ID为空/课程不存在/非作者/已上架禁编/默认排序与显式排序/默认值填充/章节数重算；
 * - updateChapter：章节ID为空/章节不存在/课程不存在/非作者/已上架禁编/部分字段更新；
 * - deleteChapter：参数缺失/不存在/非作者/已上架禁编/成功并重算章节数；
 * - updateSort：参数缺失(空items也不同)/课程不存在/非作者/仅更新归属一致的项；
 * - getChapterDetail：参数缺失/不存在/正常；
 * - submitForReview：参数缺失/不存在/非作者/已发布(1)与审核中(2)拦截/草稿(0)->审核中(2)成功。
 */
class ApCourseChapterServiceImplTest {

    @Mock
    private ApCourseChapterMapper chapterMapper;
    @Mock
    private ApCourseMapper courseMapper;
    @Mock
    private ApUserCourseMapper userCourseMapper;
    // AIGC 水文检测（建章/改章后 L1 快检打标，测试 no-op）
    @Mock
    private AigcDetectService aigcDetectService;

    @InjectMocks
    private ApCourseChapterServiceImpl chapterService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    // ---------- 辅助 ----------

    private ApCourse authorCourse(Integer authorId, byte status) {
        ApCourse c = new ApCourse();
        c.setId(10L);
        c.setAuthorId(authorId);
        c.setStatus(status);
        return c;
    }

    private ApCourseChapter chapter(Long id, Long courseId, Integer status) {
        ApCourseChapter ch = new ApCourseChapter();
        ch.setId(id);
        ch.setCourseId(courseId);
        ch.setStatus(status);
        ch.setSortOrder(1);
        return ch;
    }

    // ==================== createChapter ====================

    @Test
    @DisplayName("createChapter - 课程ID为空")
    void testCreateParamInvalid() {
        ChapterDto dto = new ChapterDto();
        ResponseResult r = chapterService.createChapter(dto, userId);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        verify(chapterMapper, never()).insert((ApCourseChapter) any());
    }

    @Test
    @DisplayName("createChapter - 课程不存在")
    void testCreateCourseNotExist() {
        ChapterDto dto = new ChapterDto();
        dto.setCourseId(10L);
        when(courseMapper.selectById(10L)).thenReturn(null);
        ResponseResult r = chapterService.createChapter(dto, userId);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), r.getCode());
    }

    @Test
    @DisplayName("createChapter - 非作者被拒")
    void testCreateNotAuthor() {
        ChapterDto dto = new ChapterDto();
        dto.setCourseId(10L);
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(999, (byte) 0));
        ResponseResult r = chapterService.createChapter(dto, userId);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), r.getCode());
    }

    @Test
    @DisplayName("createChapter - 已上架课程禁编")
    void testCreatePublishedDenied() {
        ChapterDto dto = new ChapterDto();
        dto.setCourseId(10L);
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, ApCourse.Status.PUBLISHED.getCode()));
        ResponseResult r = chapterService.createChapter(dto, userId);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), r.getCode());
    }

    @Test
    @DisplayName("createChapter - 默认排序1与默认值填充，并重算章节数")
    void testCreateSuccessDefaults() {
        ChapterDto dto = new ChapterDto();
        dto.setCourseId(10L);
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, (byte) 0));
        // getNextSortOrder：无上一条 -> 1
        when(chapterMapper.selectOne(any())).thenReturn(null);
        when(chapterMapper.selectCount(any())).thenReturn(2L);

        ResponseResult r = chapterService.createChapter(dto, userId);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        ApCourseChapter created = (ApCourseChapter) r.getData();
        assertEquals(1, created.getSortOrder());
        assertEquals("未命名章节", created.getTitle());
        assertEquals(0, created.getWordCount());
        assertEquals(5, created.getEstimatedMinutes());
        verify(chapterMapper).insert(created);
        // 重算章节数写回课程
        verify(courseMapper).updateById((ApCourse) any());
    }

    @Test
    @DisplayName("createChapter - 显式排序并继承上一条排序")
    void testCreateSuccessExplicitSort() {
        ChapterDto dto = new ChapterDto();
        dto.setCourseId(10L);
        dto.setSortOrder(5);
        dto.setTitle("第一章");
        dto.setContent("hello");
        dto.setIsFree((byte) 1);
        dto.setEstimatedMinutes(8);
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, (byte) 0));
        when(chapterMapper.selectCount(any())).thenReturn(3L);

        ResponseResult r = chapterService.createChapter(dto, userId);

        ApCourseChapter created = (ApCourseChapter) r.getData();
        assertEquals(5, created.getSortOrder());
        assertEquals("第一章", created.getTitle());
        assertEquals(5, created.getWordCount());
        assertEquals(8, created.getEstimatedMinutes());
        verify(chapterMapper).insert(created);
    }

    // ==================== updateChapter ====================

    @Test
    @DisplayName("updateChapter - 章节ID为空")
    void testUpdateParamInvalid() {
        ChapterDto dto = new ChapterDto();
        ResponseResult r = chapterService.updateChapter(dto, userId);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("updateChapter - 章节不存在")
    void testUpdateChapterNotExist() {
        ChapterDto dto = new ChapterDto();
        dto.setId(1L);
        when(chapterMapper.selectById(1L)).thenReturn(null);
        ResponseResult r = chapterService.updateChapter(dto, userId);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), r.getCode());
    }

    @Test
    @DisplayName("updateChapter - 课程不存在")
    void testUpdateCourseNotExist() {
        ChapterDto dto = new ChapterDto();
        dto.setId(1L);
        when(chapterMapper.selectById(1L)).thenReturn(chapter(1L, 10L, 0));
        when(courseMapper.selectById(10L)).thenReturn(null);
        ResponseResult r = chapterService.updateChapter(dto, userId);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), r.getCode());
    }

    @Test
    @DisplayName("updateChapter - 非作者被拒")
    void testUpdateNotAuthor() {
        ChapterDto dto = new ChapterDto();
        dto.setId(1L);
        when(chapterMapper.selectById(1L)).thenReturn(chapter(1L, 10L, 0));
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(999, (byte) 0));
        ResponseResult r = chapterService.updateChapter(dto, userId);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), r.getCode());
    }

    @Test
    @DisplayName("updateChapter - 已上架禁编")
    void testUpdatePublishedDenied() {
        ChapterDto dto = new ChapterDto();
        dto.setId(1L);
        when(chapterMapper.selectById(1L)).thenReturn(chapter(1L, 10L, 0));
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, ApCourse.Status.PUBLISHED.getCode()));
        ResponseResult r = chapterService.updateChapter(dto, userId);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), r.getCode());
    }

    @Test
    @DisplayName("updateChapter - 部分字段更新")
    void testUpdateSuccess() {
        ChapterDto dto = new ChapterDto();
        dto.setId(1L);
        dto.setTitle("新标题");
        dto.setContent("新内容");
        dto.setSortOrder(9);
        ApCourseChapter existing = chapter(1L, 10L, 0);
        when(chapterMapper.selectById(1L)).thenReturn(existing);
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, (byte) 0));

        ResponseResult r = chapterService.updateChapter(dto, userId);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals("新标题", existing.getTitle());
        assertEquals(3, existing.getWordCount());
        assertEquals(9, existing.getSortOrder());
        verify(chapterMapper).updateById(existing);
    }

    // ==================== deleteChapter ====================

    @Test
    @DisplayName("deleteChapter - 参数缺失/章节不存在/课程不存在")
    void testDeleteGuardPaths() {
        ResponseResult param = chapterService.deleteChapter(null, userId);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), param.getCode());

        when(chapterMapper.selectById(1L)).thenReturn(null);
        ResponseResult noChapter = chapterService.deleteChapter(1L, userId);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), noChapter.getCode());

        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 10L, 0));
        when(courseMapper.selectById(10L)).thenReturn(null);
        ResponseResult noCourse = chapterService.deleteChapter(2L, userId);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), noCourse.getCode());
    }

    @Test
    @DisplayName("deleteChapter - 非作者/已上架被拒")
    void testDeleteAuthDenied() {
        when(chapterMapper.selectById(1L)).thenReturn(chapter(1L, 10L, 0));
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(999, (byte) 0));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                chapterService.deleteChapter(1L, userId).getCode());

        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 10L, 0));
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, ApCourse.Status.PUBLISHED.getCode()));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                chapterService.deleteChapter(2L, userId).getCode());
    }

    @Test
    @DisplayName("deleteChapter - 成功并重算章节数")
    void testDeleteSuccess() {
        when(chapterMapper.selectById(1L)).thenReturn(chapter(1L, 10L, 0));
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, (byte) 0));
        when(chapterMapper.selectCount(any())).thenReturn(1L);

        ResponseResult r = chapterService.deleteChapter(1L, userId);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(chapterMapper).deleteById(1L);
        verify(courseMapper).updateById((ApCourse) any());
    }

    // ==================== updateSort ====================

    @Test
    @DisplayName("updateSort - 参数缺失")
    void testSortParamInvalid() {
        ChapterSortDto none = new ChapterSortDto();
        ChapterSortDto empty = new ChapterSortDto();
        empty.setCourseId(10L);
        empty.setItems(Collections.emptyList());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), chapterService.updateSort(none, userId).getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), chapterService.updateSort(empty, userId).getCode());
    }

    @Test
    @DisplayName("updateSort - 课程不存在/非作者")
    void testSortGuardPaths() {
        ChapterSortDto dto = new ChapterSortDto();
        dto.setCourseId(10L);
        ChapterSortDto.SortItem item = new ChapterSortDto.SortItem();
        item.setId(1L);
        item.setSortOrder(2);
        dto.setItems(Collections.singletonList(item));

        when(courseMapper.selectById(10L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), chapterService.updateSort(dto, userId).getCode());

        when(courseMapper.selectById(10L)).thenReturn(authorCourse(999, ApCourse.Status.PUBLISHED.getCode()));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), chapterService.updateSort(dto, userId).getCode());
    }

    @Test
    @DisplayName("updateSort - 仅更新归属一致的小节")
    void testSortSuccess() {
        ChapterSortDto dto = new ChapterSortDto();
        dto.setCourseId(10L);
        ChapterSortDto.SortItem item1 = new ChapterSortDto.SortItem();
        item1.setId(1L);
        item1.setSortOrder(3);
        ChapterSortDto.SortItem item2 = new ChapterSortDto.SortItem();
        item2.setId(2L);
        item2.setSortOrder(4);
        dto.setItems(java.util.Arrays.asList(item1, item2));

        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, (byte) 0));
        // item1 归属一致 -> 更新；item2 归属他课(20) -> 跳过
        when(chapterMapper.selectById(1L)).thenReturn(chapter(1L, 10L, 0));
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 20L, 0));

        ResponseResult r = chapterService.updateSort(dto, userId);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(chapterMapper, times(1)).updateById((ApCourseChapter) any());
    }

    // ==================== getChapterDetail ====================

    @Test
    @DisplayName("getChapterDetail - 参数缺失/不存在/正常")
    void testChapterDetail() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                chapterService.getChapterDetail(null).getCode());

        when(chapterMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                chapterService.getChapterDetail(1L).getCode());

        // 课程不存在视为免费兜底 → 匿名可读
        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 10L, 1));
        ApCourseChapter vo = (ApCourseChapter) chapterService.getChapterDetail(2L).getData();
        assertEquals(2L, vo.getId());
    }

    // ==================== getChapterDetail 阅读权限 ====================

    /** 免费整本（价格为0）→ 匿名放行 */
    @Test
    @DisplayName("getChapterDetail - 免费整本匿名可读")
    void testAnonymousCanReadFreeCourse() {
        ApCourseChapter ch = chapter(3L, 10L, 1);
        when(chapterMapper.selectById(3L)).thenReturn(ch);
        ApCourse free = authorCourse(99, (byte) 9);
        free.setPrice(BigDecimal.ZERO);
        when(courseMapper.selectById(10L)).thenReturn(free);

        ResponseResult r = chapterService.getChapterDetail(3L);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
    }

    /** 付费课程的免费/试读节（is_free=1）→ 匿名放行 */
    @Test
    @DisplayName("getChapterDetail - 付费课程试读节匿名可读")
    void testAnonymousCanReadTrialSection() {
        ApCourseChapter ch = chapter(4L, 10L, 1);
        ch.setIsFree((byte) 1);
        when(chapterMapper.selectById(4L)).thenReturn(ch);
        ApCourse paid = authorCourse(99, (byte) 9);
        paid.setPrice(new BigDecimal("9.9"));
        when(courseMapper.selectById(10L)).thenReturn(paid);

        ResponseResult r = chapterService.getChapterDetail(4L);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
    }

    /** 付费非试读节：匿名 → 需登录 */
    @Test
    @DisplayName("getChapterDetail - 付费非试读节匿名需登录")
    void testAnonymousCannotReadPaidSection() {
        ApCourseChapter ch = chapter(5L, 10L, 1);  // is_free=0
        when(chapterMapper.selectById(5L)).thenReturn(ch);
        ApCourse paid = authorCourse(99, (byte) 9);
        paid.setPrice(new BigDecimal("9.9"));
        when(courseMapper.selectById(10L)).thenReturn(paid);

        ResponseResult r = chapterService.getChapterDetail(5L);
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), r.getCode());
    }

    /** 付费非试读节：已登录未购 → 需购买 */
    @Test
    @DisplayName("getChapterDetail - 付费非试读节已登录未购需购买")
    void testLoggedInNotPurchasedCannotReadPaidSection() {
        ApCourseChapter ch = chapter(6L, 10L, 1);
        when(chapterMapper.selectById(6L)).thenReturn(ch);
        ApCourse paid = authorCourse(99, (byte) 9);
        paid.setPrice(new BigDecimal("9.9"));
        when(courseMapper.selectById(10L)).thenReturn(paid);

        ApUser user = new ApUser();
        user.setId(100);
        AppThreadLocalUtil.setUser(user);
        when(userCourseMapper.selectCount(any())).thenReturn(0L);

        ResponseResult r = chapterService.getChapterDetail(6L);
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), r.getCode());
        AppThreadLocalUtil.clear();
    }

    /** 付费非试读节：已登录且已购 → 放行 */
    @Test
    @DisplayName("getChapterDetail - 付费非试读节已购可读")
    void testLoggedInPurchasedCanReadPaidSection() {
        ApCourseChapter ch = chapter(7L, 10L, 1);
        when(chapterMapper.selectById(7L)).thenReturn(ch);
        ApCourse paid = authorCourse(99, (byte) 9);
        paid.setPrice(new BigDecimal("9.9"));
        when(courseMapper.selectById(10L)).thenReturn(paid);

        ApUser user = new ApUser();
        user.setId(100);
        AppThreadLocalUtil.setUser(user);
        when(userCourseMapper.selectCount(any())).thenReturn(1L);

        ResponseResult r = chapterService.getChapterDetail(7L);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        AppThreadLocalUtil.clear();
    }

    // ==================== submitForReview ====================

    @Test
    @DisplayName("submitForReview - 参数缺失/章节不存在/课程不存在/非作者")
    void testReviewGuardPaths() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                chapterService.submitForReview(null, "n", userId).getCode());

        when(chapterMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                chapterService.submitForReview(1L, "n", userId).getCode());

        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 10L, 0));
        when(courseMapper.selectById(10L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                chapterService.submitForReview(2L, "n", userId).getCode());

        when(chapterMapper.selectById(3L)).thenReturn(chapter(3L, 10L, 0));
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(999, (byte) 0));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                chapterService.submitForReview(3L, "n", userId).getCode());
    }

    @Test
    @DisplayName("submitForReview - 已发布(1)与审核中(2)拦截")
    void testReviewStatusDenied() {
        when(chapterMapper.selectById(1L)).thenReturn(chapter(1L, 10L, 1));
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, (byte) 0));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                chapterService.submitForReview(1L, "n", userId).getCode());

        when(chapterMapper.selectById(2L)).thenReturn(chapter(2L, 10L, 2));
        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(),
                chapterService.submitForReview(2L, "n", userId).getCode());
    }

    @Test
    @DisplayName("submitForReview - 草稿(0)提交为审核中(2)并写审核备注")
    void testReviewSubmitSuccess() {
        ApCourseChapter ch = chapter(1L, 10L, 0);
        when(chapterMapper.selectById(1L)).thenReturn(ch);
        when(courseMapper.selectById(10L)).thenReturn(authorCourse(100, (byte) 0));

        ResponseResult r = chapterService.submitForReview(1L, "请审核", userId);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals(2, ch.getStatus());
        assertEquals("请审核", ch.getReviewNote());
        verify(chapterMapper).updateById(ch);
    }
}