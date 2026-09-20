package com.zhuri.coding.content.service.fans.impl;

import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.model.common.dtos.ResponseResult;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FansDataServiceImpl 单元测试（粉丝数据看板：统计/趋势/列表/画像/头像）
 *
 * 纯 @Service 类，通过 @InjectMocks 注入 ApFollowMapper 与 JdbcTemplate；
 * 登录用户通过 AppThreadLocalUtil.setUser 控制。
 */
class FansDataServiceImplTest {

    @Mock
    private ApFollowMapper apFollowMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private FansDataServiceImpl fansService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private void login(Integer userId) {
        ApUser user = new ApUser();
        user.setId(userId);
        AppThreadLocalUtil.setUser(user);
    }

    // ---------- 未登录 ----------
    @Test
    @DisplayName("未登录时各接口返回空占位数据")
    void notLoggedInGuards() {
        assertEquals(0, ((Map<?, ?>) fansService.getFansStatistics("2026-08-01", "2026-08-07").getData()).size());
        assertEquals(0, ((List<?>) fansService.getFansTrend("2026-08-01", "2026-08-07", 7).getData()).size());
        assertEquals(0, ((Map<?, ?>) fansService.getFansList(1, 20).getData()).size());
        assertEquals(0, ((Map<?, ?>) fansService.getFansAvatars(1, 20).getData()).size());
        assertNull(fansService.followFans(2).getData());
    }

    // ---------- getFansStatistics ----------
    @Test
    @DisplayName("getFansStatistics 计算当前期与上期指标及趋势")
    void getFansStatistics() {
        login(1);
        // current.total=10, current.new=10, prev.total=3, prev.new=3
        when(apFollowMapper.selectCount(any()))
                .thenReturn(10L)
                .thenReturn(10L)
                .thenReturn(3L)
                .thenReturn(3L);

        Map<?, ?> data = (Map<?, ?>) fansService.getFansStatistics("2026-08-01", "2026-08-07").getData();
        assertEquals(10, ((Number) data.get("totalFans")).intValue());
        assertEquals(10, ((Number) data.get("interactiveFans")).intValue());
        assertEquals(10, ((Number) data.get("newFans")).intValue());
        assertEquals(10, ((Number) data.get("netGrowth")).intValue());
        // 断言趋势计算涉及的核心字段均已生成
        org.junit.jupiter.api.Assertions.assertTrue(data.containsKey("totalTrend"));
        org.junit.jupiter.api.Assertions.assertTrue(data.containsKey("newFansTrend"));
    }

    // ---------- getFansTrend ----------
    @Test
    @DisplayName("getFansTrend 逐日生成趋势点")
    void getFansTrend() {
        login(1);
        when(apFollowMapper.selectCount(any())).thenReturn(2L);
        List<?> list = (List<?>) fansService.getFansTrend("2026-08-01", "2026-08-02", 2).getData();
        assertEquals(2, list.size());
        Map<?, ?> point = (Map<?, ?>) list.get(0);
        assertEquals("2026-08-01", point.get("date"));
        assertEquals(2, ((Number) point.get("totalFans")).intValue());
        assertEquals(2, ((Number) point.get("newFans")).intValue());
    }

    // ---------- getFansList ----------
    @Test
    @DisplayName("getFansList 分页查询并填充用户信息与回关状态")
    void getFansList() {
        login(1);
        ApFollow follow = new ApFollow();
        follow.setUserId(5);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ApFollow> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        page.setRecords(List.of(follow));
        page.setTotal(1);
        when(apFollowMapper.selectPage(any(), any())).thenReturn(page);
        when(jdbcTemplate.queryForList(anyString(), eq(5))).thenReturn(List.of(pair(5, "小明", "a.png")));
        when(apFollowMapper.selectCount(any())).thenReturn(0L);

        Map<?, ?> data = (Map<?, ?>) fansService.getFansList(1, 20).getData();
        assertEquals(1L, ((Number) data.get("total")).longValue());
        Map<?, ?> item = (Map<?, ?>) ((List<?>) data.get("list")).get(0);
        assertEquals(5, item.get("userId"));
        assertEquals("小明", item.get("nickName"));
        assertEquals("a.png", item.get("avatar"));
        assertEquals(false, item.get("isFollowed"));
    }

