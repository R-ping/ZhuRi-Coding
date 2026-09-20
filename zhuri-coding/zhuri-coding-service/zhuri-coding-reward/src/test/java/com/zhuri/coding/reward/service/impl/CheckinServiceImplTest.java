package com.zhuri.coding.reward.service.impl;

import com.zhuri.coding.apis.article.ILevelClient;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.reward.mapper.SignRecordMapper;
import com.zhuri.coding.reward.mapper.UserAssetsMapper;
import com.zhuri.coding.reward.mapper.UserCheckinStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CheckinServiceImpl（外层锁编排）单元测试 —— P0-6 事务边界修复。
 *
 * <p>拆分后本类只负责「抢锁 → 委托 {@link CheckinTxService}（事务体内核）→ finally 解锁」，
 * 因此本测试只断言<b>编排语义</b>（原业务断言已迁至 CheckinTxServiceTest）：
 * <ol>
 *   <li>抢锁失败 → 429 且<b>绝不进入</b>事务体、绝不 unlock（未持有锁）；</li>
 *   <li>抢锁成功 → 委托事务体、结果透传、最后 unlock（unlock 发生在内层事务边界之后）；</li>
 *   <li>事务体抛异常 → unlock 仍执行（finally），异常上抛给调用方；</li>
 *   <li>只读路径（getStatus / getTodayStatus / getContinuousCheckinDays）不受拆分影响。</li>
 * </ol>
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
    @Mock
    private CheckinTxService checkinTxService;

    @InjectMocks
    private CheckinServiceImpl checkinService;

    private final Long userId = 100L;
    private static final String LOCK_KEY = "sign:lock:100";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ==================== doCheckin - 锁编排（P0-6） ====================

    @Test
    @DisplayName("doCheckin - 锁竞争返回429且不进入事务体、不释放锁")
    void testDoCheckinLockBusy() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        ResponseResult result = checkinService.doCheckin(userId);

        assertEquals(429, result.getCode());
        verify(checkinTxService, never()).doCheckinTx(any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("doCheckin - 抢锁成功委托事务体，结果透传并释放锁")
    void testDoCheckinDelegatesAndUnlocks() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        ResponseResult txResult = ResponseResult.okResult("ok");
        when(checkinTxService.doCheckinTx(userId)).thenReturn(txResult);

        ResponseResult result = checkinService.doCheckin(userId);

        assertSame(txResult, result);
        verify(checkinTxService).doCheckinTx(userId);
        verify(redisTemplate).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("doCheckin - 事务体抛异常 → unlock 仍执行（锁释放在事务边界之后），异常上抛")
    void testDoCheckinTxThrowsStillUnlocks() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        doThrow(new IllegalStateException("db down")).when(checkinTxService).doCheckinTx(userId);

        assertThrows(IllegalStateException.class, () -> checkinService.doCheckin(userId));

        verify(redisTemplate).delete(LOCK_KEY);
    }

    // ==================== doExtra - 锁编排（P0-6） ====================

    @Test
    @DisplayName("doExtra - 锁竞争返回429且不进入事务体")
    void testDoExtraLockBusy() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        ResponseResult result = checkinService.doExtra(userId, "2026-09-10");

        assertEquals(429, result.getCode());
        verify(checkinTxService, never()).doExtraTx(any(), anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("doExtra - 抢锁成功委托事务体并透传目标日期，最后释放锁")
    void testDoExtraDelegatesAndUnlocks() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        ResponseResult txResult = ResponseResult.okResult("ok");
        when(checkinTxService.doExtraTx(userId, "2026-09-10")).thenReturn(txResult);

        ResponseResult result = checkinService.doExtra(userId, "2026-09-10");

        assertSame(txResult, result);
        verify(checkinTxService).doExtraTx(userId, "2026-09-10");
        verify(redisTemplate).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("doExtra - 事务体抛异常 → unlock 仍执行，异常上抛")
    void testDoExtraTxThrowsStillUnlocks() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        doThrow(new IllegalStateException("db down")).when(checkinTxService).doExtraTx(eq(userId), anyString());

        assertThrows(IllegalStateException.class, () -> checkinService.doExtra(userId, "2026-09-10"));

        verify(redisTemplate).delete(LOCK_KEY);
    }

    // ==================== getStatus / getTodayStatus / getContinuousCheckinDays（只读，不受拆分影响） ====================

    @Test
    @DisplayName("getStatus - 今日未签到且无资产无用户信息时返回默认值")
    void testGetStatusDefault() {
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(userAssetsMapper.selectById(userId)).thenReturn(null);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);
        when(userClient.getBasicInfo(userId)).thenReturn(null);
        when(levelClient.getUserLevelInfo(userId)).thenReturn(null);

        ResponseResult result = checkinService.getStatus(userId);

        assertEquals(200, result.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(false, data.get("todaySigned"));
        assertEquals(0, data.get("continuousDays"));
        assertEquals(0, data.get("totalOre"));
    }

    @Test
    @DisplayName("getTodayStatus - 今日未签到返回未签到状态")
    void testGetTodayStatusNotSigned() {
        when(signRecordMapper.selectCount(any())).thenReturn(0L);
        when(userAssetsMapper.selectById(userId)).thenReturn(null);
        when(userCheckinStateMapper.selectById(userId)).thenReturn(null);

        ResponseResult result = checkinService.getTodayStatus(userId);

        assertEquals(200, result.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(false, data.get("isSignedIn"));
        assertEquals(0, data.get("consecutiveDays"));
        assertEquals(0, data.get("patchCardCount"));
    }

    @Test
    @DisplayName("getContinuousCheckinDays - 今日未签到返回连续天数")
    void testGetContinuousCheckinDaysNotSigned() {
        when(signRecordMapper.selectCount(any())).thenReturn(0L);

        ResponseResult result = checkinService.getContinuousCheckinDays(userId);

        assertEquals(200, result.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, data.get("continuousDays"));
    }

    @Test
    @DisplayName("getContinuousCheckinDays - 今日已签到连续天数+1")
    void testGetContinuousCheckinDaysSigned() {
        when(signRecordMapper.selectCount(any())).thenReturn(1L);

        ResponseResult result = checkinService.getContinuousCheckinDays(userId);

        assertEquals(200, result.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, data.get("continuousDays"));
    }
}
