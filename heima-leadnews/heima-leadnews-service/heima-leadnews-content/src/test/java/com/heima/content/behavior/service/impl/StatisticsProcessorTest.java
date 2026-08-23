package com.heima.content.behavior.service.impl;

import com.heima.content.mapper.level.ApUserLevelMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.level.pojos.ApUserLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StatisticsProcessor 单元测试（统计数据后置处理器）
 *
 * 逻辑要点：
 * - postProcess：userId 为 null 直接返回；否则更新最后活跃时间
 * - updateLastActiveTime：用户等级记录存在则刷新 updated_time 并 updateById；不存在跳过；异常被捕获
 * - getOrder 返回 5
 */
@ExtendWith(MockitoExtension.class)
class StatisticsProcessorTest {

    @Mock
    private ApUserLevelMapper userLevelMapper;

    @InjectMocks
    private StatisticsProcessor processor;

    // ---------- 辅助 ----------

    private BehaviorContext context(Integer userId) {
        return new BehaviorContext(BehaviorType.BROWSE_ARTICLE, userId).withTarget(1, 100L);
    }

    private BehaviorResult result() {
        return BehaviorResult.success(BehaviorType.BROWSE_ARTICLE);
    }

    // ==================== getOrder ====================

    @Test
    @DisplayName("getOrder - 返回固定整数 5")
    void getOrderShouldReturnFive() {
        assertEquals(5, processor.getOrder());
    }

    // ==================== userId 缺失 ====================

    @Test
    @DisplayName("postProcess - userId 为 null 直接返回，不发生数据库操作")
    void postProcessNullUserId() {
        processor.postProcess(new BehaviorContext(BehaviorType.BROWSE_ARTICLE, null), result());
        verify(userLevelMapper, never()).selectById(anyLong());
        verify(userLevelMapper, never()).updateById(any(ApUserLevel.class));
    }

    // ==================== 最后活跃时间更新 ====================

    @Test
    @DisplayName("postProcess - 存在用户等级记录则刷新最后活跃时间")
    void postProcessUpdatesLastActive() {
        ApUserLevel level = new ApUserLevel();
        when(userLevelMapper.selectById(9L)).thenReturn(level);

        processor.postProcess(context(9), result());

        verify(userLevelMapper).updateById(level);
        assertNotNull(level.getUpdatedTime());
    }

    @Test
    @DisplayName("postProcess - 用户等级记录不存在则跳过更新")
    void postProcessUserLevelNotFound() {
        when(userLevelMapper.selectById(9L)).thenReturn(null);

        processor.postProcess(context(9), result());

        verify(userLevelMapper, never()).updateById(any(ApUserLevel.class));
    }

    @Test
    @DisplayName("postProcess - 更新活跃时间抛异常被捕获，不影响主流程")
    void postProcessCatchesException() {
        ApUserLevel level = new ApUserLevel();
        when(userLevelMapper.selectById(9L)).thenReturn(level);
        doThrow(new RuntimeException("db error")).when(userLevelMapper).updateById(level);

        assertDoesNotThrow(() -> processor.postProcess(context(9), result()));
    }
}