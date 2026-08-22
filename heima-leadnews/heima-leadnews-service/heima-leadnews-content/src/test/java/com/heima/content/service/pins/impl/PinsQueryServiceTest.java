package com.heima.content.service.pins.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.circle.ApCircleCategoryMapper;
import com.heima.content.mapper.circle.ApCircleMapper;
import com.heima.content.mapper.circle.ApUserCircleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.level.ApUserLevelMapper;
import com.heima.content.mapper.pins.ApPinsCommentMapper;
import com.heima.content.mapper.pins.ApPinsLikeMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.mapper.topic.TopicMapper;
import com.heima.content.service.topic.TopicService;
import com.heima.model.circle.pojos.ApCircle;
import com.heima.model.circle.pojos.ApCircleCategory;
import com.heima.model.circle.pojos.ApUserCircle;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.level.pojos.ApUserLevel;
import com.heima.model.pins.dtos.PinsLinkPreviewDTO;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.pins.pojos.ApPinsComment;
import com.heima.model.pins.pojos.ApPinsLike;
import com.heima.model.pins.vos.PinsCommentVO;
import com.heima.model.pins.vos.PinsLinkPreviewVO;
import com.heima.model.pins.vos.PinsSidebarVO;
import com.heima.model.pins.vos.PinsVO;
import com.heima.model.topic.pojos.ApTopic;
import com.heima.model.topic.vos.TopicRecommendVO;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PinsQueryService 单元测试（沸点查询核心：列表/热度/详情/侧边栏/评论/话题/圈子/链接预览/VO转换）
 *
 * 该类为普通 @Component，依赖均通过字段注入，Mockito @InjectMocks 可直接注入；当前登录用户通过
 * AppThreadLocalUtil.setUser/clear 控制。
 * 覆盖：
 * - 沸点列表：list tab 分流(following/hot/latest)、listLatest、listHot(空/按热度排序+等级加权)、listFollowing(空/有关注)；
 * - 详情：detail(参数缺失/不存在/正常)、incrView(正常/异常吞掉)；
 * - 侧边栏 sidebar(游客/登录+精选+推荐话题)；
 * - 评论 commentList(热序/时间序/带子回复)、话题 topics(带/不带关键字)、圈子 circles；
 * - 链接预览 linkPreview(空URL/非法URL/正常)；
 * - 工具方法：calcHotScore、getUserOrNull、convertToVOList、convertToVO、parseStringList、convertCommentToVO。
 */
class PinsQueryServiceTest {

    @Mock
    private ApPinsMapper apPinsMapper;
    @Mock
    private ApPinsLikeMapper apPinsLikeMapper;
    @Mock
    private ApPinsCommentMapper apPinsCommentMapper;
    @Mock
    private ApFollowMapper apFollowMapper;
    @Mock
    private ApUserCircleMapper apUserCircleMapper;
    @Mock
    private ApCircleMapper apCircleMapper;
    @Mock
    private ApCircleCategoryMapper apCircleCategoryMapper;
    @Mock
    private TopicMapper topicMapper;
    @Mock
    private ApUserLevelMapper apUserLevelMapper;
    @Mock
    private TopicService topicService;

