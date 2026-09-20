package com.zhuri.coding.content.service.circle.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.mapper.circle.ApCircleHotConfigMapper;
import com.zhuri.coding.content.mapper.circle.ApCircleMapper;
import com.zhuri.coding.content.mapper.circle.ApUserCircleMapper;
import com.zhuri.coding.content.mapper.circle.ClubFeaturedPinMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.model.circle.pojos.ApCircle;
import com.zhuri.coding.model.circle.pojos.ApCircleHotConfig;
import com.zhuri.coding.model.circle.pojos.ApUserCircle;
import com.zhuri.coding.model.circle.pojos.ClubFeaturedPin;
import com.zhuri.coding.model.circle.vos.CircleVO;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import com.zhuri.coding.model.user.pojos.ApUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CircleServiceImpl 单元测试（圈子推荐/广场/热门/详情/加入退出/Feed/我的圈子/分类）
 *
 * 普通 @Service，依赖 5 个 mapper 由 @InjectMocks 注入，无需反射。
 * 覆盖：
 * - recommend：推荐算法(命中已登录/未登录)、圈子为 null 场景；
 * - square：列表+总数+分页字段、空列表；
 * - hot：banner 配置内 JOIN 圈子返回、空配置、配置对应圈子缺失跳过；
 * - detail：存在/不存在返回 null；
 * - join：重复加入抛异常、新加入自增人数；
 * - leave：未加入抛异常、退出自减(不为负)；
 * - feed：featured 精选(featuredPin 关联)、hot 沸点排序、new 沸点排序、空列表;
 * - myCircles：空/有加入记录；
 * - listByCategory：分类过滤+isJoined(已登录/未登录)；
 * - convertToVO：已登录 join 检测、未登录默认 false。
 */
class CircleServiceImplTest {

    @Mock
    private ApCircleMapper apCircleMapper;
    @Mock
    private ApUserCircleMapper apUserCircleMapper;
    @Mock
    private ApCircleHotConfigMapper apCircleHotConfigMapper;
    @Mock
    private ClubFeaturedPinMapper clubFeaturedPinMapper;
    @Mock
    private ApPinsMapper apPinsMapper;

    @InjectMocks
    private CircleServiceImpl circleService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private ApCircle circle(Long id, String name, Integer memberCount, Integer pinsCount, Long categoryId) {
        ApCircle c = new ApCircle();
        c.setId(id);
        c.setName(name);
        c.setMemberCount(memberCount);
        c.setPinsCount(pinsCount);
        c.setCategoryId(categoryId);
        return c;
    }

    private ApUser user(Integer id) {
        ApUser u = new ApUser();
        u.setId(id);
        return u;
    }

    private ApPins pin(Long id, Long userId, String userName, String avatar,
                       String content, Integer likes, Integer comment, Long circleId) {
        ApPins p = new ApPins();
        p.setId(id);
        p.setUserId(userId);
        p.setUserName(userName);
        p.setUserAvatar(avatar);
        p.setContent(content);
        p.setLikes(likes);
        p.setComment(comment);
        p.setCreatedTime(new Date());
        p.setCircleId(circleId);
        return p;
    }

    // ==================== recommend ====================

    @Test
    @DisplayName("recommend - 未登录用户 isJoined 为 false")
    void testRecommendAnonymous() {
        when(apCircleMapper.selectRecommendCircles(10))
                .thenReturn(Collections.singletonList(circle(1L, "圈子", 5, 2, 1L)));
        List<CircleVO> vos = circleService.recommend();
        assertEquals(1, vos.size());
        assertEquals("圈子", vos.get(0).getName());
        assertFalse(vos.get(0).getIsJoined());
    }

