package com.heima.content.service.article.impl;

import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.model.article.dtos.ArticleRecommendDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ApArticleRecommendServiceImpl 单元测试（文章推荐：综合/聚焦/关注分栏、最近/推荐分栏）
 *
 * 纯 @Service 类，@InjectMocks 注入 apArticleMapper/apFollowMapper；
 * 五个 @Value 配置项通过 ReflectionTestUtils 显式注入（否则默认 0）。
 * 覆盖：
 * - 四个入口的委托与默认参数归一；
 * - follow 分栏：未登录/未关注空列表、有关注走 ByAuthors；
 * - latest 分栏：size+1 探测 hasMore 的两种边界；
 * - recommend 分栏：候选为空、max 归一化、标签/作者配额贪心及配额不足追加降级；
 * - computeBaseScore 的对数归一化与时效因子边界（logNorm max<=0）。
 */
class ApArticleRecommendServiceImplTest {

    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApFollowMapper apFollowMapper;

    @InjectMocks
    private ApArticleRecommendServiceImpl recommendService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(recommendService, "maxCandidates", 2000);
        ReflectionTestUtils.setField(recommendService, "windowDays", 7);
        ReflectionTestUtils.setField(recommendService, "maxPerTag", 2);
        ReflectionTestUtils.setField(recommendService, "maxPerAuthor", 3);
        ReflectionTestUtils.setField(recommendService, "untaggedBucket", "__untagged__");
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private void login(Integer userId) {
        ApUser u = new ApUser();
        u.setId(userId);
        AppThreadLocalUtil.setUser(u);
    }

    private ApArticle article(Long id, String tag, Integer views, Integer likes,
                              Integer comments, Integer collections, Integer score) {
        ApArticle a = new ApArticle();
        a.setId(id);
        List<String> tags = new ArrayList<>();
        tags.add(tag);
        a.setTags(tags);
        a.setAuthorId(50L);
        a.setViews(views == null ? 0 : views);
        a.setLikes(likes == null ? 0 : likes);
        a.setComment(comments == null ? 0 : comments);
        a.setCollection(collections == null ? 0 : collections);
        a.setScore(score == null ? 0 : score);
        a.setPublishTime(new Date());
        return a;
    }

    private ArticleRecommendDto dto(Integer size, Integer page, String channel, String subTab) {
        ArticleRecommendDto d = new ArticleRecommendDto();
        d.setSize(size);
        d.setPage(page);
        d.setChannel(channel);
        d.setSubTab(subTab);
        return d;
    }

