package com.zhuri.coding.content.service.article.impl;

import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.mapper.interaction.ApArticleExposureMapper;
import com.zhuri.coding.content.mapper.interaction.ApBehaviorLikesMapper;
import com.zhuri.coding.content.mapper.interaction.ApBrowseHistoryMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.model.article.dtos.ArticleInteractionCountDTO;
import com.zhuri.coding.model.article.dtos.ArticleRecommendDto;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.pojos.ApBehaviorLikes;
import com.zhuri.coding.model.behavior.pojos.ApBrowseHistory;
import com.zhuri.coding.model.behavior.pojos.ApCollection;
import com.zhuri.coding.model.follow.pojos.ApFollow;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
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
    @Mock
    private ApBrowseHistoryMapper apBrowseHistoryMapper;
    @Mock
    private ApBehaviorLikesMapper apBehaviorLikesMapper;
    @Mock
    private ApCollectionMapper apCollectionMapper;
    @Mock
    private ApArticleExposureMapper apArticleExposureMapper;

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
        // 基础评分权重（与 application.yml 默认一致）
        ReflectionTestUtils.setField(recommendService, "weightScore", 0.25);
        ReflectionTestUtils.setField(recommendService, "weightRecency", 0.20);
        ReflectionTestUtils.setField(recommendService, "weightViews", 0.15);
        ReflectionTestUtils.setField(recommendService, "weightLikes", 0.15);
        ReflectionTestUtils.setField(recommendService, "weightComments", 0.10);
        ReflectionTestUtils.setField(recommendService, "weightCollects", 0.10);
        ReflectionTestUtils.setField(recommendService, "scoreNormalizeMax", 10000);
        ReflectionTestUtils.setField(recommendService, "recencyWindowDays", 7);
        // 服务端已读去重
        ReflectionTestUtils.setField(recommendService, "excludeReadCount", 500);
        ReflectionTestUtils.setField(recommendService, "excludeReadWindowDays", 7);
        // 数据回流闭环（近期互动热度）
        ReflectionTestUtils.setField(recommendService, "interactionBoostMax", 0.20);
        ReflectionTestUtils.setField(recommendService, "interactionWindowSeconds", 86400L);
        ReflectionTestUtils.setField(recommendService, "interactionCacheTtlSeconds", 300L);
        // 曝光/行为闭环（负反馈）
        ReflectionTestUtils.setField(recommendService, "exposureWindowDays", 3);
        ReflectionTestUtils.setField(recommendService, "exposurePenaltyMax", 0.10);
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
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any())).thenReturn(null);
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
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
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
        when(apArticleMapper.selectRecommendCandidatesByAuthors(any(), anyInt(), anyInt(), any()))
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

    @Test
    @DisplayName("latest 返回真实总数 total（countLatestArticles 兜底）")
    void latestReturnsRealTotal() {
        // 本页仅剩 1 条，但全量总数为 7 —— total 应是真实总数而非当页条数
        when(apArticleMapper.selectLatestArticles(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(article(9L, "a", 0, 0, 0, 0, 0)));
        when(apArticleMapper.countLatestArticles(any(), any(), any())).thenReturn(7L);
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(2, 1, "__all__", "latest")).getData();
        assertEquals(7L, data.get("total"));
        assertEquals(1, ((List<?>) data.get("list")).size());
    }

    // ---------- recommend 分栏：正常序列 ----------
    @Test
    @DisplayName("recommend 多候选输出全局序列并跨页 hasMore")
    void recommendMultiCandidates() {
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(
                        article(1L, "java", 100, 20, 5, 3, 80),
                        article(2L, "go", 10, 2, 1, 0, 20),
                        article(3L, "rust", 50, 8, 2, 1, 60))));
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(2, 0, "6", "recommend")).getData();
        assertEquals(3, data.get("total"));
        assertEquals(2, ((List<?>) data.get("list")).size());
        assertEquals(true, data.get("hasMore"));
        // 推荐分栏 channel 数字解析为频道ID
        org.mockito.Mockito.verify(apArticleMapper).selectRecommendCandidates(anyInt(), anyInt(), any(), anyInt(), any());
    }

    // ---------- excludeIds 透传 ----------
    @Test
    @DisplayName("recommend 携带 excludeIds 时透传至候选查询以排除已读")
    void excludeIdsPropagation() throws Exception {
        List<Long> excludes = List.of(1L, 2L, 3L);
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(article(4L, "java", 0, 0, 0, 0, 10))));

        ArticleRecommendDto d = dto(10, 0, "__all__", "recommend");
        d.setExcludeIds(excludes);
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(d).getData();
        assertEquals(1, data.get("total"));

        org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(apArticleMapper).selectRecommendCandidates(any(), anyInt(), any(), anyInt(), captor.capture());
        assertEquals(excludes, captor.getValue());
    }

    // ---------- 服务端已读去重：结合浏览历史主动排除 ----------
    @Test
    @DisplayName("登录用户近 N 天已读文章被合并进 excludeIds，主动去重")
    void serverReadExcluded() {
        login(1);
        // 浏览历史：用户已读过文章 99
        ApBrowseHistory read = new ApBrowseHistory();
        read.setArticleId(99L);
        when(apBrowseHistoryMapper.selectList(any())).thenReturn(List.of(read));
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(article(1L, "java", 0, 0, 0, 0, 0))));

        recommendService.recommendAll(dto(10, 0, "__all__", "recommend"));

        org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(apArticleMapper).selectRecommendCandidates(any(), anyInt(), any(), anyInt(), captor.capture());
        assertTrue(captor.getValue().contains(99L));
    }

    // ---------- 数据回流闭环：近期阅读热度反哺排序 ----------
    @Test
    @DisplayName("近期阅读次数更高的候选获得「近期热度」加成而排前")
    void recentInteractionRanksFirst() {
        when(apBrowseHistoryMapper.selectRecentInteractionCounts(any(), any()))
                .thenReturn(List.of(interactionCount(2L, 100L), interactionCount(1L, 3L)));
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(
                        article(1L, "go", 0, 0, 0, 0, 0),
                        article(2L, "java", 0, 0, 0, 0, 0))));

        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(10, 0, "__all__", "recommend")).getData();
        List<?> list = (List<?>) data.get("list");
        // 近期阅读 100 次的文章 2 应排在首位（seedJitter=0，排序确定）
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals("2", String.valueOf(first.get("id")));
    }

    // ---------- 曝光/行为闭环：曝光而未消费 → 降权 ----------
    @Test
    @DisplayName("近期被曝光多次而未消费的文章被降权，未曝光同分候选排前")
    void exposurePenaltyDemotesRepeatedlyExposed() {
        login(1);
        // 候选 1 对该用户近期曝光 5 次但从未消费；候选 2 未曝光。两者其余指标相同 → 应拉开
        when(apArticleExposureMapper.selectUserExposureCounts(anyLong(), any(), any()))
                .thenReturn(List.of(interactionCount(1L, 5L)));
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(
                        article(1L, "go", 0, 0, 0, 0, 0),
                        article(2L, "java", 0, 0, 0, 0, 0))));

        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(10, 0, "__all__", "recommend")).getData();
        List<?> list = (List<?>) data.get("list");
        // 曝光而未消费的候选1（5次/最大5次 → 满额降权0.10）应排到未曝光的候选2之后
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals("2", String.valueOf(first.get("id")));
    }

    @Test
    @DisplayName("曝光降权：无曝光记录时 penalty 恒为 0，不改变排序")
    void exposurePenaltyEmptyNoEffect() {
        login(1);
        // 无任何曝光/无候选曝光命中 → 降权应为 0
        when(apArticleExposureMapper.selectUserExposureCounts(anyLong(), any(), any())).thenReturn(List.of());
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(
                        article(1L, "go", 0, 0, 0, 0, 0),
                        article(2L, "java", 0, 0, 0, 0, 0))));

        Double p = ReflectionTestUtils.invokeMethod(recommendService, "exposurePenalty",
                Collections.emptyMap(), 0, 1L);
        assertEquals(0.0, p);
    }

    private ArticleInteractionCountDTO interactionCount(Long articleId, Long cnt) {
        ArticleInteractionCountDTO dto = new ArticleInteractionCountDTO();
        dto.setArticleId(articleId);
        dto.setCnt(cnt);
        return dto;
    }

    // ---------- 兴趣个性化加分：命中兴趣标签的候选优先 ----------
    @Test
    @DisplayName("用户兴趣画像：命中收藏文章标签的候选获得加成")
    void interestBoostRanksMatchFirst() throws Exception {
        // 显式放开兴趣加成幅度以覆盖"命中优先"路径（seedJitter 未注入=0，排序确定性）
        ReflectionTestUtils.setField(recommendService, "interestBoostMax", 0.5);
        // 用户收藏过一篇 java 文章（权重最高）
        login(1);
        when(apBrowseHistoryMapper.selectList(any())).thenReturn(List.of());
        when(apBehaviorLikesMapper.selectList(any())).thenReturn(List.of());
        ApCollection collect = new ApCollection();
        collect.setArticleId(20L); // java 类
        when(apCollectionMapper.selectList(any())).thenReturn(List.of(collect));

        // 兴趣来源文章的标签回读
        ApArticle javaSrc = article(20L, "java", 0, 0, 0, 0, 0);
        when(apArticleMapper.selectBatchIds(any())).thenReturn(List.of(javaSrc));

        // 候选：java(命中兴趣) vs go(不命中)；none 命中 → 靠兴趣加成拉开
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(
                        article(1L, "go", 0, 0, 0, 0, 0),
                        article(2L, "java", 0, 0, 0, 0, 0))));

        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(10, 0, "__all__", "recommend")).getData();
        List<?> list = (List<?>) data.get("list");
        // 命中最强兴趣标签（java）的候选应排在首位（兴趣加分0.5，本测试 seedJitter=0，排序确定）
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals("2", String.valueOf(first.get("id")));
    }

    // ---------- 配额贪心：标签限额 + 配额不足降级追加 ----------
    @Test
    @DisplayName("同标签超 maxPerTag：配额跳过后用余额追加补齐")
    void quotaTagCapAndFallback() {
        // 3 篇同标签 java，maxPerTag=2；配额内只取 2 篇，剩余 1 篇由降级追加
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
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
        when(apArticleMapper.selectRecommendCandidates(any(), anyInt(), any(), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(a)));
        Map<?, ?> data = (Map<?, ?>) recommendService.recommendAll(dto(10, 0, "__all__", "recommend")).getData();
        // 空标签归入 untaggedBucket
        assertEquals(1, data.get("total"));
        assertEquals(1, ((List<?>) data.get("list")).size());
    }

    // ---------- seededJitter：seed 驱动的确定性抖动弹 ----------
    @Test
    @DisplayName("seededJitter：null 返回 0，同 seed+id 恒定，值界内，seed 不同则抖动不同")
    void seededJitterDeterministic() {
        ReflectionTestUtils.setField(recommendService, "seedJitter", 0.15);

        Double nullVal = ReflectionTestUtils.invokeMethod(recommendService, "seededJitter", 123L, (Long) null);
        assertEquals(0.0, nullVal);

        // 同 seed + 同 articleId → 恒定
        Double a1 = ReflectionTestUtils.invokeMethod(recommendService, "seededJitter", 123L, 5L);
        Double a2 = ReflectionTestUtils.invokeMethod(recommendService, "seededJitter", 123L, 5L);
        assertEquals(a1, a2);

        // 抖动界内：[-seedJitter, +seedJitter]
        assertTrue(a1 >= -0.15 && a1 <= 0.15);

        // 不同 seed → 抖动不同（同一 articleId 下极难巧合相同）
        Double b1 = ReflectionTestUtils.invokeMethod(recommendService, "seededJitter", 999L, 5L);
        assertFalse(b1.equals(a1));
    }

    // ---------- interestBoost：兴趣命中得分 ----------
    @Test
    @DisplayName("interestBoost：空画像/无命中/部分命中/全命中")
    void interestBoostScenarios() {
        ReflectionTestUtils.setField(recommendService, "interestBoostMax", 0.30);
        Map<String, Double> weights = new HashMap<>();
        weights.put("java", 4.0);
        weights.put("go", 1.0);
        double weightMax = 4.0;

        ApArticle java = article(1L, "java", 0, 0, 0, 0, 0);
        ApArticle go = article(3L, "go", 0, 0, 0, 0, 0);
        ApArticle rust = article(2L, "rust", 0, 0, 0, 0, 0);

        // 空画像 → 0
        Double emptyBoost = ReflectionTestUtils.invokeMethod(recommendService, "interestBoost",
                Collections.emptyMap(), weightMax, java);
        assertEquals(0.0, emptyBoost);

        // 候选标签与画像无交集 → 0
        Double noMatch = ReflectionTestUtils.invokeMethod(recommendService, "interestBoost",
                weights, weightMax, rust);
        assertEquals(0.0, noMatch);

        // 部分命中：权重 1/最大 4 → 0.25 * 0.30
        Double partial = ReflectionTestUtils.invokeMethod(recommendService, "interestBoost",
                weights, weightMax, go);
        assertEquals(0.075, partial, 1e-9);

        // 全命中（权重=最大）→ 封顶 interestBoostMax
        Double full = ReflectionTestUtils.invokeMethod(recommendService, "interestBoost",
                weights, weightMax, java);
        assertEquals(0.30, full, 1e-9);
    }

    // ---------- buildInterestWeights：兴趣画像聚合 ----------
    @Test
    @DisplayName("buildInterestWeights：浏览/点赞/收藏按 1/3/4 折算并聚合到标签")
    void buildInterestWeightsAggregation() {
        ReflectionTestUtils.setField(recommendService, "interestBrowseSample", 100);
        login(1);

        // 浏览文章10(go)、点赞文章11(rust)、收藏文章20(java)
        ApBrowseHistory browse = new ApBrowseHistory();
        browse.setArticleId(10L);
        when(apBrowseHistoryMapper.selectList(any())).thenReturn(List.of(browse));

        ApBehaviorLikes like = new ApBehaviorLikes();
        like.setEntryId(11L);
        when(apBehaviorLikesMapper.selectList(any())).thenReturn(List.of(like));

        ApCollection collect = new ApCollection();
        collect.setArticleId(20L);
        when(apCollectionMapper.selectList(any())).thenReturn(List.of(collect));

        // 兴趣来源文章回读标签
        when(apArticleMapper.selectBatchIds(any())).thenReturn(new ArrayList<>(List.of(
                article(10L, "go", 0, 0, 0, 0, 0),
                article(11L, "rust", 0, 0, 0, 0, 0),
                article(20L, "java", 0, 0, 0, 0, 0))));

        Map<String, Double> weights = ReflectionTestUtils.invokeMethod(recommendService,
                "buildInterestWeights", 1);
        assertEquals(1.0, weights.get("go"), 1e-9);
        assertEquals(3.0, weights.get("rust"), 1e-9);
        assertEquals(4.0, weights.get("java"), 1e-9);
        assertEquals(3, weights.size());
    }

    @Test
    @DisplayName("buildInterestWeights：无行为历史返回空画像")
    void buildInterestWeightsEmpty() {
        ReflectionTestUtils.setField(recommendService, "interestBrowseSample", 100);
        login(1);
        // 三个行为查询均为空（Mockito 默认返回空列表）
        Map<String, Double> weights = ReflectionTestUtils.invokeMethod(recommendService,
                "buildInterestWeights", 1);
        assertTrue(weights.isEmpty());
    }

    // ---------- 兴趣画像 Redis 缓存 ----------
    @Test
    @DisplayName("getInterestWeights：命中缓存直接返回，不访问行为库")
    void interestCacheHit() {
        org.redisson.api.RedissonClient redissonClient = org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class);
        org.redisson.api.RBucket bucket = org.mockito.Mockito.mock(org.redisson.api.RBucket.class);
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn("{\"java\":4.0,\"go\":1.0}");
        ReflectionTestUtils.setField(recommendService, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(recommendService, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(recommendService, "interestCacheTtlSeconds", 1800L);

        Map<String, Double> w = ReflectionTestUtils.invokeMethod(recommendService, "getInterestWeights", 1);
        assertEquals(4.0, w.get("java"));
        assertEquals(1.0, w.get("go"));
        // 命中缓存 → 不应触碰任何行为库，也不应回写
        org.mockito.Mockito.verifyNoInteractions(apBrowseHistoryMapper, apBehaviorLikesMapper, apCollectionMapper);
        org.mockito.Mockito.verify(bucket, org.mockito.Mockito.never()).set(any(), anyLong(), any());
    }

    @Test
    @DisplayName("getInterestWeights：未命中则回源计算并写入缓存")
    void interestCacheMissComputesAndWrites() {
        org.redisson.api.RedissonClient redissonClient = org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class);
        org.redisson.api.RBucket bucket = org.mockito.Mockito.mock(org.redisson.api.RBucket.class);
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);
        ReflectionTestUtils.setField(recommendService, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(recommendService, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(recommendService, "interestCacheTtlSeconds", 1800L);

        // 行为历史：收藏 java 文章 → 兴趣标签 java=4
        when(apBrowseHistoryMapper.selectList(any())).thenReturn(List.of());
        when(apBehaviorLikesMapper.selectList(any())).thenReturn(List.of());
        ApCollection c = new ApCollection();
        c.setArticleId(20L);
        when(apCollectionMapper.selectList(any())).thenReturn(List.of(c));
        when(apArticleMapper.selectBatchIds(any())).thenReturn(List.of(article(20L, "java", 0, 0, 0, 0, 0)));

        Map<String, Double> w = ReflectionTestUtils.invokeMethod(recommendService, "getInterestWeights", 1);
        assertEquals(4.0, w.get("java"));
        // 未命中 → 回源计算 + 写入缓存（带 TTL）
        org.mockito.Mockito.verify(bucket).set(any(), anyLong(), any());
    }
}