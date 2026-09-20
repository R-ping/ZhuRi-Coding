package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.heima.apis.reward.IRewardClient;
import com.heima.content.mapper.level.ApLevelConfigMapper;
import com.heima.content.mapper.user.ApUserDiamondLogMapper;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.level.pojos.ApLevelConfig;
import com.heima.model.user.pojos.ApUserDiamondLog;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LevelDiamondService 单元测试（等级升级矿石奖励发放）
 *
 * @Service 依赖 levelConfigMapper、diamondLogMapper、IRewardClient（options Feign mock）。
 * 覆盖：
 * - 无等级配置 / 无钻石奖励配置时直接跳过；
 * - 归属配置存在时经 reward 远程增加矿石余额并落钻石明细日志；
 * - 远程返回 null / 异常时安全降级跳过；
 * - 免责重量级 try-catch 防御路径。
 */
class LevelDiamondServiceTest {

    @Mock
    private ApLevelConfigMapper levelConfigMapper;
    @Mock
    private ApUserDiamondLogMapper diamondLogMapper;
    @Mock
    private LevelQueryService levelQueryService;
    @Mock
    private IRewardClient rewardClient;

    @InjectMocks
    private LevelDiamondService levelDiamondService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApLevelConfig.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserDiamondLog.class);
    }

    private ApLevelConfig config(Integer diamondReward) {
        ApLevelConfig c = new ApLevelConfig();
        c.setLevelType(2);
        c.setLevelValue(3);
        c.setDiamondReward(diamondReward);
        return c;
    }

    @Test
    @DisplayName("无等级配置时跳过发放")
    void grantNoConfig() {
        when(levelConfigMapper.selectOne(any())).thenReturn(null);
        levelDiamondService.grantDiamondOnLevelUp(userId, 2, 3);
        verify(rewardClient, never()).addOreBalance(any(), anyInt());
        verify(diamondLogMapper, never()).insert(any(ApUserDiamondLog.class));
    }

    @Test
    @DisplayName("无钻石奖励配置或奖励<=0时跳过")
    void grantNoReward() {
        when(levelConfigMapper.selectOne(any())).thenReturn(config(0));
        levelDiamondService.grantDiamondOnLevelUp(userId, 2, 3);
        verify(rewardClient, never()).addOreBalance(any(), anyInt());
    }

    @Test
    @DisplayName("正常发放钻石并落明细日志")
    void grantOk() {
        when(levelConfigMapper.selectOne(any())).thenReturn(config(50));
        when(rewardClient.addOreBalance(userId, 50))
                .thenReturn(ResponseResult.okResult(Map.of("oreBalance", 888)));
        levelDiamondService.grantDiamondOnLevelUp(userId, 2, 3);
        verify(diamondLogMapper).insert(any(ApUserDiamondLog.class));
    }

    @Test
    @DisplayName("远程返回异常时不崩溃")
    void grantRemoteException() {
        when(levelConfigMapper.selectOne(any())).thenReturn(config(50));
        when(rewardClient.addOreBalance(userId, 50)).thenThrow(new RuntimeException("down"));
        assertDoesNotThrow(() -> levelDiamondService.grantDiamondOnLevelUp(userId, 2, 3));
        verify(diamondLogMapper, never()).insert(any(ApUserDiamondLog.class));
    }

    @Test
    @DisplayName("远程返回 null 时跳过")
    void grantRemoteNull() {
        when(levelConfigMapper.selectOne(any())).thenReturn(config(50));
        when(rewardClient.addOreBalance(userId, 50)).thenReturn(null);
        levelDiamondService.grantDiamondOnLevelUp(userId, 2, 3);
        verify(diamondLogMapper, never()).insert(any(ApUserDiamondLog.class));
    }
}