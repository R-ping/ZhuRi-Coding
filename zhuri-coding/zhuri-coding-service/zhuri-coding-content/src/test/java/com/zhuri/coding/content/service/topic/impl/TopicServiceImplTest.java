package com.heima.content.service.topic.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * TopicServiceImpl 单元测试（话题：推荐/广场/详情/信息流/搜索/灵感/关联推荐）
 *
 * 该类继承 ServiceImpl<TopicMapper, ApTopic>：
 * - baseMapper（父类私有字段）经反射注入 topicMapper Mock，供 list()/getById() 使用；
 * - tableMapper 等 @Autowired 字段由 @InjectMocks 注入；
 * - 需 TableInfoHelper.initTableInfo 预热 lambda 缓存，避免实体元数据解析异常。
 * 覆盖：
 * - recommend：环形缓冲分页 / 空话题；
 * - square：关键字+hot排序 / cursor 分页 hasMore / 默认值；
 * - detail：话题不存在返回 null / 完整组装(沸点+文章统计+圈子) / type=2 含文章Tab / null兜底；
 * - feed：沸点信息流(hot/new) / 走 articleFeed(空关联/无文章/排序/分页越界)；
 * - search：空关键字 / 正常模糊查询；
 * - inspirationTopics：themeType+sort 排序 / 默认排序；
 * - recommendedTopics：排除ID / 不排除。
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

    private long topicId = 100L;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        // ServiceImpl 私有 baseMapper 由反射注入，跨 MP 版本稳定（3.5.12 起字段上移至父类 CrudRepository）
        ReflectionTestUtils.setField(topicService, "baseMapper", topicMapper);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApTopic.class);
    }

    // ---------- 辅助 ----------

    private void stubTopics(List<ApTopic> records, long total) {
        Page<ApTopic> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(total);
        when(topicMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
    }

    private ApTopic topic(long id, String name) {
        ApTopic t = new ApTopic();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private ApPins pin(long id, String content) {
        ApPins p = new ApPins();
        p.setId(id);
        p.setContent(content);
        p.setUserId(1L);
        p.setUserName("小明");
        p.setLikes(10);
        p.setComment(2);
        p.setCreatedTime(new Date());
        return p;
    }

    private ApArticle article(long id, String title) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setTitle(title);
        a.setAuthorId(1L);
        a.setViews(5);
        a.setPublishTime(new Date());
        a.setCreatedTime(new Date());
        return a;
    }

    private TopicRelation relation(Long targetId) {
        TopicRelation rel = new TopicRelation();
        rel.setTopicId(topicId);
        rel.setTargetType(1);
        rel.setTargetId(targetId);
        return rel;
    }

    // ==================== recommend ====================

    @Test
    @DisplayName("recommend - 环形缓冲返回分页话题")
    void testRecommend() {
        List<ApTopic> topics = Arrays.asList(topic(1L, "A"), topic(2L, "B"), topic(3L, "C"));
        when(topicMapper.selectList(any(Wrapper.class))).thenReturn(topics);
        Map<String, Object> r = topicService.recommend(0, 2);
        List<TopicRecommendVO> list = (List<TopicRecommendVO>) r.get("list");
        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals(3, r.get("total"));
        assertEquals(0, r.get("page"));
    }

    @Test
    @DisplayName("recommend - 无推荐话题返回空")
    void testRecommendEmpty() {
        when(topicMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        Map<String, Object> r = topicService.recommend(0, 10);
        assertEquals(0, r.get("total"));
    }

    // ==================== square ====================

    @Test
    @DisplayName("square - 关键字+hot排序+cursor分页")
    void testSquare() {
        Page<ApTopic> page = new Page<>(1, 21);
        page.setRecords(Arrays.asList(topic(1L, "Java"), topic(2L, "Spring")));
        when(topicMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        TopicSquareDto dto = new TopicSquareDto();
        dto.setKeyword("java");
        dto.setSort("hot");
        dto.setCursor(10L);
        dto.setSize(20);
        Map<String, Object> r = topicService.square(dto);
        assertEquals(2, ((List<?>) r.get("list")).size());
        assertEquals(30L, r.get("cursor")); // 10+20
        assertEquals(false, r.get("has_more"));
    }

    @Test
    @DisplayName("square - 超过一页则 has_more 且截断")
    void testSquareHasMore() {
        List<ApTopic> records = new ArrayList<>();
        for (int i = 1; i <= 21; i++) records.add(topic(i, "T" + i));
        Page<ApTopic> page = new Page<>(1, 21);
        page.setRecords(records);
        when(topicMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        TopicSquareDto dto = new TopicSquareDto();
        dto.setSize(20);
        Map<String, Object> r = topicService.square(dto);
        assertEquals(20, ((List<?>) r.get("list")).size());
        assertEquals(true, r.get("has_more"));
    }

    // ==================== detail ====================

    @Test
    @DisplayName("detail - 话题不存在返回 null")
    void testDetailNull() {
        when(topicMapper.selectById(anyLong())).thenReturn(null);
        assertNull(topicService.detail(topicId));
    }

    @Test
    @DisplayName("detail - 完整组装：沸点+文章统计+type2+圈子")
    void testDetailFull() {
        ApTopic t = topic(topicId, "话题");
        t.setType(2);
        when(topicMapper.selectById(anyLong())).thenReturn(t);

        when(apPinsMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(pin(1L, "p1"), pin(2L, "p2")));
        when(topicRelationMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(relation(11L), relation(12L)));
        when(apArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(article(11L, "a1"), article(12L, "a2")));
        when(topicCircleRelationMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(new TopicCircleRelation()));
        when(apCircleMapper.selectById(anyLong())).thenReturn(new ApCircle());

        TopicDetailVO vo = topicService.detail(topicId);
        assertNotNull(vo);
        // viewCount = pinViews(0) + articleViews(5+5)
        assertEquals(10L, vo.getViewCount());
        // participantCount = pins(2) + articles(2)
        assertEquals(4L, vo.getParticipantCount());
        assertEquals(Arrays.asList("hot", "new", "article", "pin"), vo.getAvailableTabs());
        assertEquals(1, vo.getCircleInfo().size());
    }

    @Test
    @DisplayName("detail - type!=2 时 Tab 不含 article；圈子为空")
    void testDetailElseType() {
        ApTopic t = topic(topicId, "话题");
        t.setType(1);
        when(topicMapper.selectById(anyLong())).thenReturn(t);
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(topicRelationMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(topicCircleRelationMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());

        TopicDetailVO vo = topicService.detail(topicId);
        assertEquals(Arrays.asList("hot", "new", "pin"), vo.getAvailableTabs());
        assertEquals(0, vo.getCircleInfo().size());
        assertEquals(0L, vo.getViewCount());
    }

    // ==================== feed / articleFeed ====================

    @Test
    @DisplayName("feed - 沸点信息流 hot 排序")
    void testFeedPins() {
        Page<ApPins> page = new Page<>(1, 11);
        page.setRecords(Arrays.asList(pin(1L, "p1"), pin(2L, "p2")));
        when(apPinsMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        Map<String, Object> r = topicService.feed(topicId, "hot", 0, 10);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("list");
        assertEquals(2, list.size());
        assertEquals("pin", list.get(0).get("type"));
        assertEquals(false, r.get("has_more"));
    }

    @Test
    @DisplayName("feed - 文章信息流空关联")
    void testFeedArticleNoRelation() {
        when(topicRelationMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        Map<String, Object> r = topicService.feed(topicId, "article_hot", 0, 10);
        assertEquals(0, ((List<?>) r.get("list")).size());
        assertEquals(false, r.get("has_more"));
    }

    @Test
    @DisplayName("feed - 文章信息流按发布排序并分页")
    void testFeedArticle() {
        when(topicRelationMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(relation(11L), relation(12L), relation(13L)));
        when(apArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(article(11L, "a1"), article(12L, "a2"), article(13L, "a3")));
        Map<String, Object> r = topicService.feed(topicId, "article_new", 0, 2);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("list");
        assertEquals(2, list.size());
        assertEquals("article", list.get(0).get("type"));
        assertEquals(true, r.get("has_more"));
    }

    @Test
    @DisplayName("feed - 沸点信息流按最新时间排序且超过一页截断")
    void testFeedPinsNew() {
        List<ApPins> pins = new ArrayList<>();
        for (int i = 1; i <= 12; i++) pins.add(pin(i, "p" + i)); // 12 > size10 → hasMore
        Page<ApPins> page = new Page<>(1, 11);
        page.setRecords(pins);
        when(apPinsMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        Map<String, Object> r = topicService.feed(topicId, "new", 0, 10);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("list");
        assertEquals(10, list.size()); // 截断到 10
        assertEquals(true, r.get("has_more"));
    }

    @Test
    @DisplayName("feed - 文章信息流 targetId 全为空返回空")
    void testFeedArticleEmptyIds() {
        // 有关联记录但 targetId 全部为 null，过滤后 articleIds 为空
        List<TopicRelation> relations = Arrays.asList(relation(null), relation(null));
        when(topicRelationMapper.selectList(any(Wrapper.class))).thenReturn(relations);
        Map<String, Object> r = topicService.feed(topicId, "article_hot", 0, 10);
        assertEquals(0, ((List<?>) r.get("list")).size());
        assertEquals(false, r.get("has_more"));
    }

    @Test
    @DisplayName("feed - 文章信息流游标越界返回空")
    void testFeedArticleOutOfRange() {
        when(topicRelationMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(relation(11L)));
        when(apArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(article(11L, "a1")));
        Map<String, Object> r = topicService.feed(topicId, "article", 20, 10);
        assertEquals(0, ((List<?>) r.get("list")).size());
    }

    // ==================== search ====================

    @Test
    @DisplayName("search - 空关键字返回空")
    void testSearchEmpty() {
        assertEquals(0, topicService.search("  ", 10).size());
    }

    @Test
    @DisplayName("search - 关键字模糊查询")
    void testSearch() {
        Page<ApTopic> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(topic(1L, "Java")));
        when(topicMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        List<TopicRecommendVO> list = topicService.search("java", 10);
        assertEquals(1, list.size());
        assertEquals("Java", list.get(0).getName());
    }

    // ==================== inspirationTopics ====================

    @Test
    @DisplayName("inspirationTopics - themeType+participants 排序")
    void testInspirationParticipants() {
        Page<ApTopic> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(topic(1L, "A"), topic(2L, "B")));
        page.setTotal(2);
        when(topicMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        Map<String, Object> r = topicService.inspirationTopics(1, 10, "participants", 1);
        assertEquals(2, ((List<?>) r.get("list")).size());
        assertEquals(2L, r.get("total"));
    }

    @Test
    @DisplayName("inspirationTopics - 默认按浏览量排序")
    void testInspirationDefault() {
        Page<ApTopic> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(topic(1L, "A")));
        page.setTotal(1);
        when(topicMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        Map<String, Object> r = topicService.inspirationTopics(1, 10, "views", null);
        assertEquals(1, ((List<?>) r.get("list")).size());
    }

    // ==================== recommendedTopics ====================

    @Test
    @DisplayName("recommendedTopics - 排除指定ID")
    void testRecommendedExclude() {
        Page<ApTopic> page = new Page<>(1, 3);
        page.setRecords(Arrays.asList(topic(2L, "B"), topic(3L, "C")));
        when(topicMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        List<TopicRecommendVO> list = topicService.recommendedTopics(1L, 3);
        assertEquals(2, list.size());
    }

    @Test
    @DisplayName("recommendedTopics - 不排除，返回预置")
    void testRecommendedNoExclude() {
        Page<ApTopic> page = new Page<>(1, 3);
        page.setRecords(Collections.singletonList(topic(1L, "A")));
        when(topicMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        List<TopicRecommendVO> list = topicService.recommendedTopics(null, 3);
        assertEquals(1, list.size());
    }
}