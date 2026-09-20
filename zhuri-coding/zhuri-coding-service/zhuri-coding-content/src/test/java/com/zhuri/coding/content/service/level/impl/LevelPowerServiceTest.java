package com.zhuri.coding.content.service.level.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.level.ApUserLevelMapper;
import com.zhuri.coding.content.mapper.user.ApUserDailyLogMapper;
import com.zhuri.coding.content.service.level.LevelPermissionService;
import com.zhuri.coding.model.level.pojos.ApUserLevel;
import com.zhuri.coding.model.user.pojos.ApUserDailyLog;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LevelPowerService 单元测试（逐力值计算与等级更新）
 *
 * @Service 依赖 dailyLogMapper、userLevelMapper、LevelQueryService、LevelPermissionService、LevelDiamondService。
 * 覆盖：
 * - calculatePowerWithLimit：常规获得逐力值、等级升级触发权限重算与钻石奖励、达日限额返回未获得、发布/互动/阅读/兜底各 changeType 分支、实际值<=0 短路、入明细与等级落库；
 * - calculatePower：委托 calculatePowerWithLimit 的简化入口。
 */
class LevelPowerServiceTest {

    @Mock
    private ApUserDailyLogMapper dailyLogMapper;
    @Mock
    private ApUserLevelMapper userLevelMapper;
    @Mock
    private LevelQueryService levelQueryService;
    @Mock
    private LevelPermissionService permissionService;
    @Mock
    private LevelDiamondService diamondService;

    @InjectMocks
    private LevelPowerService levelPowerService;

    private final Long userId = 100L;
    private final Long articleId = 10L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 预热 MybatisPlus 实体表元数据
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserDailyLog.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserLevel.class);
    }

    private ApUserLevel userLevel(Integer powerValue, Integer powerValueToday, Integer powerLevel) {
        ApUserLevel u = new ApUserLevel();
        u.setId(1L);
        u.setUserId(userId);
        u.setPowerValue(powerValue);
        u.setPowerValueToday(powerValueToday);
        u.setPowerLevel(powerLevel);
        return u;
    }

    private void mockLevel(int currentLevel, int newLevel) {
        when(levelQueryService.getUserLevel(userId)).thenReturn(userLevel(5, 0, currentLevel));
        when(levelQueryService.calculateLevel(eq(2), any())).thenReturn(newLevel);
    }

    @Test
    @DisplayName("常规获得逐力值且等级不变")
    void calculatePowerSameLevel() {
        mockLevel(1, 1);
        when(dailyLogMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> r = levelPowerService.calculatePowerWithLimit(userId, articleId, "get_like", 1);

        assertTrue((Boolean) r.get("success"));
        assertEquals(1, r.get("power"));
        assertFalse((Boolean) r.get("levelChanged"));
        verify(dailyLogMapper).insert(any(ApUserDailyLog.class));
        verify(userLevelMapper).updateById(any(ApUserLevel.class));
        verify(permissionService, never()).updateUserPermissions(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("等级升级时触发权限重算与钻石奖励")
    void calculatePowerLevelUp() {
        mockLevel(1, 2);
        when(dailyLogMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> r = levelPowerService.calculatePowerWithLimit(userId, articleId, "get_favorite", 1);

        assertTrue((Boolean) r.get("success"));
        assertTrue((Boolean) r.get("levelChanged"));
        assertEquals(1, r.get("oldLevel"));
        assertEquals(2, r.get("newLevel"));
        verify(permissionService).updateUserPermissions(userId, 2, 1, 2);
        verify(diamondService).grantDiamondOnLevelUp(userId, 2, 2);
    }

    @Test
    @DisplayName("发布文章达日限额时返回未获得")
    void calculatePowerLimitHit() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(userLevel(5, 0, 1));
        when(dailyLogMapper.selectCount(any())).thenReturn(2L); // publish_article 上限 2

        Map<String, Object> r = levelPowerService.calculatePowerWithLimit(userId, articleId, "publish_article", 10);

        assertFalse((Boolean) r.get("success"));
        assertEquals("未获得逐力值", r.get("message"));
        assertEquals(0, r.get("power"));
        verify(dailyLogMapper, never()).insert(any(ApUserDailyLog.class));
    }

    @Test
    @DisplayName("发布文章未超限获得 10 点逐力值")
    void calculatePowerPublish() {
        mockLevel(1, 1);
        when(dailyLogMapper.selectCount(any())).thenReturn(0L);
        Map<String, Object> r = levelPowerService.calculatePowerWithLimit(userId, articleId, "publish_article", 10);
        assertEquals(10, r.get("power"));
    }

    @Test
    @DisplayName("阅读按浏览数折算逐力值")
    void calculatePowerRead() {
        mockLevel(1, 1);
        when(dailyLogMapper.selectCount(any())).thenReturn(0L);
        Map<String, Object> r = levelPowerService.calculatePowerWithLimit(userId, articleId, "get_read", 150);
        assertEquals(1, r.get("power")); // 150/100
    }

    @Test
    @DisplayName("阅读不足 100 时实际逐力值为 0 且短路")
    void calculatePowerReadZero() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(userLevel(5, 0, 1));
        Map<String, Object> r = levelPowerService.calculatePowerWithLimit(userId, articleId, "get_read", 50);
        assertFalse((Boolean) r.get("success"));
        assertEquals(0, r.get("power"));
    }

    @Test
    @DisplayName("兜底 changeType 直接采用 powerChange")
    void calculatePowerDefault() {
        mockLevel(1, 1);
        when(dailyLogMapper.selectCount(any())).thenReturn(0L);
        Map<String, Object> r = levelPowerService.calculatePowerWithLimit(userId, articleId, "share", 7);
        assertEquals(7, r.get("power"));
    }

    @Test
    @DisplayName("简化入口 calculatePower 委托主逻辑")
    void calculatePowerNoResult() {
        mockLevel(1, 1);
        when(dailyLogMapper.selectCount(any())).thenReturn(0L);
        levelPowerService.calculatePower(userId, articleId, "get_comment", 1);
        verify(userLevelMapper).updateById(any(ApUserLevel.class));
    }
}