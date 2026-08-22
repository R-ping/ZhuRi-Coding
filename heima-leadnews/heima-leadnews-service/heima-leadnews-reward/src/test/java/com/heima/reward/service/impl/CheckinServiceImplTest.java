package com.heima.reward.service.impl;

import com.heima.apis.article.ILevelClient;
import com.heima.apis.user.IUserClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.entity.SignRecord;
import com.heima.reward.entity.UserAssets;
import com.heima.reward.entity.UserCheckinState;
import com.heima.reward.mapper.SignRecordMapper;
import com.heima.reward.mapper.UserAssetsMapper;
import com.heima.reward.mapper.UserCheckinStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CheckinServiceImpl 单元测试
 *
 * 覆盖签到核心链路的安全与幂等诉求：
 * 1. Redis 分布式锁竞争(429)与正常获取/释放（finally 内 unlock）；
 * 2. 重复签到阻止(400)与 DuplicateKeyException 兜底；
 * 3. 首次签到（state/assets 为空则 insert）与已存在（则 updateById）；
 * 4. 补签卡不足(400)、补签成功重算奖励。
 */
class CheckinServiceImplTest {

    @Mock
    private SignRecordMapper signRecordMapper;
    @Mock
    private UserCheckinStateMapper userCheckinStateMapper;
    @Mock
    private UserAssetsMapper userAssetsMapper;
    @Mock
    private IUserClient userClient;
    @Mock
    private ILevelClient levelClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CheckinServiceImpl checkinService;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ==================== doCheckin - 分布式锁 ====================

    @Test
    @DisplayName("doCheckin - 锁竞争返回429且不释放锁")
    void testDoCheckinLockBusy() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        ResponseResult result = checkinService.doCheckin(userId);

        assertEquals(429, result.getCode());
        verify(redisTemplate, never()).delete(anyString());
    }

    // ==================== doCheckin - 重复签到 ====================

    @Test
    @DisplayName("doCheckin - 今日已签到返回400并释放锁")
    void testDoCheckinAlreadySignedToday() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(signRecordMapper.selectCount(any())).thenReturn(1L);

        ResponseResult result = checkinService.doCheckin(userId);

        assertEquals(400, result.getCode());
        verify(redisTemplate).delete("sign:lock:100");
    }

    // ==================== doCheckin - 首次签到成功 ====================

    @Test
    @DisplayName("doCheckin - 首次签到(state/assets为空)插入记录并返回奖励")
    void testDoCheckinFirstTime() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(signRecordMapper.selectOne(any())).thenReturn(null);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);
        when(userAssetsMapper.selectById(userId)).thenReturn(null);

        ResponseResult result = checkinService.doCheckin(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(100, data.get("awardOre"));
        assertEquals(1, data.get("continuousDays"));
        verify(signRecordMapper).insert(any(SignRecord.class));
        verify(userCheckinStateMapper).insert(any(UserCheckinState.class));
        verify(userAssetsMapper).insert(any(UserAssets.class));
        verify(redisTemplate).delete("sign:lock:100");
    }

    @Test
    @DisplayName("doCheckin - 已有state/assets时更新而非插入")
    void testDoCheckinExistingStateAndAssets() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(signRecordMapper.selectOne(any())).thenReturn(null);

        UserCheckinState state = new UserCheckinState();
        state.setTotalCheckinDays(5);
        state.setContinuousDays(3);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(state);

        UserAssets assets = new UserAssets();
        assets.setOreBalance(2000);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);

        ResponseResult result = checkinService.doCheckin(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        // 连续天数由数据库回溯得到(selectOne返回null) -> 第1天奖励100
        assertEquals(100, data.get("awardOre"));
        assertEquals(6, data.get("totalSignDays"));
        assertEquals(2100, data.get("totalOre"));
        verify(userCheckinStateMapper).updateById(state);
        verify(userAssetsMapper).updateById(assets);
        verify(userCheckinStateMapper, never()).insert(any(UserCheckinState.class));
    }

    @Test
    @DisplayName("doCheckin - 插入记录抛DuplicateKeyException返回400")
    void testDoCheckinDuplicateKeyInsert() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(signRecordMapper.selectOne(any())).thenReturn(null);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);
        when(signRecordMapper.insert(any(SignRecord.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        ResponseResult result = checkinService.doCheckin(userId);

        assertEquals(400, result.getCode());
        verify(redisTemplate).delete("sign:lock:100");
    }

    // ==================== doExtra - 补签 ====================

    @Test
    @DisplayName("doExtra - 补签卡不足返回400")
    void testDoExtraNotEnoughPatchCard() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        LocalDate target = LocalDate.now(ZONE).minusDays(5);
        // 过去5天，已有记录为空 -> 进入补签卡校验
        when(signRecordMapper.selectOne(any())).thenReturn(null);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);

        ResponseResult result = checkinService.doExtra(userId, target.toString());

        assertEquals(400, result.getCode());
        verify(redisTemplate).delete("sign:lock:100");
    }

    @Test
    @DisplayName("doExtra - 补签未来日期返回400")
    void testDoExtraFutureDate() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        LocalDate future = LocalDate.now(ZONE).plusDays(1);

        ResponseResult result = checkinService.doExtra(userId, future.toString());

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("doExtra - 超出30天返回400")
    void testDoExtraTooOld() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        LocalDate tooOld = LocalDate.now(ZONE).minusDays(31);

        ResponseResult result = checkinService.doExtra(userId, tooOld.toString());

        assertEquals(400, result.getCode());
    }

    // ==================== getStatus ====================

    @Test
    @DisplayName("getStatus - 今日未签到且无资产无用户信息时返回默认值")
    void testGetStatusDefault() {
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(signRecordMapper.selectOne(any())).thenReturn(null);
        when(userAssetsMapper.selectById(userId)).thenReturn(null);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);
        when(userClient.getBasicInfo(userId)).thenReturn(null);
        when(levelClient.getUserLevelInfo(userId)).thenReturn(null);

        ResponseResult result = checkinService.getStatus(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(false, data.get("todaySigned"));
        assertEquals(0, data.get("continuousDays"));
        assertEquals(0, data.get("totalOre"));
    }

    // ==================== getTodayStatus ====================

    @Test
    @DisplayName("getTodayStatus - 今日未签到返回未签到状态")
    void testGetTodayStatusNotSigned() {
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(signRecordMapper.selectOne(any())).thenReturn(null);
        when(userAssetsMapper.selectById(userId)).thenReturn(null);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);

        ResponseResult result = checkinService.getTodayStatus(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(false, data.get("isSignedIn"));
        assertEquals(0, data.get("consecutiveDays"));
        assertEquals(0, data.get("patchCardCount"));
    }

    // ==================== getContinuousCheckinDays ====================

    @Test
    @DisplayName("getContinuousCheckinDays - 今日未签到返回连续天数")
    void testGetContinuousCheckinDaysNotSigned() {
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(signRecordMapper.selectOne(any())).thenReturn(null);

        ResponseResult result = checkinService.getContinuousCheckinDays(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, data.get("continuousDays"));
    }

    @Test
    @DisplayName("getContinuousCheckinDays - 今日已签到连续天数+1")
    void testGetContinuousCheckinDaysSigned() {
        when(signRecordMapper.selectCount(any())).thenReturn(1L);
        when(signRecordMapper.selectOne(any())).thenReturn(null);

        ResponseResult result = checkinService.getContinuousCheckinDays(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, data.get("continuousDays"));
    }
}