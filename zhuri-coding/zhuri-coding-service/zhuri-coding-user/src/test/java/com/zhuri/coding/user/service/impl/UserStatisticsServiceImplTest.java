package com.zhuri.coding.user.service.impl;

import com.zhuri.coding.apis.article.IArticleClient;
import com.zhuri.coding.apis.article.ILevelClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * UserStatisticsServiceImpl 单元测试（用户创作中心统计聚合）
 *
 * 纯 @Service，经 @InjectMocks 注入两个 Feign Client Mock：
 * - articleClient.getStatisticsFeign：拉取文章/沸点统计；
 * - levelClient.getUserLevelData：拉取逐友(日)等级数据。
 * 覆盖：未登录兜底、Feign 返回数据形态、注册天数、等级数据缺失/异常兜底。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserStatisticsService 用户统计")
class UserStatisticsServiceImplTest {

    @Mock
    private IArticleClient articleClient;

    @Mock
    private ILevelClient levelClient;

    @InjectMocks
    private UserStatisticsServiceImpl statisticsService;

    private static final Integer USER_ID = 1001;

    private ApUser user() {
        ApUser u = new ApUser();
        u.setId(USER_ID);
        u.setNickname("测试用户");
        u.setPhone("13800138000");
        u.setImage("avatar_head_1");
        return u;
    }

    @BeforeEach
    void setUp() {
        AppThreadLocalUtil.clear();
        AppThreadLocalUtil.setUser(user());
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private Map<String, Object> levelMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("levelBadge", "ZR.5");
        m.put("levelScore", 88);
        m.put("dailyLevel", 6);
        return m;
    }

    @Nested
    @DisplayName("聚合用户统计")
    class GetUserStatistics {

        @Test
        @DisplayName("未登录 → 返回空Map")
        void testNotLogin() {
            AppThreadLocalUtil.clear();

            ResponseResult r = statisticsService.getUserStatistics();

            assertNotNull(r.getData());
            assertTrue(((Map<?, ?>) r.getData()).isEmpty());
        }

        @Test
        @DisplayName("正常聚合：文章统计 + 等级数据齐全")
        void testFull() {
            Map<String, Object> articleStats = new HashMap<>();
            articleStats.put("articleCount", 3);
            when(articleClient.getStatisticsFeign(anyLong())).thenReturn(ResponseResult.okResult(articleStats));

            ApUser u = user();
            u.setCreatedTime(new Date(System.currentTimeMillis() - 5L * 24 * 3600 * 1000)); // 5天前
            AppThreadLocalUtil.setUser(u);

            when(levelClient.getUserLevelData(anyLong())).thenReturn(levelMap());

            ResponseResult r = statisticsService.getUserStatistics();

            Map<String, Object> data = (Map<String, Object>) r.getData();
            assertEquals(3, data.get("articleCount"));      // 文章统计透传
            assertEquals(5L, data.get("createDays"));        // 注册天数=5
            assertEquals("ZR.5", data.get("levelBadge"));
            assertEquals(6, data.get("dailyLevel"));
        }

        @Test
        @DisplayName("注册时间为空 → createDays 兜底为 1")
        void testNullCreatedTime() {
            when(articleClient.getStatisticsFeign(anyLong())).thenReturn(null); // Feign 无返回
            when(levelClient.getUserLevelData(anyLong())).thenReturn(levelMap());

            ResponseResult r = statisticsService.getUserStatistics();

            Map<String, Object> data = (Map<String, Object>) r.getData();
            assertEquals(1, data.get("createDays"));
            assertEquals("ZR.5", data.get("levelBadge"));
        }

        @Test
        @DisplayName("Feign 数据非Map → 忽略，使用空Map")
        void testNonMapFeignData() {
            when(articleClient.getStatisticsFeign(anyLong()))
                    .thenReturn(ResponseResult.okResult("not-a-map"));
            when(levelClient.getUserLevelData(anyLong())).thenReturn(levelMap());

            ResponseResult r = statisticsService.getUserStatistics();

            Map<String, Object> data = (Map<String, Object>) r.getData();
            assertEquals("ZR.5", data.get("levelBadge"));
        }

        @Test
        @DisplayName("等级接口无数据 → 等级字段全部兜底")
        void testNullLevelData() {
            when(articleClient.getStatisticsFeign(anyLong()))
                    .thenReturn(ResponseResult.okResult(new HashMap<>()));
            when(levelClient.getUserLevelData(anyLong())).thenReturn(null);

            ResponseResult r = statisticsService.getUserStatistics();

            Map<String, Object> data = (Map<String, Object>) r.getData();
            assertEquals("ZR.1", data.get("levelBadge"));
            assertEquals(0, data.get("levelScore"));
            assertEquals(15, data.get("levelMax"));
            assertEquals(1, data.get("dailyLevel"));
        }

        @Test
        @DisplayName("等级接口异常 → 捕获后等级字段兜底")
        void testLevelException() {
            when(articleClient.getStatisticsFeign(anyLong()))
                    .thenReturn(ResponseResult.okResult(new HashMap<>()));
            when(levelClient.getUserLevelData(anyLong())).thenThrow(new RuntimeException("level down"));

            ResponseResult r = statisticsService.getUserStatistics();

            assertEquals("ZR.1", ((Map<String, Object>) r.getData()).get("levelBadge"));
        }
    }
}