package com.heima.content.service.contentdata.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.column.ApColumnMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.column.pojos.ApColumn;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.pins.pojos.ApPins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ContentDataServiceImpl 单元测试（内容数据看板：文章/专栏/沸点的统计、趋势、明细）
 *
 * 纯数据聚合服务，依赖三个 Mapper，by @InjectMocks 注入。所有接口返回 ResponseResult，data 为 Map。
 * 覆盖：
 * - Article：getArticleStatistics（当前区间 vs 前一天增减）、getArticleTrend（按天分组趋势）、getArticleDetail（日期过滤+分页）；
 * - Column：getColumnStatistics / getColumnTrend / getColumnDetail；
 * - Pin：getPinStatistics / getPinTrend / getPinDetail；
 * - 边界：空列表、null 指标字段按 0 计、非法日期回退当前时间。
 */
class ContentDataServiceImplTest {

    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApColumnMapper apColumnMapper;
    @Mock
    private ApPinsMapper apPinsMapper;

    @InjectMocks
    private ContentDataServiceImpl service;

    private final SimpleDateFormat dt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ---------- 辅助 ----------

    private Date date(String dateTime) {
        try {
            return dt.parse(dateTime);
        } catch (Exception e) {
            throw new IllegalArgumentException(dateTime, e);
        }
    }