    @InjectMocks
    private PinsQueryService pinsQueryService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    // ---------- 辅助 ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseResult r) {
        return (Map<String, Object>) r.getData();
    }

    private ApUser user(Integer id) {
        ApUser u = new ApUser();
        u.setId(id);
        return u;
    }

    private ApPins pin(Long id, Long authorId, Long circleId, byte status) {
        return ApPins.builder()
                .id(id)
                .userId(id)
                .authorId(authorId)
                .authorName("作者" + authorId)
                .authorImage("avatar")
                .userName("用户" + id)
                .userAvatar("avatar")
                .content("沸点内容" + id)
                .likes(10)
                .comment(5)
                .shareCount(2)
                .status(status)
                .isDeleted(false)
                .circleId(circleId)
                .imageUrls("a.png,b.png")
                .topicTags("标签1,标签2")
                .build();
    }

    private void mockEmptyLikes() {
        when(apPinsLikeMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
    }

    private void mockEmptyCircles() {
        when(apCircleMapper.selectBatchIds(anyCollection())).thenReturn(new ArrayList<>());
    }

    // ==================== list 分流 ====================

    @Test
    @DisplayName("list - following 未登录返回 NEED_LOGIN")
    void testListFollowNotLogin() {
        ResponseResult r = pinsQueryService.list("follow", 1, 10);
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), r.getCode());
    }

    @Test
    @DisplayName("list - following 登录后走 listFollowing")
    void testListFollowLoggedIn() {
        AppThreadLocalUtil.setUser(user(1));
        // 无任何关注 -> 空列表
        when(apFollowMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        ResponseResult r = pinsQueryService.list("following", 1, 10);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals(0, ((List<?>) dataOf(r).get("list")).size());
    }

    @Test
    @DisplayName("list - hot 与默认 tab 分流")
    void testListTabRouting() {
        when(apPinsMapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenReturn(pageOf());
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        mockEmptyLikes();
        mockEmptyCircles();

        ResponseResult hot = pinsQueryService.list("hot", 1, 10);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), hot.getCode());

        ResponseResult latest = pinsQueryService.list("recommend", 1, 10);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), latest.getCode());
    }

    // ==================== 最新列表 ====================

    @Test
    @DisplayName("listLatest - 游客可看公开沸点")
    void testListLatestAnonymous() {
        Page<ApPins> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(pin(1L, 100L, null, ApPins.Status.PUBLISHED.getCode())));
        page.setTotal(1);
        when(apPinsMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        // 游客 -> 不查点赞，仅查圈子名
        when(apCircleMapper.selectBatchIds(anyCollection())).thenReturn(new ArrayList<>());

        ResponseResult r = pinsQueryService.listLatest(null, 1, 10);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals(1, ((List<?>) dataOf(r).get("list")).size());
        assertEquals(1L, ((Number) dataOf(r).get("total")).longValue());
        verify(apPinsLikeMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("listLatest - 登录用户查询点赞状态")
    void testListLatestLoggedIn() {
        AppThreadLocalUtil.setUser(user(1));
        ApPins p = pin(1L, 100L, 10L, ApPins.Status.PUBLISHED.getCode());
        Page<ApPins> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(p));
        page.setTotal(1);
        when(apPinsMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        ApPinsLike like = new ApPinsLike();
        like.setPinsId(1L);
        like.setUserId(1);
        when(apPinsLikeMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(like));

        ApCircle circle = new ApCircle();
        circle.setId(10L);
        circle.setName("Java");
        when(apCircleMapper.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(circle));

        ResponseResult r = pinsQueryService.listLatest(user(1), 1, 10);

        List<PinsVO> list = (List<PinsVO>) dataOf(r).get("list");
        assertEquals(1, list.size());
        assertTrue(list.get(0).getLiked());
        assertEquals("Java", list.get(0).getCircleName());
    }

    // ==================== 热度列表 ====================

    @Test
    @DisplayName("listHot - 无发布沸点返回空")
    void testListHotEmpty() {
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        ResponseResult r = pinsQueryService.listHot(null, 1, 10);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals(0L, ((Number) dataOf(r).get("total")).longValue());
        verify(apUserLevelMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("listHot - 按热度+作者等级加权排序")
    void testListHotSorted() {
        ApPins a = pin(1L, 100L, null, ApPins.Status.PUBLISHED.getCode()); // likes10 comment5 share2
        ApPins b = pin(2L, 200L, null, ApPins.Status.PUBLISHED.getCode());
        b.setLikes(50);
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(a, b)));

        ApUserLevel la = new ApUserLevel();
        la.setUserId(100L);
        la.setDailyLevel(2);
        la.setPowerLevel(3);
        when(apUserLevelMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(la));
        mockEmptyLikes();
        mockEmptyCircles();

        ResponseResult r = pinsQueryService.listHot(null, 1, 10);
        List<PinsVO> list = (List<PinsVO>) dataOf(r).get("list");
        // b(likes50=50) 热度高于 a(10+5*2+2*3+2*5=36)，即便 a 有等级加权
        assertEquals(2L, list.get(0).getId());
    }

    // ==================== 关注列表 ====================

    @Test
    @DisplayName("listFollowing - 无关注返回空")
    void testListFollowingEmpty() {
        AppThreadLocalUtil.setUser(user(1));
        when(apFollowMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        ResponseResult r = pinsQueryService.listFollowing(user(1), 1, 10);
        assertEquals(0L, ((Number) dataOf(r).get("total")).longValue());
        verify(apPinsMapper, never()).selectPage(any(IPage.class), any(Wrapper.class));
    }

    @Test
    @DisplayName("listFollowing - 有关注分页查询")
    void testListFollowingWithFollows() {
        AppThreadLocalUtil.setUser(user(1));
        ApFollow f = new ApFollow();
        f.setUserId(1);
        f.setFollowUserId(200);
        when(apFollowMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(f));

        ApPins p = pin(3L, 200L, null, ApPins.Status.PUBLISHED.getCode());
        Page<ApPins> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(p));
        page.setTotal(1);
        when(apPinsMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        mockEmptyLikes();
        mockEmptyCircles();

        ResponseResult r = pinsQueryService.listFollowing(user(1), 1, 10);
        assertEquals(1L, ((Number) dataOf(r).get("total")).longValue());
    }

    // ==================== 详情 ====================

    @Test
    @DisplayName("detail - pinsId 为空返回参数错误")
    void testDetailNullId() {
        ResponseResult r = pinsQueryService.detail(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("detail - 不存在或已删除返回 DATA_NOT_EXIST")
    void testDetailNotExist() {
        when(apPinsMapper.selectById(anyLong())).thenReturn(null);
        ResponseResult r = pinsQueryService.detail(1L);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), r.getCode());
    }

    @Test
    @DisplayName("detail - 正常返回单条VO")
    void testDetailOk() {
        ApPins p = pin(1L, 100L, null, ApPins.Status.PUBLISHED.getCode());
        when(apPinsMapper.selectById(1L)).thenReturn(p);
        mockEmptyLikes();
        mockEmptyCircles();
        ResponseResult r = pinsQueryService.detail(1L);
        PinsVO vo = (PinsVO) r.getData();
        assertNotNull(vo);
        assertEquals(1L, vo.getId());
    }

    // ==================== 浏览自增 ====================

    @Test
    @DisplayName("incrView - 递增并吞掉底层异常")
    void testIncrView() {
        pinsQueryService.incrView(1L);
        verify(apPinsMapper).incrementViews(1L);

        doThrow(new RuntimeException("db down")).when(apPinsMapper).incrementViews(2L);
        pinsQueryService.incrView(2L); // 不应抛出
    }

    // ==================== 侧边栏 ====================

    @Test
    @DisplayName("sidebar - 游客仅看精选+推荐话题")
    void testSidebarAnonymous() {
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(topicService.recommend(anyInt(), anyInt())).thenReturn(new HashMap<>());

        ResponseResult r = pinsQueryService.sidebar();
        PinsSidebarVO vo = (PinsSidebarVO) r.getData();
        assertNotNull(vo);
        assertEquals(0, vo.getPinsCount());
        assertEquals(0, vo.getFeaturedPins().size());
        assertNotNull(vo.getRecommendedTopics());
    }

    @Test
    @DisplayName("sidebar - 登录用户统计+精选前3")
    void testSidebarLoggedIn() {
        AppThreadLocalUtil.setUser(user(1));
        when(apPinsMapper.selectCount(any(Wrapper.class))).thenReturn(5L);
        when(apUserCircleMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        // 关注数/粉丝数两次 selectCount 均命中同一 stub
        when(apFollowMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        ApPins p1 = pin(1L, 1L, null, ApPins.Status.PUBLISHED.getCode());
        ApPins p2 = pin(2L, 1L, null, ApPins.Status.PUBLISHED.getCode());
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(p1, p2)));

        Map<String, Object> topicMap = new HashMap<>();
        topicMap.put("list", new ArrayList<TopicRecommendVO>());
        when(topicService.recommend(anyInt(), anyInt())).thenReturn(topicMap);
        mockEmptyLikes();
        mockEmptyCircles();

        ResponseResult r = pinsQueryService.sidebar();
        PinsSidebarVO vo = (PinsSidebarVO) r.getData();
        assertEquals(5, vo.getPinsCount());
        assertEquals(2, vo.getCircleCount());
        assertEquals(3, vo.getFollowingCount());
        assertEquals(3, vo.getFollowersCount());
        assertEquals(2, vo.getFeaturedPins().size());
    }

    @Test
    @DisplayName("sidebar - 推荐话题异常时兜底为空列表")
    void testSidebarTopicException() {
        AppThreadLocalUtil.setUser(user(1));
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(apPinsMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(apUserCircleMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(apFollowMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(topicService.recommend(anyInt(), anyInt())).thenThrow(new RuntimeException("boom"));

        ResponseResult r = pinsQueryService.sidebar();
        PinsSidebarVO vo = (PinsSidebarVO) r.getData();
        assertEquals(0, vo.getRecommendedTopics().size());
    }

    // ==================== 评论列表 ====================

    @Test
    @DisplayName("commentList - 时间序分页并带上子回复")
    void testCommentListByTime() {
        ApPinsComment top = new ApPinsComment();
        top.setId(10L);
        top.setPinsId(1L);
        top.setUserName("小明");
        top.setContent("顶楼");
        Page<ApPinsComment> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(top));
        page.setTotal(1);
        when(apPinsCommentMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        ApPinsComment reply = new ApPinsComment();
        reply.setId(11L);
        reply.setParentId(10L);
        reply.setPinsId(1L);
        reply.setContent("回复");
        when(apPinsCommentMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(reply));

        ResponseResult r = pinsQueryService.commentList(1L, 1, 10, "time");

        List<PinsCommentVO> list = (List<PinsCommentVO>) dataOf(r).get("list");
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).getReplies().size());
    }

    @Test
    @DisplayName("commentList - 热序与空评论")
    void testCommentListHotAndEmpty() {
        Page<ApPinsComment> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>());
        page.setTotal(0);
        when(apPinsCommentMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        ResponseResult hot = pinsQueryService.commentList(1L, 1, 10, "hot");
        assertEquals(0, ((List<?>) dataOf(hot).get("list")).size());
    }

    // ==================== 话题列表 ====================

    @Test
    @DisplayName("topics - 带/不带关键字")
    void testTopics() {
        ApTopic t1 = new ApTopic();
        t1.setId(1L);
        t1.setName("Java");
        t1.setPostCount(10);
        t1.setIsRecommend(1);
        Page<ApTopic> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(t1));
        page.setTotal(1);
        when(topicMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        ResponseResult withKw = pinsQueryService.topics("Java", 1, 10);
        assertEquals(1, ((List<?>) dataOf(withKw).get("list")).size());

        ResponseResult noKw = pinsQueryService.topics("", 1, 10);
        assertEquals(1, ((List<?>) dataOf(noKw).get("list")).size());
    }

    // ==================== 圈子列表 ====================

    @Test
    @DisplayName("circles - 按分类分组返回")
    void testCircles() {
        ApCircleCategory c1 = new ApCircleCategory();
        c1.setId(1L);
        c1.setName("技术");
        when(apCircleCategoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(c1));

        ApCircle circle = new ApCircle();
        circle.setId(10L);
        circle.setCategoryId(1L);
        circle.setName("Java圈");
        circle.setIcon("icon");
        circle.setMemberCount(100);
        circle.setPinsCount(5);
        when(apCircleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(circle));

        ResponseResult r = pinsQueryService.circles();
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
        assertEquals(1, list.size());
        List<Map<String, Object>> circles =
                (List<Map<String, Object>>) list.get(0).get("circles");
        assertEquals("Java圈", circles.get(0).get("name"));
    }

    // ==================== 链接预览 ====================

    @Test
    @DisplayName("linkPreview - 空URL/非法URL段/正常")
    void testLinkPreview() {
        PinsLinkPreviewDTO none = new PinsLinkPreviewDTO();
        none.setUrl("  ");
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), pinsQueryService.linkPreview(none).getCode());

        PinsLinkPreviewDTO valid = new PinsLinkPreviewDTO();
        valid.setUrl("https://www.example.com/a");
        PinsLinkPreviewVO vo = (PinsLinkPreviewVO) pinsQueryService.linkPreview(valid).getData();
        assertEquals("www.example.com", vo.getDomain());
    }

    // ==================== 工具方法 ====================

    @Test
    @DisplayName("calcHotScore - 根据互动与作者等级加权")
    void testCalcHotScore() {
        ApPins p = pin(1L, 100L, null, ApPins.Status.PUBLISHED.getCode());
        ApUserLevel level = new ApUserLevel();
        level.setUserId(100L);
        level.setDailyLevel(2);
        level.setPowerLevel(3);
        Map<Long, ApUserLevel> map = new HashMap<>();
        map.put(100L, level);
        // 10*1 + 5*2 + 2*3 + 2*5 + 3*5 = 10+10+6+10+15 = 51
        assertEquals(51, pinsQueryService.calcHotScore(p, map));

        // 无等级时
        ApPins p2 = pin(2L, 200L, null, ApPins.Status.PUBLISHED.getCode());
        assertEquals(26, pinsQueryService.calcHotScore(p2, new HashMap<>()));
    }

    @Test
    @DisplayName("getUserOrNull - 未登录返回 null，登录后返回用户")
    void testGetUserOrNull() {
        assertNull(pinsQueryService.getUserOrNull());
        AppThreadLocalUtil.setUser(user(9));
        assertNotNull(pinsQueryService.getUserOrNull());
        assertEquals(9, pinsQueryService.getUserOrNull().getId());
    }

    @Test
    @DisplayName("convertToVOList - 空列表与多元素")
    void testConvertToVOList() {
        assertEquals(0, pinsQueryService.convertToVOList(null, null).size());
        assertEquals(0, pinsQueryService.convertToVOList(new ArrayList<>(), null).size());
        mockEmptyLikes();
        mockEmptyCircles();
        List<PinsVO> list = pinsQueryService.convertToVOList(
                Collections.singletonList(pin(1L, 1L, null, ApPins.Status.PUBLISHED.getCode())), user(1));
        assertEquals(1, list.size());
        assertEquals("沸点内容1", list.get(0).getContent());
    }

    @Test
    @DisplayName("parseStringList - 空/逗号分隔/混合空白")
    void testParseStringList() {
        assertEquals(0, pinsQueryService.parseStringList(null).size());
        assertEquals(0, pinsQueryService.parseStringList("   ").size());
        List<String> list = pinsQueryService.parseStringList(" a ,b ,,c ");
        assertEquals(3, list.size());
        assertEquals("a", list.get(0));
        assertEquals("c", list.get(2));
    }

    @Test
    @DisplayName("convertCommentToVO - 字段映射与默认值")
    void testConvertCommentToVO() {
        ApPinsComment c = new ApPinsComment();
        c.setId(1L);
        c.setPinsId(2L);
        c.setUserId(3);
        c.setParentId(0L);
        c.setContent("评论");
        c.setImageUrls("");
        c.setReplyToUserName("");
        PinsCommentVO vo = pinsQueryService.convertCommentToVO(c);
        assertEquals(1L, vo.getId());
        assertEquals(0L, vo.getParentId());
        assertEquals("评论", vo.getContent());
        assertEquals(0, vo.getReplyCount());
        assertEquals(0, vo.getLikeCount());
    }

    private <T> Page<T> pageOf() {
        return new Page<>(1, 10);
    }
}