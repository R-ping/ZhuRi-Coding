package com.zhuri.coding.reward.service.impl;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.reward.entity.SignRecord;
import com.zhuri.coding.reward.entity.UserAssets;
import com.zhuri.coding.reward.entity.UserCheckinState;
import com.zhuri.coding.reward.mapper.SignRecordMapper;
import com.zhuri.coding.reward.mapper.UserAssetsMapper;
import com.zhuri.coding.reward.mapper.UserCheckinStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.stubbing.Answer;

/**
 * CheckinTxService（事务体内核）单元测试 —— P0-6 拆分后承接原 CheckinServiceImplTest 的业务断言。
 *
 * <p>覆盖签到核心链路的安全与幂等诉求（业务行为与拆分前完全一致，仅归属类变化）：
 * 1. 重复签到阻止(400)与 DuplicateKeyException 兜底；
 * 2. 首次签到（state/assets 为空则 insert）与已存在（则 updateById + 原子累加）；
 * 3. 补签卡不足(400)、补签未来/过期日期(400)。
 * 锁编排语义（429/finally unlock）见 {@link CheckinServiceImplTest}。
 */
class CheckinTxServiceTest {

    @Mock
    private SignRecordMapper signRecordMapper;
    @Mock
    private UserCheckinStateMapper userCheckinStateMapper;
    @Mock
    private UserAssetsMapper userAssetsMapper;

    @InjectMocks
    private CheckinTxService checkinTxService;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ==================== doCheckinTx - 重复签到 ====================

    @Test
    @DisplayName("doCheckinTx - 今日已签到返回400")
    void testDoCheckinAlreadySignedToday() {
        when(signRecordMapper.selectCount(any())).thenReturn(1L);

        ResponseResult result = checkinTxService.doCheckinTx(userId);

        assertEquals(400, result.getCode());
    }

    // ==================== doCheckinTx - 首次签到成功 ====================

    @Test
    @DisplayName("doCheckinTx - 首次签到(state/assets为空)插入记录并返回奖励")
    void testDoCheckinFirstTime() {
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);
        when(userAssetsMapper.selectById(userId)).thenReturn(null);

        ResponseResult result = checkinTxService.doCheckinTx(userId);

        assertEquals(200, result.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        // 连续天数由数据库回溯得到(selectList 返回 null 由 MP 处理为空) -> 第1天奖励100
        assertEquals(100, data.get("awardOre"));
        assertEquals(1, data.get("continuousDays"));
        verify(signRecordMapper).insert(any(SignRecord.class));
        verify(userCheckinStateMapper).insert(any(UserCheckinState.class));
        verify(userAssetsMapper).insert(any(UserAssets.class));
    }

    @Test
    @DisplayName("doCheckinTx - 已有state/assets时更新而非插入")
    void testDoCheckinExistingStateAndAssets() {
        when(signRecordMapper.selectCount(any())).thenReturn(0L);

        UserCheckinState state = new UserCheckinState();
        state.setTotalCheckinDays(5);
        state.setContinuousDays(3);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(state);

        UserAssets assets = new UserAssets();
        assets.setOreBalance(2000);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);
        // 已存在资产时走原子累加；模拟真实 addOreBalance 在内存对象上的累加效果
        doAnswer((Answer<Void>) inv -> {
            assets.setOreBalance(assets.getOreBalance() + (Integer) inv.getArgument(1));
            return null;
        }).when(userAssetsMapper).addOreBalance(anyLong(), anyInt());

        ResponseResult result = checkinTxService.doCheckinTx(userId);

        assertEquals(200, result.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        // 连续天数由数据库回溯得到(selectList空) -> 第1天奖励100
        assertEquals(100, data.get("awardOre"));
        assertEquals(6, data.get("totalSignDays"));
        assertEquals(2100, data.get("totalOre"));
        verify(userCheckinStateMapper).updateById(state);
        // 已存在资产时走原子累加（不再读改写 updateById），并累加到内存对象使返回余矿正确
        verify(userAssetsMapper).addOreBalance(userId, 100);
        verify(userCheckinStateMapper, never()).insert(any(UserCheckinState.class));
    }

    @Test
    @DisplayName("doCheckinTx - 插入记录抛DuplicateKeyException返回400")
    void testDoCheckinDuplicateKeyInsert() {
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);
        when(signRecordMapper.insert(any(SignRecord.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        ResponseResult result = checkinTxService.doCheckinTx(userId);

        assertEquals(400, result.getCode());
    }

    // ==================== doExtraTx - 补签 ====================

    @Test
    @DisplayName("doExtraTx - 补签卡不足返回400")
    void testDoExtraNotEnoughPatchCard() {
        LocalDate target = LocalDate.now(ZONE).minusDays(5);
        // 过去5天，已有记录为空 -> 进入补签卡校验
        when(signRecordMapper.selectOne(any())).thenReturn(null);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);

        ResponseResult result = checkinTxService.doExtraTx(userId, target.toString());

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("doExtraTx - 补签未来日期返回400")
    void testDoExtraFutureDate() {
        LocalDate future = LocalDate.now(ZONE).plusDays(1);

        ResponseResult result = checkinTxService.doExtraTx(userId, future.toString());

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("doExtraTx - 超出30天返回400")
    void testDoExtraTooOld() {
        LocalDate tooOld = LocalDate.now(ZONE).minusDays(31);

        ResponseResult result = checkinTxService.doExtraTx(userId, tooOld.toString());

        assertEquals(400, result.getCode());
    }
}