    private ApArticle article(Long id, String publishTime, Integer views, Integer likes,
                              Integer comment, Integer collection, String title) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setTitle(title);
        a.setPublishTime(date(publishTime));
        a.setViews(views);
        a.setLikes(likes);
        a.setComment(comment);
        a.setCollection(collection);
        a.setAuthorId(1L);
        return a;
    }

    private ApColumn column(Long id, String createdTime, Integer subscribe, String title) {
        ApColumn c = new ApColumn();
        c.setId(id);
        c.setTitle(title);
        c.setCreatedTime(date(createdTime));
        c.setSubscribeCount(subscribe);
        c.setAuthorId(1L);
        return c;
    }

    private ApPins pins(Long id, String publishTime, Integer likes, Integer comment, String content) {
        ApPins p = new ApPins();
        p.setId(id);
        p.setContent(content);
        p.setPublishTime(date(publishTime));
        p.setLikes(likes);
        p.setComment(comment);
        p.setAuthorId(1L);
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseResult r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(ResponseResult r) {
        return (List<Map<String, Object>>) r.getData();
    }

    // ==================== Article ====================

    @Test
    @DisplayName("getArticleStatistics - 当前区间与前一天增减")
    void testArticleStatistics() {
        // prev=2026-08-18，current=2026-08-19~08-20
        ApArticle a1 = article(1L, "2026-08-18 10:00:00", 2, 1, 3, 4, "昨日文章");
        ApArticle a2 = article(2L, "2026-08-19 10:00:00", 5, 1, 1, 0, "今日一");
        ApArticle a3 = article(3L, "2026-08-20 10:00:00", 10, 2, 2, 1, "今日二");
        when(apArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(a1, a2, a3));

        ResponseResult r = service.getArticleStatistics(1L, "2026-08-19", "2026-08-20");

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        Map<String, Object> d = dataOf(r);
        // current 命中 a2,a3 -> total=2 show=2 read=15 like=3 comment=3 collect=1
        assertEquals(2, ((Number) d.get("totalCount")).intValue());
        assertEquals(2, ((Number) d.get("showCount")).intValue());
        assertEquals(15, ((Number) d.get("readCount")).intValue());
        assertEquals(3, ((Number) d.get("likeCount")).intValue());
        assertEquals(3, ((Number) d.get("commentCount")).intValue());
        assertEquals(1, ((Number) d.get("collectCount")).intValue());
        // previous 命中 a1 -> total=1 read=2 like=1 comment=3 collect=4
        assertEquals(1, ((Number) d.get("totalTrend")).intValue());
        assertEquals(13, ((Number) d.get("readTrend")).intValue());
        assertEquals(2, ((Number) d.get("likeTrend")).intValue());
        assertEquals(0, ((Number) d.get("commentTrend")).intValue());
        assertEquals(-3, ((Number) d.get("collectTrend")).intValue());
    }

    @Test
    @DisplayName("getArticleStatistics - 空列表零值")
    void testArticleStatisticsEmpty() {
        when(apArticleMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        ResponseResult r = service.getArticleStatistics(1L, "2026-08-19", "2026-08-20");
        Map<String, Object> d = dataOf(r);
        assertEquals(0, ((Number) d.get("totalTrend")).intValue());
        assertEquals(0, ((Number) d.get("readTrend")).intValue());
    }

    @Test
    @DisplayName("getArticleTrend - 按天输出趋势点")
    void testArticleTrend() {
        ApArticle a = article(1L, "2026-08-20 09:00:00", 7, 2, 1, 3, "趋势文");
        when(apArticleMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(a));

        ResponseResult r = service.getArticleTrend(1L, "2026-08-19", "2026-08-21", 3);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        List<Map<String, Object>> trend = listOf(r);
        assertEquals(3, trend.size());
        Map<String, Object> day = trend.get(1); // 2026-08-20
        assertEquals("2026-08-20", day.get("date"));
        assertEquals(7, ((Number) day.get("readCount")).intValue());
        assertEquals(2, ((Number) day.get("likeCount")).intValue());
        // 无数据的天为 0
        assertEquals(0, ((Number) trend.get(0).get("readCount")).intValue());
    }

    @Test
    @DisplayName("getArticleDetail - 日期过滤与分页，null 指标按 0")
    void testArticleDetail() {
        ApArticle a = article(9L, "2026-08-20 12:00:00", null, null, null, null, "详情文");
        Page<ApArticle> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(a));
        page.setTotal(1);
        when(apArticleMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        ResponseResult r = service.getArticleDetail(1L, "2026-08-20", "2026-08-21", 1, 10);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        Map<String, Object> d = dataOf(r);
        List<Map<String, Object>> list = (List<Map<String, Object>>) d.get("list");
        assertEquals(1, ((Number) d.get("total")).intValue());
        assertEquals("详情文", list.get(0).get("title"));
        assertEquals(0, ((Number) list.get(0).get("readCount")).intValue());
    }

    // ==================== Column ====================

    @Test
    @DisplayName("getColumnStatistics - 专栏数量与订阅增减")
    void testColumnStatistics() {
        ApColumn c1 = column(1L, "2026-08-18 10:00:00", 5, "旧专栏");
        ApColumn c2 = column(2L, "2026-08-20 10:00:00", 9, "新专栏");
        when(apColumnMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(c1, c2));

        ResponseResult r = service.getColumnStatistics(1L, "2026-08-19", "2026-08-20");

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        Map<String, Object> d = dataOf(r);
        // current 命中 c2 -> total=1 subscribe=9；previous 命中 c1 -> total=1 subscribe=5
        assertEquals(1, ((Number) d.get("totalCount")).intValue());
        assertEquals(0, ((Number) d.get("totalTrend")).intValue());
        assertEquals(9, ((Number) d.get("subscribeCount")).intValue());
        assertEquals(4, ((Number) d.get("subscribeTrend")).intValue());
    }

    @Test
    @DisplayName("getColumnTrend - 按天订阅趋势")
    void testColumnTrend() {
        ApColumn c = column(1L, "2026-08-20 09:00:00", 12, "趋势专栏");
        when(apColumnMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(c));

        ResponseResult r = service.getColumnTrend(1L, "2026-08-19", "2026-08-20", 2);

        List<Map<String, Object>> trend = listOf(r);
        assertEquals(2, trend.size());
        assertEquals(12, ((Number) trend.get(1).get("subscribeCount")).intValue());
        assertEquals(0, ((Number) trend.get(0).get("subscribeCount")).intValue());
    }

    @Test
    @DisplayName("getColumnDetail - 分页与订阅数")
    void testColumnDetail() {
        ApColumn c = column(5L, "2026-08-20 12:00:00", 3, "详情专栏");
        Page<ApColumn> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(c));
        page.setTotal(1);
        when(apColumnMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        ResponseResult r = service.getColumnDetail(1L, null, "2026-08-21", 1, 10);

        Map<String, Object> d = dataOf(r);
        List<Map<String, Object>> list = (List<Map<String, Object>>) d.get("list");
        assertEquals(1, ((Number) d.get("total")).intValue());
        assertEquals("详情专栏", list.get(0).get("title"));
        assertEquals(3, ((Number) list.get(0).get("subscribeCount")).intValue());
    }

    // ==================== Pin ====================

    @Test
    @DisplayName("getPinStatistics - 沸点数量/点赞/评论增减")
    void testPinStatistics() {
        ApPins p1 = pins(1L, "2026-08-18 10:00:00", 4, 2, "旧沸点");
        ApPins p2 = pins(2L, "2026-08-20 10:00:00", 6, 3, "新沸点");
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(p1, p2));

        ResponseResult r = service.getPinStatistics(1L, "2026-08-19", "2026-08-20");

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        Map<String, Object> d = dataOf(r);
        // current 命中 p2 -> total=1 like=6 comment=3；previous p1 -> like=4 comment=2
        assertEquals(1, ((Number) d.get("totalCount")).intValue());
        assertEquals(0, ((Number) d.get("totalTrend")).intValue());
        assertEquals(6, ((Number) d.get("likeCount")).intValue());
        assertEquals(2, ((Number) d.get("likeTrend")).intValue());
        assertEquals(3, ((Number) d.get("commentCount")).intValue());
        assertEquals(1, ((Number) d.get("commentTrend")).intValue());
    }

    @Test
    @DisplayName("getPinTrend - 按天点赞/评论趋势")
    void testPinTrend() {
        ApPins p = pins(1L, "2026-08-20 09:00:00", 8, 4, "沸点");
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(p));

        ResponseResult r = service.getPinTrend(1L, "2026-08-20", "2026-08-21", 2);

        List<Map<String, Object>> trend = listOf(r);
        assertEquals(2, trend.size());
        assertEquals(8, ((Number) trend.get(0).get("likeCount")).intValue());
        assertEquals(0, ((Number) trend.get(1).get("commentCount")).intValue());
    }

    @Test
    @DisplayName("getPinDetail - 分页与内容")
    void testPinDetail() {
        ApPins p = pins(8L, "2026-08-20 12:00:00", null, null, "沸点详情");
        Page<ApPins> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(p));
        page.setTotal(1);
        when(apPinsMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        ResponseResult r = service.getPinDetail(1L, "2026-08-20", null, 1, 10);

        Map<String, Object> d = dataOf(r);
        List<Map<String, Object>> list = (List<Map<String, Object>>) d.get("list");
        assertEquals(1, ((Number) d.get("total")).intValue());
        assertEquals("沸点详情", list.get(0).get("content"));
        assertEquals(0, ((Number) list.get(0).get("likeCount")).intValue());
    }
}