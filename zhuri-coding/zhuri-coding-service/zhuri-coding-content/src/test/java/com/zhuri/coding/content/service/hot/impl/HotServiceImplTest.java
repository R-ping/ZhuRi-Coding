package com.zhuri.coding.content.service.hot.impl;

import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.model.article.vos.HotArticleVo;
import com.zhuri.coding.model.article.vos.HotAuthorVo;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * HotServiceImpl 单元测试（热门榜单：热门文章/收藏榜/热门作者/规则文案）
 *
 * 纯 @Service 类，@InjectMocks 注入 collection/follow mapper 与 JdbcTemplate。
 * 覆盖：
 * - getHotArticles 综合/分类查询、limit 兜底、收藏状态、登录/未登录；
 * - getCollectedArticles 收藏榜；
 * - getHotAuthors 周期过滤、关注与粉丝统计；
 * - getHotMeta/buildRule 各 tab 文案；
 * - normalizeLimit/toInteger/toLong 边界。
 */
class HotServiceImplTest {

    @Mock
    private ApCollectionMapper apCollectionMapper;
    @Mock
    private ApFollowMapper apFollowMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private HotServiceImpl hotService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
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

    private Map<String, Object> articleRow(int id) {
        return java.util.Map.of(
                "id", id, "title", "标题" + id, "author_id", 9L,
                "author_name", "作者", "author_image", "img",
                "score", 88, "views", 100, "likes", 20, "comment", 5, "collection", 3);
    }

    // ---------- getHotArticles ----------
    @Test
    @DisplayName("getHotArticles 综合查询并填充收藏态")
    void getHotArticlesComprehensive() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(articleRow(1), articleRow(2)));
        when(apCollectionMapper.selectCount(any())).thenReturn(0L);

        List<HotArticleVo> list = hotService.getHotArticles("comprehensive", 10);
        assertEquals(2, list.size());
        HotArticleVo vo = list.get(0);
        assertEquals(1, vo.getRank());
        assertEquals(1L, vo.getId());
        assertEquals(88, vo.getScore());
        assertFalse(vo.getIsCollected());
    }

    @Test
    @DisplayName("getHotArticles 分类查询与登录时的收藏判定")
    void getHotArticlesCategoryLoggedIn() {
        login(1);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(articleRow(3)));
        when(apCollectionMapper.selectCount(any())).thenReturn(1L);

        List<HotArticleVo> list = hotService.getHotArticles("backend", 12);
        assertEquals(1, list.size());
        assertTrue(list.get(0).getIsCollected());
    }

    @Test
    @DisplayName("getHotArticles limit 兜底与非法分类")
    void getHotArticlesLimitGuard() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        // null limit 走默认，非法分类 channelName 为 null → 综合
        assertEquals(0, hotService.getHotArticles("unknown", null).size());
    }

    // ---------- getCollectedArticles ----------
    @Test
    @DisplayName("getCollectedArticles 收藏榜按收藏数排序")
    void getCollectedArticles() {
        login(1);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(articleRow(1)));
        when(apCollectionMapper.selectCount(any())).thenReturn(1L);

        List<HotArticleVo> list = hotService.getCollectedArticles(null);
        assertEquals(1, list.size());
        assertEquals(1L, list.get(0).getId());
        assertTrue(list.get(0).getIsCollected());
    }

    // ---------- getHotAuthors ----------
    @Test
    @DisplayName("getHotAuthors 获取作者榜并计算质量文章与粉丝数")
    void getHotAuthors() {
        login(1);
        Map<String, Object> row = java.util.Map.of(
                "user_id", 7L, "user_name", "大佬", "user_image", "i",
                "level", 3, "hot_score", 100L, "total_likes", 10L, "total_collections", 2L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(5);
        when(apFollowMapper.selectCount(any())).thenReturn(8L);

        List<HotAuthorVo> list = hotService.getHotAuthors("weekly", 20);
        assertEquals(1, list.size());
        HotAuthorVo vo = list.get(0);
        assertEquals(7, vo.getUserId());
        assertEquals(5, vo.getQualityArticles());
        assertEquals(8, vo.getFans());
        assertTrue(vo.getIsFollowed());
    }

    @Test
    @DisplayName("getHotAuthors 未登录时关注为 false，total null 兜底为 0")
    void getHotAuthorsAnonymousNulls() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("user_id", 7L);
        row.put("user_name", "大佬");
        row.put("user_image", "i");
        row.put("level", null);
        row.put("hot_score", null);
        row.put("total_likes", null);
        row.put("total_collections", null);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(0);
        when(apFollowMapper.selectCount(any())).thenReturn(0L);

        // 未登录，currentUserId 为 null，level null 兜底为 0
        HotAuthorVo vo = hotService.getHotAuthors("monthly", 20).get(0);
        assertEquals(0, vo.getLevel());
        assertEquals(0L, vo.getHotScore());
        assertFalse(vo.getIsFollowed());
    }

    // ---------- getHotMeta ----------
    @Test
    @DisplayName("getHotMeta 各 tab 规则文案")
    void getHotMeta() {
        assertTrue(((String) hotService.getHotMeta("article", "comprehensive", null).get("rule")).contains("最近3日内"));
        assertTrue(((String) hotService.getHotMeta("article", "java", null).get("rule")).contains("最近7日内"));
        assertTrue(((String) hotService.getHotMeta("collected", null, null).get("rule")).contains("最近3个月内"));
        assertTrue(((String) hotService.getHotMeta("authors", null, "monthly").get("rule")).contains("最近30日内"));
        assertTrue(((String) hotService.getHotMeta("authors", null, "weekly").get("rule")).contains("最近7日内"));
        assertEquals("", hotService.getHotMeta(null, null, null).get("rule"));
        assertEquals("", hotService.getHotMeta("other", null, null).get("rule"));
    }
}