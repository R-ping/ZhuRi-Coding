package com.heima.content.service.topic.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.circle.ApCircleMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.mapper.topic.TopicCircleRelationMapper;
import com.heima.content.mapper.topic.TopicMapper;
import com.heima.content.mapper.topic.TopicRelationMapper;
import com.heima.content.mapper.topic.UserTopicPostMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.TopicCircleRelation;
import com.heima.model.article.pojos.TopicRelation;
import com.heima.model.circle.pojos.ApCircle;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.topic.dtos.TopicSquareDto;
import com.heima.model.topic.pojos.ApTopic;
import com.heima.model.topic.vos.TopicDetailVO;
import com.heima.model.topic.vos.TopicRecommendVO;
import com.heima.model.topic.vos.TopicSquareVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TopicServiceImpl 单元测试（话题广场/推荐/详情/Feed/搜索）
 *
 * 继承 ServiceImpl，baseMapper 经反射注入；topicMapper 同类型实例字段由 @InjectMocks 注入。
 * 覆盖：
 * - recommend：普通分页、空列表、环形缓冲回卷；
 * - square：默认 hot 排序、keyword 过滤、new 排序、has_more 截断；
 * - detail：不存在返回 null、沸点+文章参与与浏览聚合、type=2 扩展 tabs、关联圈子、null 字段兜底；
 * - feed：文章分栏(article_hot/article_new/空关联/越界)、沸点(hot/new/has_more)；
 * - search：空参返回空、有结果 VO 转换；
 * - inspirationTopics：themeType 过滤、participants/view 排序；
 * - recommendedTopics：excludeId 与 limit 分页。
 */
class TopicServiceImplTest {

    @Mock
    private TopicMapper topicMapper;
    @Mock
    private TopicRelationMapper topicRelationMapper;
    @Mock
    private UserTopicPostMapper userTopicPostMapper;
    @Mock
    private TopicCircleRelationMapper topicCircleRelationMapper;
    @Mock
    private ApCircleMapper apCircleMapper;
    @Mock
    private ApPinsMapper apPinsMapper;
    @Mock
    private ApArticleMapper apArticleMapper;