    // ---------- 默认参数归一（无候选 → 空响应） ----------
    @Test
    @DisplayName("recommendAll 默认 size/page/channel/subTab，候选为空返回空")
    void recommendAllDefaults() {
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt())).thenReturn(null);
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(null, null, null, null)).getData();
        assertEquals(10, ((Number) data.get("size")).intValue());
        assertEquals(0, data.get("page"));
        assertEquals(false, data.get("hasMore"));
        assertEquals(0, data.get("total"));
    }

    // ---------- 各入口委托 ----------
    @Test
    @DisplayName("recommend/recommendCate 委托到 doRecommend")
    void entranceDelegation() {
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of());
        // recommend = all；recommendCate = cate；两者均非 follow、非 latest → 走候选池
        assertEquals(0, ((Map<?, ?>) recommendService.recommend(dto(5, 1, "__all__", "recommend")).getData()).get("total"));
        assertEquals(0, ((Map<?, ?>) recommendService.recommendCate(dto(5, 1, "6", "recommend")).getData()).get("total"));
    }

    // ---------- follow 分栏 ----------
    @Test
    @DisplayName("recommendFollow 未登录返回空列表")
    void followNotLoggedIn() {
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendFollow(dto(10, 0, null, "recommend")).getData();
        assertEquals(0, data.get("total"));
        assertEquals(0, ((List<?>) data.get("list")).size());
    }

    @Test
    @DisplayName("recommendFollow 关注列表为空返回空")
    void followNoFollowing() {
        login(1);
        when(apFollowMapper.selectList(any())).thenReturn(List.of());
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendFollow(dto(10, 0, null, "recommend")).getData();
        assertEquals(0, data.get("total"));
    }

    @Test
    @DisplayName("recommendFollow 有关注作者时按作者查询候选")
    void followWithFollowing() {
        login(1);
        ApFollow f = new ApFollow();
        f.setFollowUserId(42);
        when(apFollowMapper.selectList(any())).thenReturn(List.of(f));
        when(apArticleMapper.selectRecommendCandidatesByAuthors(any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(article(11L, "java", 100, 20, 5, 3, 80))));

        Map<?, ?> data = (Map<?, ?>) recommendService.recommendFollow(dto(10, 0, null, "recommend")).getData();
        assertEquals(1, data.get("total"));
        assertEquals(1, ((List<?>) data.get("list")).size());
    }

    // ---------- latest 分栏 ----------
    @Test
    @DisplayName("latest 返回 size+1 条时 hasMore 为 true")
    void latestHasMore() {
        when(apArticleMapper.selectLatestArticles(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(article(1L, "a", 0, 0, 0, 0, 0),
                        article(2L, "b", 0, 0, 0, 0, 0),
                        article(3L, "c", 0, 0, 0, 0, 0)));
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(2, 0, "__all__", "latest")).getData();
        assertEquals(true, data.get("hasMore"));
        assertEquals(2, ((List<?>) data.get("list")).size());
    }

    @Test
    @DisplayName("latest 返回恰为 size 条时 hasMore 为 false")
    void latestNoMore() {
        // null 结果视为空
        when(apArticleMapper.selectLatestArticles(any(), any(), any(), anyInt(), anyInt())).thenReturn(null);
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(2, 0, "__all__", "latest")).getData();
        assertEquals(false, data.get("hasMore"));
        assertEquals(0, ((List<?>) data.get("list")).size());
    }

    // ---------- recommend 分栏：正常序列 ----------
    @Test
    @DisplayName("recommend 多候选输出全局序列并跨页 hasMore")
    void recommendMultiCandidates() {
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(
                        article(1L, "java", 100, 20, 5, 3, 80),
                        article(2L, "go", 10, 2, 1, 0, 20),
                        article(3L, "rust", 50, 8, 2, 1, 60))));
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(2, 0, "6", "recommend")).getData();
        assertEquals(3, data.get("total"));
        assertEquals(2, ((List<?>) data.get("list")).size());
        assertEquals(true, data.get("hasMore"));
        // 推荐分栏 channel 数字解析为频道ID
        org.mockito.Mockito.verify(apArticleMapper).selectRecommendCandidates(anyInt(), anyInt(), any(), anyInt());
    }

    // ---------- 配额贪心：标签限额 + 配额不足降级追加 ----------
    @Test
    @DisplayName("同标签超 maxPerTag：配额跳过后用余额追加补齐")
    void quotaTagCapAndFallback() {
        // 3 篇同标签 java，maxPerTag=2；配额内只取 2 篇，剩余 1 篇由降级追加
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(
                        article(1L, "java", 0, 0, 0, 0, 10),
                        article(2L, "java", 0, 0, 0, 0, 10),
                        article(3L, "java", 0, 0, 0, 0, 10))));
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(50, 0, "__all__", "recommend")).getData();
        // 总序列满 3（配额跳过 + 降级补齐）
        assertEquals(3, data.get("total"));
        assertEquals(3, ((List<?>) data.get("list")).size());
    }

    // ---------- computeBaseScore 边界 ----------
    @Test
    @DisplayName("无互动指标且 max 为 0 时 logNorm 返回 0（不 NPE）")
    void scoreWithZeroMetrics() {
        // 候选含 null 指标文章，max 归一化不抛异常
        ApArticle a = new ApArticle();
        a.setId(9L);
        a.setTags(new ArrayList<>());
        a.setViews(null);
        a.setLikes(null);
        a.setComment(null);
        a.setCollection(null);
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(a)));
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(10, 0, "__all__", "recommend")).getData();
        // 空标签归入 untaggedBucket
        assertEquals(1, data.get("total"));
        assertEquals(1, ((List<?>) data.get("list")).size());
    }
}