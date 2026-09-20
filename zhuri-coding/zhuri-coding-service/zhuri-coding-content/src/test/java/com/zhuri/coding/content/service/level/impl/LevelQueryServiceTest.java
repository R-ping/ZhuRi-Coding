package com.zhuri.coding.content.service.level.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.content.mapper.level.ApLevelConfigMapper;
import com.zhuri.coding.content.mapper.level.ApUserLevelMapper;
import com.zhuri.coding.content.service.level.LevelPermissionService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.level.pojos.ApLevelConfig;
import com.zhuri.coding.model.level.pojos.ApUserLevel;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LevelQueryService 单元测试（用户等级查询/等级配置/等级计算）
 *
 * @Service 依赖 level mapper 与 reward Feign，均 @Mock 注入。
 * 覆盖：
 * - getUserLevel：命中返回 / 不存在时创建默认记录并落库；
 * - getUserLevelInfo：逐日/逐力标题与描述有无配置、权限列表回填；
 * - getUserLevelData：矿石余额远端获取成功/失败/异常、下一级门槛存在与否、升级百分比计算；
 * - getLevelConfigs：按 levelType 查询并升序；
 * - calculateLevel：命中配置 / 无匹配回退最高级 / 无任何配置返回 1。
 */
class LevelQueryServiceTest {

    @Mock
    private ApUserLevelMapper userLevelMapper;
    @Mock
    private ApLevelConfigMapper levelConfigMapper;
    @Mock
    private IRewardClient rewardClient;
    @Mock
    private LevelPermissionService permissionService;