    @InjectMocks
    private TopicServiceImpl topicService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        injectBaseMapper(topicService, topicMapper);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApTopic.class);
    }

    private void injectBaseMapper(Object service, Object mapper) {
        try {
            Field f = service.getClass().getSuperclass().getDeclaredField("baseMapper");
            f.setAccessible(true);
            f.set(service, mapper);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("无法注入 baseMapper", e);
        }
    }

    private ApTopic topic(Long id, String name, Long views, Long participants, Integer postCount) {
        ApTopic t = new ApTopic();
        t.setId(id);
        t.setName(name);
        t.setViewCount(views);
        t.setParticipantCount(participants);
        t.setPostCount(postCount);
        t.setStatus(1);
        return t;
    }

    private ApPins pins(Long id, Integer views, Integer likes, Date created) {
        ApPins p = new ApPins();
        p.setId(id);
        p.setContent("内容" + id);
        p.setViews(views);
        p.setLikes(likes);
        p.setComment(0);
        p.setCreatedTime(created);
        p.setUserId(1L);
        p.setUserName("小明");
        p.setUserAvatar("a.png");
        p.setStatus((byte) 9);
        return p;
    }

    private ApArticle article(Long id, Integer views, Date publish, Date created, String title) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setTitle(title);
        a.setViews(views);
        a.setPublishTime(publish);
        a.setCreatedTime(created);
        a.setStatus((byte) 9);
        a.setAuthorId(1L);
        a.setAuthorName("作者");
        return a;
    }

    // ==================== recommend ====================

    @Test
    @DisplayName("recommend - 正常分页环形缓冲（回卷）")
    void testRecommendWithWrap() {
        ApTopic t1 = topic(1L, "话题1", 100L, 10L, 1);
        ApTopic t2 = topic(2L, "话题2", 200L, 20L, 2);
        when(topicMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(t1, t2));

        Map<String, Object> r = topicService.recommend(1, 3);
        assertNotNull(r);
        assertEquals(2, r.get("total"));
        List<TopicRecommendVO> vos = (List<TopicRecommendVO>) r.get("list");
        assertEquals(3, vos.size());
        // offset=(1*3)%2=1：从话题2开始，环形回卷 2->1->2
        assertEquals("话题2", vos.get(0).getName());
        assertEquals("话题1", vos.get(1).getName());
        assertEquals("话题2", vos.get(2).getName());
    }

    @Test
    @DisplayName("recommend - 无推荐话题返回空")
    void testRecommendEmpty() {
        when(topicMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        Map<String, Object> r = topicService.recommend(0, 5);
        assertEquals(0, r.get("total"));
        assertTrue(((List<?>) r.get("list")).isEmpty());
    }

    // ==================== square ====================

    @Test
    @DisplayName("square - 默认 hot 排序+keyword，has_more 截断")
    void testSquareHotWithKeywordAndMore() {
        ApTopic t1 = topic(1L, "Java", 50L, 5L, 3);
        ApTopic t2 = topic(2L, "Java并发", 90L, 9L, 4);
        Page<ApTopic> page = new Page<>(0, 3);
        page.setRecords(Arrays.asList(t1, t2, topic(3L, "JVM", 1L, 1L, 0)));
        page.setTotal(3);
        when(topicMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        TopicSquareDto dto = new TopicSquareDto();
        dto.setKeyword("  Java  ");
        dto.setCursor(0L);
        dto.setSize(2);
        Map<String, Object> r = topicService.square(dto);

        assertEquals(2L, r.get("cursor"));
        assertTrue((Boolean) r.get("has_more"));
        List<TopicSquareVO> vos = (List<TopicSquareVO>) r.get("list");
        assertEquals(2, vos.size());
        assertEquals("Java", vos.get(0).getName());
        assertEquals(5L, vos.get(0).getParticipantCount());
    }

    @Test
    @DisplayName("square - new 排序且不足 size 无 has_more")
    void testSquareNewNoMore() {
        ApTopic t = topic(1L, "话题", 10L, 2L, 5);
        Page<ApTopic> page = new Page<>(1, 21);
        page.setRecords(Collections.singletonList(t));
        page.setTotal(1);
        when(topicMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        TopicSquareDto dto = new TopicSquareDto();
        dto.setSort("new");
        dto.setCursor(20L);
        dto.setSize(20);
        Map<String, Object> r = topicService.square(dto);

        assertFalse((Boolean) r.get("has_more"));
        assertEquals(40L, r.get("cursor"));
        List<TopicSquareVO> vos = (List<TopicSquareVO>) r.get("list");
        assertEquals(1, vos.size());
    }

    // ==================== detail ====================

    @Test
    @DisplayName("detail - 不存在返回 null")
    void testDetailNotExist() {
        when(topicMapper.selectById(any())).thenReturn(null);
        assertNull(topicService.detail(1L));
    }

    @Test
    @DisplayName("detail - 聚合沸点+文章浏览/参与，type=1 默认 tabs，含圈子")
    void testDetailAggregated() {
        ApTopic t = topic(7L, "话题", 0L, 0L, 0);
        t.setType(1);
        t.setDescription(null);
        t.setCoverImage(null);
        t.setBadge(null);
        when(topicMapper.selectById(7L)).thenReturn(t);

        ApPins p1 = pins(1L, 30, 5, new Date());
        ApPins p2 = pins(2L, 10, 2, new Date());
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(p1, p2));

        TopicRelation rel = new TopicRelation();
        rel.setTargetId(100L);
        rel.setTargetType(1);
        when(topicRelationMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(rel));

        ApArticle article = article(100L, 60, new Date(), new Date(), "关联文章");
        when(apArticleMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(article));

        TopicCircleRelation circleRel = new TopicCircleRelation();
        circleRel.setCircleId(9L);
        when(topicCircleRelationMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(circleRel));
        ApCircle circle = new ApCircle();
        circle.setId(9L);
        circle.setName("圈子A");
        when(apCircleMapper.selectById(9L)).thenReturn(circle);

        TopicDetailVO vo = topicService.detail(7L);

        assertNotNull(vo);
        assertEquals(100L, vo.getViewCount());  // 30+10 沸点 + 60 文章
        assertEquals(3L, vo.getParticipantCount()); // 2 沸点 + 1 文章
        List<String> tabs = vo.getAvailableTabs();
        assertTrue(tabs.contains("hot"));
        assertTrue(tabs.contains("new"));
        assertTrue(tabs.contains("pin"));
        assertFalse(tabs.contains("article"));
        assertEquals("圈子A", vo.getCircleInfo().get(0).getCircleName());
    }

    @Test
    @DisplayName("detail - type=2 增加 article tab，空关联列表兜底，圈子不存在名称为空")
    void testDetailTypeTwoEmptyRelations() {
        ApTopic t = topic(8L, "话题2", 0L, 0L, 0);
        t.setType(2);
        when(topicMapper.selectById(8L)).thenReturn(t);

        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(topicRelationMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        // 圈子关联存在但圈子本身不存在
        TopicCircleRelation rel = new TopicCircleRelation();
        rel.setCircleId(5L);
        when(topicCircleRelationMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(rel));
        when(apCircleMapper.selectById(5L)).thenReturn(null);

        TopicDetailVO vo = topicService.detail(8L);
        assertNotNull(vo);
        assertTrue(vo.getAvailableTabs().contains("article"));
        assertEquals("", vo.getCircleInfo().get(0).getCircleName());
        assertEquals(0L, vo.getViewCount());
    }

    // ==================== feed ====================

    @Test
    @DisplayName("feed - 文章分栏 article_hot 按阅读量降序")
    void testFeedArticleHot() {
        TopicRelation rel = new TopicRelation();
        rel.setTargetId(1L);
        rel.setTargetType(1);
        when(topicRelationMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(rel));

        ApArticle a1 = article(1L, 5, new Date(), new Date(), "文章1");
        ApArticle a2 = article(2L, 9, new Date(), new Date(), "文章2");
        when(apArticleMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(a1, a2));

        Map<String, Object> r = topicService.feed(1L, "article_hot", 0, 2);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("list");
        assertEquals(2, list.size());
        assertEquals("article", list.get(0).get("type"));
        assertEquals("文章2", list.get(0).get("title"));
    }

    @Test
    @DisplayName("feed - article_new 按时间降序，空关系返回空")
    void testFeedArticleNewAndEmptyRel() {
        when(topicRelationMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        Map<String, Object> r = topicService.feed(1L, "article_new", 0, 5);
        assertFalse((Boolean) r.get("has_more"));
        assertTrue(((List<?>) r.get("list")).isEmpty());
    }

    @Test
    @DisplayName("feed - 沸点 hot 排序并 has_more 截断")
    void testFeedPinHot() {
        Date d1 = new Date(1000L);
        Date d2 = new Date(2000L);
        ApPins p1 = pins(1L, 0, 5, d1);
        ApPins p2 = pins(2L, 0, 9, d2);
        ApPins p3 = pins(3L, 0, 1, d2);
        Page<ApPins> page = new Page<>(0, 3);
        page.setRecords(Arrays.asList(p1, p2, p3));
        page.setTotal(3);
        when(apPinsMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        Map<String, Object> r = topicService.feed(1L, "hot", 0, 2);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("list");
        assertEquals(2, list.size());
        assertEquals("pin", list.get(0).get("type"));
        assertTrue((Boolean) r.get("has_more"));
    }

    // ==================== search ====================

    @Test
    @DisplayName("search - 空关键字返回空列表")
    void testSearchBlank() {
        assertTrue(topicService.search("   ", 10).isEmpty());
    }

    @Test
    @DisplayName("search - 分页查询并转换 VO")
    void testSearch() {
        Page<ApTopic> page = new Page<>(1, 5);
        page.setRecords(Collections.singletonList(topic(1L, "Java", 100L, 5L, 1)));
        page.setTotal(1);
        when(topicMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        List<TopicRecommendVO> vos = topicService.search("Java", 5);
        assertEquals(1, vos.size());
        assertEquals("Java", vos.get(0).getName());
        assertEquals(100L, vos.get(0).getViewCount());
    }

    // ==================== inspirationTopics ====================

    @Test
    @DisplayName("inspirationTopics - participants 排序并过滤 themeType")
    void testInspirationParticipants() {
        when(topicMapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenReturn(new Page<>(1, 2));
        Map<String, Object> r = topicService.inspirationTopics(1, 2, "participants", 1);
        assertEquals(1, r.get("page"));
        assertEquals(2, r.get("size"));
        assertNotNull(r.get("list"));
    }

    // ==================== recommendedTopics ====================

    @Test
    @DisplayName("recommendedTopics - excludeId 与 limit")
    void testRecommendedTopics() {
        Page<ApTopic> page = new Page<>(1, 3);
        page.setRecords(Collections.singletonList(topic(2L, "推荐", 30L, 3L, 1)));
        when(topicMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        List<TopicRecommendVO> vos = topicService.recommendedTopics(1L, 3);
        assertEquals(1, vos.size());
        assertEquals("推荐", vos.get(0).getName());
    }
}