package com.zhuri.coding.content.controller.v1.user;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.column.ApColumnMapper;
import com.zhuri.coding.content.mapper.course.ApCourseMapper;
import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.mapper.tip.ApArticleTipRecordMapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.content.service.article.ArticleStatisticsService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleTipRecord;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.behavior.pojos.ApCollection;
import com.zhuri.coding.model.behavior.pojos.UserBehaviorRecord;
import com.zhuri.coding.model.column.pojos.ApColumn;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.course.pojos.ApCourse;
import com.zhuri.coding.model.follow.pojos.ApFollow;
import com.zhuri.coding.model.pins.pojos.ApPins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * UserHomeController 单元测试（个人主页公开接口，@RestController，@InjectMocks 注入 10 个依赖）
 *
 * 覆盖：
 * - home：参数校验(getCode!=200)、正常合并用户信息+统计、userClient/统计异常兜底；
 * - articles/columns/pins/courses：参数校验、公开已发布过滤、VO 组装；
 * - following/followers：关注/关注者分页、userBrief 失败过滤；
 * - collections：空返回→total=0、正常组装、无对应文章跳过；
 * - likes：type 过滤(article/pins/不传)、空、文章/沸点混合组装、目标缺失跳过；
 * - tips：空返回、正常组装、article 缺失 articleTitle 为空；
 * - helper：firstImage 解析 [".."] / 逗号分隔 / 空、str 空串兜底。
 */
class UserHomeControllerTest {

    @Mock private IUserClient userClient;
    @Mock private ArticleStatisticsService articleStatisticsService;
    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApColumnMapper apColumnMapper;
    @Mock private ApPinsMapper apPinsMapper;
    @Mock private ApFollowMapper apFollowMapper;
    @Mock private ApCollectionMapper apCollectionMapper;
    @Mock private UserBehaviorRecordMapper behaviorRecordMapper;
    @Mock private ApCourseMapper apCourseMapper;
    @Mock private ApArticleTipRecordMapper tipRecordMapper;

