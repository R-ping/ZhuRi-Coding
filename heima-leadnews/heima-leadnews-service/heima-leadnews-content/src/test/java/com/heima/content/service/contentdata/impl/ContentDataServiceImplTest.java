package com.heima.content.service.contentdata.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.column.ApColumnMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.column.pojos.ApColumn;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.pins.pojos.ApPins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ContentDataServiceImpl 单元测试（创作中心数据统计：文章/专栏/沸点）
 *
 * 纯 @Service，依赖经 @Autowired 由 @InjectMocks 注入三个 mapper Mock。
 * 覆盖：
 * - 文章：getArticleStatistics 当日 vs 前日趋势、getArticleTrend 逐日趋势、getArticleDetail 分页细节(null字段兜底)；
 * - 专栏：getColumnStatistics / getColumnTrend / getColumnDetail；
 * - 沸点：getPinStatistics / getPinTrend / getPinDetail；
 * - 私有聚合 helper 经各统计数据间接覆盖，含空列表与 null 指标兜底。
 */
class ContentDataServiceImplTest {

    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApColumnMapper apColumnMapper;
    @Mock
    private ApPinsMapper apPinsMapper;

    @InjectMocks
    private ContentDataServiceImpl contentDataService;

    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ---------- 辅助 ----------

    private Date now() {
        return new Date();
    }

    private ApArticle article(long id, Integer views, Integer likes, Integer comment, Integer collect, Date t) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setViews(views);
        a.setLikes(likes);
        a.setComment(comment);
        a.setCollection(collect);
        a.setPublishTime(t);
        return a;
    }

    private ApColumn column(long id, Integer subscribe, Date t) {
        ApColumn c = new ApColumn();
        c.setId(id);
        c.setSubscribeCount(subscribe);
        c.setCreatedTime(t);
        return c;
    }

    private ApPins pin(long id, Integer likes, Integer comment, Date t) {
        ApPins p = new ApPins();
        p.setId(id);
        p.setLikes(likes);
        p.setComment(comment);
        p.setPublishTime(t);
        return p;
    }

    private String today() {
        return DAY.format(now());
    }

    private Date atTodayStart() {
        return new Date(System.currentTimeMillis() - 3600_000L); // 今天略早时刻，落在当日区间
    }

    // ==================== Article ====================

    @Test
    @DisplayName("getArticleStatistics - 当日与前日指标及趋势差")
    void testArticleStatistics() {
        Date t = atTodayStart();
        String d = today();
        when(apArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(article(1L, 100, 10, 5, 3, t)));
        ResponseResult r = contentDataService.getArticleStatistics(5L, d, d);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, data.get("totalCount"));
        // 当日 1 vs 前日 0
        assertEquals(1, data.get("totalTrend"));
        assertNotNull(data.get("readCount"));
        assertNotNull(data.get("showTrend"));
    }

    @Test
    @DisplayName("getArticleStatistics - 空列表各指标为 0")
    void testArticleStatisticsEmpty() {
        when(apArticleMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList());
        ResponseResult r = contentDataService.getArticleStatistics(5L, today(), today());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(0, data.get("totalCount"));
    }

    @Test
    @DisplayName("getArticleTrend - 逐日趋势含空天数默认值")
    void testArticleTrend() {
        Date t = atTodayStart();
        String d = today();
        when(apArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(article(1L, null, null, null, null, t)));
        ResponseResult r = contentDataService.getArticleTrend(5L, d, d, 1);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
        assertEquals(1, list.size());
        Map<String, Object> point = list.get(0);
        assertEquals(d, point.get("date"));
        assertEquals(1, point.get("showCount")); // showCount = 文章数(1)
        assertEquals(0, point.get("readCount")); // views null 兜底
    }

    @Test
    @DisplayName("getArticleDetail - 分页返回并映射 null 字段兜底")
    void testArticleDetail() {
        Page<ApArticle> page = new Page<>(0, 10);
        page.setRecords(Arrays.asList(
                article(1L, 10, 2, 1, 0, now()),
                article(2L, null, null, null, null, now())));
        page.setTotal(2);
        when(apArticleMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        ResponseResult r = contentDataService.getArticleDetail(5L, "", "", 0, 10);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertEquals(2, list.size());
        assertEquals(0, list.get(1).get("readCount")); // views null -> 0
        assertEquals(2L, data.get("total"));
    }

    // ==================== Column ====================

    @Test
    @DisplayName("getColumnStatistics - 订阅数及趋势")
    void testColumnStatistics() {
        Date t = atTodayStart();
        String d = today();
        when(apColumnMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(column(1L, 20, t)));
        ResponseResult r = contentDataService.getColumnStatistics(5L, d, d);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, data.get("totalCount"));
        assertEquals(20, data.get("subscribeCount"));
        assertEquals(1, data.get("totalTrend"));
    }

    @Test
    @DisplayName("getColumnTrend - 逐日订阅趋势")
    void testColumnTrend() {
        Date t = atTodayStart();
        String d = today();
        when(apColumnMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(column(1L, null, t)));
        ResponseResult r = contentDataService.getColumnTrend(5L, d, d, 1);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
        assertEquals(1, list.size());
        assertEquals(0, list.get(0).get("subscribeCount")); // null 兜底
    }

    @Test
    @DisplayName("getColumnDetail - 分页与字段映射")
    void testColumnDetail() {
        Page<ApColumn> page = new Page<>(0, 10);
        page.setRecords(Arrays.asList(column(1L, 9, now()), column(2L, null, now())));
        page.setTotal(2);
        when(apColumnMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        ResponseResult r = contentDataService.getColumnDetail(5L, null, null, 0, 10);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertEquals(0, list.get(1).get("subscribeCount"));
        assertEquals(2L, data.get("total"));
    }

    // ==================== Pin ====================

    @Test
    @DisplayName("getPinStatistics - 点赞/评论数及趋势")
    void testPinStatistics() {
        Date t = atTodayStart();
        String d = today();
        when(apPinsMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(pin(1L, 30, 4, t)));
        ResponseResult r = contentDataService.getPinStatistics(5L, d, d);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(30, data.get("likeCount"));
        assertEquals(4, data.get("commentCount"));
        assertEquals(1, data.get("totalTrend"));
    }

    @Test
    @DisplayName("getPinTrend - 逐日趋势与空列表")
    void testPinTrend() {
        Date t = atTodayStart();
        String d = today();
        when(apPinsMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(pin(1L, null, null, t)));
        ResponseResult r = contentDataService.getPinTrend(5L, d, d, 1);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
        assertEquals(1, list.size());
        assertEquals(0, list.get(0).get("likeCount"));
    }

    @Test
    @DisplayName("getPinDetail - 分页与字段映射")
    void testPinDetail() {
        Page<ApPins> page = new Page<>(0, 10);
        page.setRecords(Arrays.asList(pin(1L, 8, 2, now()), pin(2L, null, null, now())));
        page.setTotal(2);
        when(apPinsMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        ResponseResult r = contentDataService.getPinDetail(5L, "", "", 0, 10);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertEquals(2, list.size());
        assertEquals(0, list.get(1).get("likeCount"));
    }
}