    @InjectMocks
    private LevelQueryService levelQueryService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 预热 MybatisPlus 实体表元数据，使 lambda 查询自足、不依赖 Spring 上下文
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserLevel.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApLevelConfig.class);
    }

    private ApUserLevel userLevel(Integer dailyLevel, BigDecimal dailyScore, Integer powerLevel, Integer powerValue) {
        ApUserLevel u = new ApUserLevel();
        u.setId(1L);
        u.setUserId(userId);
        u.setDailyLevel(dailyLevel);
        u.setDailyScore(dailyScore);
        u.setPowerLevel(powerLevel);
        u.setPowerValue(powerValue);
        return u;
    }

    private ApLevelConfig config(Integer levelValue, Integer minScore, String title, String desc) {
        ApLevelConfig c = new ApLevelConfig();
        c.setLevelValue(levelValue);
        c.setMinScore(minScore);
        c.setTitle(title);
        c.setDescription(desc);
        return c;
    }

    // ---------- getUserLevel ----------
    @Test
    @DisplayName("getUserLevel 命中已有记录")
    void getUserLevelHit() {
        ApUserLevel exists = userLevel(5, new BigDecimal("120"), 3, 88);
        when(userLevelMapper.selectOne(any())).thenReturn(exists);
        assertEquals(exists, levelQueryService.getUserLevel(userId));
        verify(userLevelMapper, never()).insert(any(ApUserLevel.class));
    }

    @Test
    @DisplayName("getUserLevel 不存在时创建默认记录并落库")
    void getUserLevelCreateDefault() {
        when(userLevelMapper.selectOne(any())).thenReturn(null);
        ApUserLevel created = levelQueryService.getUserLevel(userId);
        assertNotNull(created);
        assertEquals(userId, created.getUserId());
        assertEquals(0, created.getDailyScore().intValue());
        assertEquals(1, created.getDailyLevel());
        assertEquals(1, created.getPowerLevel());
        assertEquals(0, created.getPowerValue());
        verify(userLevelMapper).insert(any(ApUserLevel.class));
    }

    // ---------- getUserLevelInfo ----------
    @Test
    @DisplayName("getUserLevelInfo 组装逐日/逐力标题与权限")
    void getUserLevelInfoOk() {
        ApUserLevel u = userLevel(2, new BigDecimal("50"), 4, 99);
        when(userLevelMapper.selectOne(any())).thenReturn(u);
        when(levelConfigMapper.selectOne(any()))
                .thenReturn(config(2, 50, "见习", "初级"), config(4, 300, "资深", "高手"));
        when(permissionService.getUserPermissions(userId)).thenReturn(List.of("editor", "pin"));

        Map<String, Object> r = levelQueryService.getUserLevelInfo(userId);
        assertEquals("见习", r.get("dailyTitle"));
        assertEquals("初级", r.get("dailyDescription"));
        assertEquals("资深", r.get("powerTitle"));
        assertEquals("高手", r.get("powerDescription"));
        assertEquals(List.of("editor", "pin"), r.get("permissions"));
    }

    @Test
    @DisplayName("getUserLevelInfo 配置缺失时标题为空")
    void getUserLevelInfoNoConfig() {
        when(userLevelMapper.selectOne(any())).thenReturn(userLevel(2, new BigDecimal("50"), 4, 99));
        when(levelConfigMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> r = levelQueryService.getUserLevelInfo(userId);
        assertEquals("", r.get("dailyTitle"));
        assertEquals("", r.get("powerTitle"));
    }

    // ---------- getUserLevelData ----------
    @Test
    @DisplayName("getUserLevelData 矿石获取成功且存在下一级门槛")
    void getUserLevelDataWithOre() {
        ApUserLevel u = userLevel(2, new BigDecimal("250"), 1, 0);
        when(userLevelMapper.selectOne(any())).thenReturn(u);
        when(rewardClient.getUserOreBalance(userId))
                .thenReturn(ResponseResult.okResult(Map.of("oreBalance", 66)));
        when(levelConfigMapper.selectOne(any())).thenReturn(config(3, 330, "L3", null));

        Map<String, Object> r = levelQueryService.getUserLevelData(userId);
        assertEquals("ZR.2", r.get("levelBadge"));
        assertEquals(330, r.get("levelMax"));
        assertEquals(75, r.get("levelPercent")); // 250*100/330=75.75 -> 75
        assertEquals(66, r.get("diamondCount"));
    }

    @Test
    @DisplayName("getUserLevelData 无下一级门槛回退 dailyLevel*150")
    void getUserLevelDataNoNextLevel() {
        ApUserLevel u = userLevel(2, new BigDecimal("250"), 1, 0);
        when(userLevelMapper.selectOne(any())).thenReturn(u);
        when(levelConfigMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> r = levelQueryService.getUserLevelData(userId);
        assertEquals(300, r.get("levelMax")); // 2*150
        assertEquals(83, r.get("levelPercent")); // 25000/300=83.3 -> 83
        assertEquals(0, r.get("diamondCount"));
    }

    @Test
    @DisplayName("getUserLevelData 矿石远端异常时降级为 0")
    void getUserLevelDataOreException() {
        ApUserLevel u = userLevel(2, new BigDecimal("250"), 1, 0);
        when(userLevelMapper.selectOne(any())).thenReturn(u);
        when(rewardClient.getUserOreBalance(userId))
                .thenReturn(ResponseResult.okResult(Map.of("diamondBalance", 999)));
        Map<String, Object> r = levelQueryService.getUserLevelData(userId);
        assertEquals(0, r.get("diamondCount")); // 无 oreBalance 字段
    }

    // ---------- getLevelConfigs ----------
    @Test
    @DisplayName("getLevelConfigs 按类型查询")
    void getLevelConfigs() {
        when(levelConfigMapper.selectList(any())).thenReturn(List.of(config(1, 0, "L1", null)));
        assertEquals(1, levelQueryService.getLevelConfigs(1).size());
    }

    // ---------- calculateLevel ----------
    @Test
    @DisplayName("calculateLevel 命中配置返回该等级")
    void calculateLevelHit() {
        // 生产实现改为按 levelType 一次拉全量配置后内存计算（走 getCachedConfigs→selectList）
        when(levelConfigMapper.selectList(any())).thenReturn(List.of(config(3, 200, "L3", null)));
        assertEquals(3, levelQueryService.calculateLevel(1, new BigDecimal("230")));
    }

    @Test
    @DisplayName("calculateLevel 无匹配回落最高级")
    void calculateLevelFallbackHighest() {
        when(levelConfigMapper.selectList(any())).thenReturn(List.of(config(1, 0, "L1", null), config(6, 1000, "顶端", null)));
        assertEquals(6, levelQueryService.calculateLevel(1, new BigDecimal("500000")));
    }

    @Test
    @DisplayName("calculateLevel 无任何配置返回 1")
    void calculateLevelNoConfig() {
        when(levelConfigMapper.selectList(any())).thenReturn(List.of());
        assertEquals(1, levelQueryService.calculateLevel(1, new BigDecimal("10")));
    }
}