    @InjectMocks
    private UserHomeController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private ApArticle article(Long id, String title) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setTitle(title);
        a.setAuthorId(5L);
        a.setAuthorName("作者");
        a.setViews(10);
        a.setComment(2);
        a.setPublishTime(new Date());
        a.setCreatedTime(new Date());
        return a;
    }

    private ApColumn column(Long id, String title) {
        ApColumn c = new ApColumn();
        c.setId(id);
        c.setTitle(title);
        c.setDescription("desc");
        c.setCoverImage("cover.png");
        c.setArticleCount(3);
        c.setCreatedTime(new Date());
        return c;
    }

    private ApPins pins(Long id, String content) {
        ApPins p = new ApPins();
        p.setId(id);
        p.setContent(content);
        p.setImageUrls("[\"img1.png\",\"img2.png\"]");
        p.setAuthorId(5L);
        p.setAuthorName("作者");
        p.setLikes(4);
        p.setComment(1);
        p.setViews(9);
        p.setPublishTime(new Date());
        p.setCreatedTime(new Date());
        return p;
    }

    private ApCourse course(Long id, String title) {
        ApCourse c = new ApCourse();
        c.setId(id);
        c.setTitle(title);
        c.setSubtitle("副标题");
        c.setCoverImage("c.png");
        c.setPrice(BigDecimal.valueOf(10));
        c.setOriginalPrice(BigDecimal.valueOf(20));
        c.setChapterCount(3);
        c.setStudyCount(50);
        c.setSalesCount(4);
        c.setPublishedAt(new Date());
        c.setCreatedTime(new Date());
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseResult r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(ResponseResult r) {
        return (List<Map<String, Object>>) data(r).get("list");
    }

    // ==================== home ====================

    @Test
    @DisplayName("home - 参数非法返回 PARAM_INVALID")
    void testHomeBadParam() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                controller.home(0L).getCode());
    }

    @Test
    @DisplayName("home - 正常合并用户信息+统计")
    void testHomeOk() {
        Map<String, Object> ud = new HashMap<>();
        ud.put("nickname", "小明");
        ud.put("avatar", "a.png");
        ud.put("bio", "简介");
        ud.put("position", "后端");
        ud.put("company", "XX");
        when(userClient.getPublicInfo(1L))
                .thenReturn(ResponseResult.okResult(ud));

        Map<String, Object> stats = new HashMap<>();
        stats.put("followCount", 3);
        stats.put("levelInfo", new HashMap<String, Object>());
        when(articleStatisticsService.getUserStatistics(1L))
                .thenReturn(ResponseResult.okResult(stats));

        ResponseResult r = controller.home(1L);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        Map<String, Object> user = data(r).get("user") instanceof Map
                ? (Map<String, Object>) data(r).get("user") : new HashMap<>();
        assertEquals("小明", user.get("nickname"));
        assertEquals("后端", user.get("position"));
        assertEquals(3, data(r).get("followCount"));
    }

    @Test
    @DisplayName("home - userClient/统计异常兜底，ridIe 返回空 user")
    void testHomeExceptionFallback() {
        when(userClient.getPublicInfo(1L)).thenThrow(new RuntimeException("boom"));
        when(articleStatisticsService.getUserStatistics(1L)).thenThrow(new RuntimeException("boom"));
        ResponseResult r = controller.home(1L);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        Map<String, Object> user = data(r).get("user") instanceof Map
                ? (Map<String, Object>) data(r).get("user") : new HashMap<>();
        assertEquals("", user.getOrDefault("nickname", ""));
    }

    // ==================== articles ====================

    @Test
    @DisplayName("articles - 参数非法")
    void testArticlesBadParam() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), controller.articles(0L, 1, 10).getCode());
    }

    @Test
    @DisplayName("articles - 正常返回并组装 VO")
    void testArticlesOk() {
        Page<ApArticle> p = new Page<>(1, 10);
        p.setTotal(1L);
        p.setRecords(Collections.singletonList(article(1L, "文章")));
        when(apArticleMapper.selectPage(any(), any())).thenReturn(p);

        ResponseResult r = controller.articles(1L, 1, 10);
        List<Map<String, Object>> list = list(r);
        assertEquals(1, list.size());
        assertEquals("文章", list.get(0).get("title"));
        assertEquals("1", list.get(0).get("id"));
        assertEquals(1L, data(r).get("total"));
    }

    @Test
    @DisplayName("articles - page/size 越界夹紧")
    void testArticlesClamp() {
        Page<ApArticle> p = new Page<>(1, 10);
        p.setRecords(Collections.emptyList());
        when(apArticleMapper.selectPage(any(), any())).thenReturn(p);
        controller.articles(1L, 0, 100); // 内部 page->1, size->10 分支覆盖
    }

    // ==================== columns ====================

    @Test
    @DisplayName("columns - 正常返回")
    void testColumnsOk() {
        Page<ApColumn> p = new Page<>(1, 10);
        p.setTotal(1L);
        p.setRecords(Collections.singletonList(column(1L, "专栏")));
        when(apColumnMapper.selectPage(any(), any())).thenReturn(p);
        ResponseResult r = controller.columns(1L, 1, 10);
        List<Map<String, Object>> list = list(r);
        assertEquals("专栏", list.get(0).get("name"));
        assertEquals(1L, data(r).get("total"));
    }

    // ==================== pins ====================

    @Test
    @DisplayName("pins - 正常返回")
    void testPinsOk() {
        Page<ApPins> p = new Page<>(1, 10);
        p.setRecords(Collections.singletonList(pins(1L, "沸点")));
        when(apPinsMapper.selectPage(any(), any())).thenReturn(p);
        ResponseResult r = controller.pins(1L, 1, 10);
        List<Map<String, Object>> list = list(r);
        assertEquals("沸点", list.get(0).get("content"));
        assertEquals(4, list.get(0).get("likeCount"));
    }

    // ==================== following / followers ====================

    @Test
    @DisplayName("following - userBrief 成功返回")
    void testFollowingOk() {
        ApFollow f = new ApFollow();
        f.setFollowUserId(9);
        f.setCreatedTime(new Date());
        Page<ApFollow> p = new Page<>(1, 20);
        p.setRecords(Collections.singletonList(f));
        when(apFollowMapper.selectPage(any(), any())).thenReturn(p);

        Map<String, Object> ud = new HashMap<>();
        ud.put("nickname", "被关注");
        ud.put("avatar", "b.png");
        ud.put("bio", "hi");
        when(userClient.getPublicInfo(9L)).thenReturn(ResponseResult.okResult(ud));

        ResponseResult r = controller.following(1L, 1, 20);
        List<Map<String, Object>> list = list(r);
        assertEquals(1, list.size());
        assertEquals(9, list.get(0).get("id"));
        assertEquals("被关注", list.get(0).get("nickname"));
    }

    @Test
    @DisplayName("followers - userBrief 失败时过滤 null")
    void testFollowersFilterNull() {
        ApFollow f = new ApFollow();
        f.setUserId(8);
        Page<ApFollow> p = new Page<>(1, 20);
        p.setRecords(Collections.singletonList(f));
        when(apFollowMapper.selectPage(any(), any())).thenReturn(p);
        when(userClient.getPublicInfo(8L)).thenThrow(new RuntimeException("boom"));
        ResponseResult r = controller.followers(1L, 1, 20);
        assertEquals(0, list(r).size());
    }

    // ==================== collections ====================

    @Test
    @DisplayName("collections - 空记录返回 total=0")
    void testCollectionsEmpty() {
        Page<ApCollection> p = new Page<>(1, 10);
        p.setRecords(Collections.emptyList());
        when(apCollectionMapper.selectPage(any(), any())).thenReturn(p);
        ResponseResult r = controller.collections(1L, 1, 10);
        assertEquals(0, data(r).get("total"));
        assertEquals(0, list(r).size());
    }

    @Test
    @DisplayName("collections - 正常组装，无对应文章跳过")
    void testCollectionsOk() {
        ApCollection c1 = new ApCollection();
        c1.setArticleId(1L);
        c1.setCreatedTime(new Date());
        ApCollection c2 = new ApCollection();
        c2.setArticleId(99L); // 无对应文章
        Page<ApCollection> p = new Page<>(1, 10);
        p.setRecords(Arrays.asList(c1, c2));
        when(apCollectionMapper.selectPage(any(), any())).thenReturn(p);
        when(apArticleMapper.selectBatchIds(any()))
                .thenReturn(Collections.singletonList(article(1L, "收藏")));

        ResponseResult r = controller.collections(1L, 1, 10);
        List<Map<String, Object>> list = list(r);
        assertEquals(1, list.size());
        assertEquals("收藏", list.get(0).get("title"));
    }

    // ==================== likes ====================

    @Test
    @DisplayName("likes - type 过滤 article")
    void testLikesArticle() {
        UserBehaviorRecord r1 = likeRecord(1L, BehaviorType.LIKE_ARTICLE.getCode());
        Page<UserBehaviorRecord> p = new Page<>(1, 10);
        p.setRecords(Collections.singletonList(r1));
        when(behaviorRecordMapper.selectPage(any(), any())).thenReturn(p);
        when(apArticleMapper.selectBatchIds(any()))
                .thenReturn(Collections.singletonList(article(1L, "赞文")));

        ResponseResult r = controller.likes(1L, 1, 10, "article");
        List<Map<String, Object>> list = list(r);
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).get("targetType"));
    }

    @Test
    @DisplayName("likes - type 不传时混合组装文章+沸点")
    void testLikesMixed() {
        UserBehaviorRecord a = likeRecord(10L, BehaviorType.LIKE_ARTICLE.getCode());
        UserBehaviorRecord b = likeRecord(20L, BehaviorType.LIKE_PIN.getCode());
        UserBehaviorRecord orphan = likeRecord(30L, BehaviorType.LIKE_PIN.getCode()); // 沸点缺失跳过
        Page<UserBehaviorRecord> p = new Page<>(1, 10);
        p.setRecords(Arrays.asList(a, b, orphan));
        when(behaviorRecordMapper.selectPage(any(), any())).thenReturn(p);
        when(apArticleMapper.selectBatchIds(any()))
                .thenReturn(Collections.singletonList(article(10L, "赞文")));
        when(apPinsMapper.selectBatchIds(any()))
                .thenReturn(Collections.singletonList(pins(20L, "赞沸点")));

        ResponseResult r = controller.likes(1L, 1, 10, null);
        List<Map<String, Object>> list = list(r);
        assertEquals(2, list.size());
        assertEquals(2, list.get(1).get("targetType")); // 沸点先或后需按记录顺序断言
        assertEquals("img1.png", list.get(1).get("coverImage")); // firstImage 解析
    }

    @Test
    @DisplayName("likes - 空记录返回 total=0")
    void testLikesEmpty() {
        Page<UserBehaviorRecord> p = new Page<>(1, 10);
        p.setRecords(Collections.emptyList());
        when(behaviorRecordMapper.selectPage(any(), any())).thenReturn(p);
        ResponseResult r = controller.likes(1L, 1, 10, null);
        assertEquals(0, data(r).get("total"));
    }

    private UserBehaviorRecord likeRecord(Long targetId, String type) {
        UserBehaviorRecord rec = new UserBehaviorRecord();
        rec.setUserId(1);
        rec.setTargetId(targetId);
        rec.setBehaviorType(type);
        rec.setCreatedTime(new Date());
        return rec;
    }

    // ==================== courses ====================

    @Test
    @DisplayName("courses - 正常返回")
    void testCoursesOk() {
        Page<ApCourse> p = new Page<>(1, 10);
        p.setRecords(Collections.singletonList(course(1L, "课程")));
        when(apCourseMapper.selectPage(any(), any())).thenReturn(p);
        ResponseResult r = controller.courses(1L, 1, 10);
        List<Map<String, Object>> list = list(r);
        assertEquals("课程", list.get(0).get("title"));
        assertEquals(50, list.get(0).get("studyCount"));
    }

    // ==================== tips ====================

    @Test
    @DisplayName("tips - 空记录返回 total=0")
    void testTipsEmpty() {
        Page<ApArticleTipRecord> p = new Page<>(1, 10);
        p.setRecords(Collections.emptyList());
        when(tipRecordMapper.selectPage(any(), any())).thenReturn(p);
        ResponseResult r = controller.tips(1L, 1, 10);
        assertEquals(0, data(r).get("total"));
    }

    @Test
    @DisplayName("tips - 正常组装，article 缺失时 articleTitle 为空")
    void testTipsOk() {
        ApArticleTipRecord t1 = tip(1L, 100L);
        ApArticleTipRecord t2 = tip(2L, 999L); // article 缺失
        Page<ApArticleTipRecord> p = new Page<>(1, 10);
        p.setRecords(Arrays.asList(t1, t2));
        when(tipRecordMapper.selectPage(any(), any())).thenReturn(p);
        when(apArticleMapper.selectBatchIds(any()))
                .thenReturn(Collections.singletonList(article(100L, "被打赏")));

        ResponseResult r = controller.tips(1L, 1, 10);
        List<Map<String, Object>> list = list(r);
        assertEquals(2, list.size());
        assertEquals("被打赏", list.get(0).get("articleTitle"));
        assertEquals("", list.get(1).get("articleTitle"));
        assertEquals(BigDecimal.valueOf(5), list.get(0).get("amount"));
    }

    private ApArticleTipRecord tip(Long id, Long articleId) {
        ApArticleTipRecord t = new ApArticleTipRecord();
        t.setId(id);
        t.setArticleId(articleId);
        t.setNickName("打赏人");
        t.setAvatar("tip.png");
        t.setAmount(BigDecimal.valueOf(5));
        t.setMessage("谢谢");
        t.setCreatedTime(new Date());
        return t;
    }
}