    @Test
    @DisplayName("getFansList 用户信息缺失时回退空字符串")
    void getFansListNoUserInfo() {
        login(1);
        ApFollow follow = new ApFollow();
        follow.setUserId(5);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ApFollow> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        page.setRecords(List.of(follow));
        page.setTotal(1);
        when(apFollowMapper.selectPage(any(), any())).thenReturn(page);
        when(jdbcTemplate.queryForList(anyString(), eq(5))).thenReturn(List.of());
        when(apFollowMapper.selectCount(any())).thenReturn(1L);

        Map<?, ?> item = (Map<?, ?>) ((List<?>) ((Map<?, ?>) fansService.getFansList(1, 20).getData()).get("list")).get(0);
        assertEquals("", item.get("nickName"));
        assertEquals(true, item.get("isFollowed"));
    }

    // ---------- followFans ----------
    @Test
    @DisplayName("followFans 自关注、重复关注、空参数静默返回")
    void followFansInvalid() {
        login(1);
        assertNull(fansService.followFans(1).getData()); // 自关注
        when(apFollowMapper.selectOne(any())).thenReturn(new ApFollow());
        assertNull(fansService.followFans(2).getData());  // 已关注
        assertNull(fansService.followFans(null).getData()); // 空 target
    }

    @Test
    @DisplayName("followFans 插入，并发冲突幂等降级")
    void followFansInsertWithDup() {
        login(1);
        when(apFollowMapper.selectOne(any())).thenReturn(null);
        // 第一次插入抛重复，第二次成功
        when(apFollowMapper.insert(any(ApFollow.class)))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenReturn(1);

        assertNull(fansService.followFans(2).getData());
        assertNull(fansService.followFans(3).getData());
        verify(apFollowMapper, times(2)).insert(any(ApFollow.class));
    }

    // ---------- getFansPortrait / avatars ----------
    @Test
    @DisplayName("getFansPortrait 返回空分布占位")
    void getFansPortrait() {
        login(1);
        assertEquals(3, ((Map<?, ?>) fansService.getFansPortrait().getData()).size());
    }

    @Test
    @DisplayName("getFansAvatars 返回用户头像列表")
    void getFansAvatars() {
        login(1);
        ApFollow follow = new ApFollow();
        follow.setUserId(5);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ApFollow> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        page.setRecords(List.of(follow));
        page.setTotal(1);
        when(apFollowMapper.selectPage(any(), any())).thenReturn(page);
        when(jdbcTemplate.queryForList(anyString(), eq(5))).thenReturn(List.of(pair(5, "小张", "b.png")));

        Map<?, ?> data = (Map<?, ?>) fansService.getFansAvatars(1, 20).getData();
        Map<?, ?> item = (Map<?, ?>) ((List<?>) data.get("list")).get(0);
        assertEquals(5, item.get("userId"));
        assertEquals("b.png", item.get("avatar"));
    }

    @Test
    @DisplayName("getFansAvatars 用户缺失时回退默认值")
    void getFansAvatarsNoUser() {
        login(1);
        ApFollow follow = new ApFollow();
        follow.setUserId(7);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ApFollow> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        page.setRecords(List.of(follow));
        when(apFollowMapper.selectPage(any(), any())).thenReturn(page);
        when(jdbcTemplate.queryForList(anyString(), eq(7))).thenReturn(List.of());

        Map<?, ?> item = (Map<?, ?>) ((List<?>) ((Map<?, ?>) fansService.getFansAvatars(1, 20).getData()).get("list")).get(0);
        assertEquals(7, item.get("userId"));
        assertEquals("", item.get("avatar"));
        assertEquals("", item.get("nickName"));
    }

    private Map<String, Object> pair(Integer id, String nickname, String image) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", id);
        row.put("nickname", nickname);
        row.put("image", image);
        return row;
    }
}