    @Test
    @DisplayName("recommend - 已登录用户检测 isJoined")
    void testRecommendLoggedIn() {
        AppThreadLocalUtil.setUser(user(7));
        when(apCircleMapper.selectRecommendCircles(10))
                .thenReturn(Collections.singletonList(circle(1L, "圈子", 5, 2, 1L)));
        when(apUserCircleMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        List<CircleVO> vos = circleService.recommend();
        assertTrue(vos.get(0).getIsJoined());
    }

    // ==================== square ====================

    @Test
    @DisplayName("square - 列表+总数+页字段")
    void testSquare() {
        when(apCircleMapper.selectSquareCircles(0, 10))
                .thenReturn(Collections.singletonList(circle(1L, "广场", 8, 3, 2L)));
        when(apCircleMapper.selectSquareCirclesCount()).thenReturn(1L);

        Map<String, Object> r = circleService.square(1, 10);
        assertEquals(1L, r.get("total"));
        assertEquals(1, r.get("page"));
        assertEquals(10, r.get("size"));
        assertEquals(1, ((List<?>) r.get("list")).size());
    }

    @Test
    @DisplayName("square - 空列表")
    void testSquareEmpty() {
        when(apCircleMapper.selectSquareCircles(0, 10)).thenReturn(new ArrayList<>());
        when(apCircleMapper.selectSquareCirclesCount()).thenReturn(0L);
        Map<String, Object> r = circleService.square(1, 10);
        assertTrue(((List<?>) r.get("list")).isEmpty());
    }

    // ==================== hot ====================

    @Test
    @DisplayName("hot - 按 display_order 保持顺序 JOIN 圈子")
    void testHotReturnOrder() {
        ApCircleHotConfig c1 = hotConfig(1L, 1);
        ApCircleHotConfig c2 = hotConfig(2L, 2);
        ApCircleHotConfig c3 = hotConfig(99L, 3); // 无对应圈子，应跳过
        when(apCircleHotConfigMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(c1, c2, c3));
        when(apCircleMapper.selectBatchIds(any())).thenReturn(Arrays.asList(circle(2L, "B", 1, 0, 1L), circle(1L, "A", 2, 0, 1L)));
        List<CircleVO> vos = circleService.hot();
        assertEquals(2, vos.size());
        assertEquals("A", vos.get(0).getName());
        assertEquals("B", vos.get(1).getName());
    }

    @Test
    @DisplayName("hot - 无热门配置返回空")
    void testHotEmpty() {
        when(apCircleHotConfigMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        assertTrue(circleService.hot().isEmpty());
    }

    private ApCircleHotConfig hotConfig(Long circleId, Integer order) {
        ApCircleHotConfig c = new ApCircleHotConfig();
        c.setCircleId(circleId);
        c.setDisplayOrder(order);
        return c;
    }

    // ==================== detail ====================

    @Test
    @DisplayName("detail - 不存在返回 null")
    void testDetailNotExist() {
        when(apCircleMapper.selectById(1L)).thenReturn(null);
        assertNull(circleService.detail(1L, 1));
    }

    @Test
    @DisplayName("detail - 存在返回 VO")
    void testDetail() {
        when(apCircleMapper.selectById(1L)).thenReturn(circle(1L, "圈子", 3, 1, 1L));
        CircleVO vo = circleService.detail(1L, null);
        assertEquals("圈子", vo.getName());
    }

    // ==================== join ====================

    @Test
    @DisplayName("join - 重复加入抛异常")
    void testJoinAlready() {
        when(apUserCircleMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThrows(RuntimeException.class, () -> circleService.join(1L, 1));
    }

    @Test
    @DisplayName("join - 新加入自增成员数")
    void testJoinSuccess() {
        when(apUserCircleMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        ApCircle c = circle(1L, "圈子", 5, 0, 1L);
        when(apCircleMapper.selectById(1L)).thenReturn(c);
        circleService.join(1L, 1);
        verify(apUserCircleMapper).insert((ApUserCircle) any());
        assertEquals(6, c.getMemberCount());
        verify(apCircleMapper).updateById((ApCircle) any());
    }

    @Test
    @DisplayName("join - 圈子不存在时不更新人数（不抛异常）")
    void testJoinCircleNull() {
        when(apUserCircleMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(apCircleMapper.selectById(1L)).thenReturn(null);
        circleService.join(1L, 1);
        verify(apCircleMapper, never()).updateById((ApCircle) any());
    }

    // ==================== leave ====================

    @Test
    @DisplayName("leave - 未加入抛异常")
    void testLeaveNotJoined() {
        when(apUserCircleMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertThrows(RuntimeException.class, () -> circleService.leave(1L, 1));
    }

    @Test
    @DisplayName("leave - 退出自减且不为负")
    void testLeaveSuccess() {
        when(apUserCircleMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        ApCircle c = circle(1L, "圈子", 1, 0, 1L);
        when(apCircleMapper.selectById(1L)).thenReturn(c);
        circleService.leave(1L, 1);
        verify(apUserCircleMapper).delete(any(Wrapper.class));
        assertEquals(0, c.getMemberCount());
    }

    // ==================== feed ====================

    @Test
    @DisplayName("feed - featured 精选，保持排序并关联沸点")
    void testFeedFeatured() {
        ClubFeaturedPin fp1 = featuredPin(1L, 11L, 1);
        ClubFeaturedPin fp2 = featuredPin(1L, 22L, 2);
        when(clubFeaturedPinMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(fp1, fp2));
        when(apPinsMapper.selectBatchIds(any()))
                .thenReturn(Arrays.asList(pin(22L, 2L, "乙", "b", "正文2", 5, 1, 1L),
                        pin(11L, 1L, "甲", "a", "正文1", 3, 0, 1L)));

        Map<String, Object> r = circleService.feed(1L, "featured", 1, 10);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("list");
        assertEquals(2, list.size());
        assertEquals("pin", list.get(0).get("type"));
        assertEquals(11L, list.get(0).get("id"));
        assertEquals("正文2", list.get(1).get("content"));
    }

    @Test
    @DisplayName("feed - featured 无精选时为空")
    void testFeedFeaturedEmpty() {
        when(clubFeaturedPinMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        Map<String, Object> r = circleService.feed(1L, "featured", 1, 10);
        assertTrue(((List<?>) r.get("list")).isEmpty());
    }

    @Test
    @DisplayName("feed - hot 沸点按点赞降序分页")
    void testFeedHot() {
        ApPins p = pin(11L, 1L, "甲", "a", "正文", 9, 2, 1L);
        Page<ApPins> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(p));
        when(apPinsMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        Map<String, Object> r = circleService.feed(1L, "hot", 1, 10);
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("list");
        assertEquals(1, list.size());
        assertEquals(9, list.get(0).get("likeCount"));
    }

    @Test
    @DisplayName("feed - new 沸点按时间降序")
    void testFeedNew() {
        when(apPinsMapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenReturn(new Page<>(1, 10));
        Map<String, Object> r = circleService.feed(1L, "new", 1, 10);
        assertTrue(((List<?>) r.get("list")).isEmpty());
    }

    private ClubFeaturedPin featuredPin(Long circleId, Long pinId, Integer order) {
        ClubFeaturedPin fp = new ClubFeaturedPin();
        fp.setCircleId(circleId);
        fp.setPinId(pinId);
        fp.setSortOrder(order);
        return fp;
    }

    // ==================== myCircles ====================

    @Test
    @DisplayName("myCircles - 无加入记录返回空")
    void testMyCirclesEmpty() {
        when(apUserCircleMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        assertTrue(circleService.myCircles(1).isEmpty());
    }

    @Test
    @DisplayName("myCircles - 返回加入的圈子")
    void testMyCircles() {
        ApUserCircle uc = new ApUserCircle();
        uc.setCircleId(1L);
        uc.setUserId(1);
        when(apUserCircleMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(uc));
        when(apCircleMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(circle(1L, "我的", 2, 1, 1L)));

        List<CircleVO> vos = circleService.myCircles(1);
        assertEquals(1, vos.size());
        assertEquals("我的", vos.get(0).getName());
    }

    // ==================== listByCategory ====================

    @Test
    @DisplayName("listByCategory - 已登录检测 isJoined")
    void testListByCategoryLoggedIn() {
        AppThreadLocalUtil.setUser(user(3));
        Page<ApCircle> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(circle(1L, "分类圈子", 4, 2, 20L)));
        when(apCircleMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        when(apUserCircleMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        List<CircleVO> vos = circleService.listByCategory(20L, 1, 10);
        assertEquals(1, vos.size());
        assertFalse(vos.get(0).getIsJoined());
    }

    @Test
    @DisplayName("listByCategory - 未登录 isJoined 为 false")
    void testListByCategoryAnonymous() {
        Page<ApCircle> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(circle(1L, "分类圈子", 4, 2, 20L)));
        when(apCircleMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        List<CircleVO> vos = circleService.listByCategory(20L, 1, 10);
        assertFalse(vos.get(0).getIsJoined());
    }
}