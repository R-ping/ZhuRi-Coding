package com.heima.reward.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.entity.LotteryBroadcastMessage;
import com.heima.reward.entity.LotteryDrawRecord;
import com.heima.reward.mapper.LotteryBroadcastMessageMapper;
import com.heima.reward.mapper.LotteryDailyStateMapper;
import com.heima.reward.mapper.LotteryDrawRecordMapper;
import com.heima.reward.mapper.LotteryPhysicalOrderMapper;
import com.heima.reward.mapper.LotteryPrizePoolMapper;
import com.heima.reward.mapper.UserAssetsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
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
 * LotteryServiceImpl（外层锁编排）单元测试 —— P0-6 修复。
 *
 * <p>拆分后本类只负责「抢锁 → 委托 {@link LotteryTxService}（事务体内核）→ finally 解锁」，
 * 因此本测试只断言<b>编排语义</b>（业务断言已迁至 LotteryTxServiceTest）：
 * <ol>
 *   <li>抢锁失败 → 429 且<b>绝不进入</b>事务体、绝不 unlock（未持有锁）；</li>
 *   <li>抢锁成功 → 委托事务体、结果透传、最后 unlock（unlock 发生在内层事务边界之后）；</li>
 *   <li>事务体抛异常 → unlock 仍执行（finally），异常上抛；</li>
 *   <li>只读路径（getDashboard / getMyPrizes / getBroadcast）不受拆分影响。</li>
 * </ol>
 */
class LotteryServiceImplTest {

    @Mock
    private LotteryPrizePoolMapper prizePoolMapper;
    @Mock
    private LotteryDrawRecordMapper drawRecordMapper;
    @Mock
    private LotteryPhysicalOrderMapper physicalOrderMapper;
    @Mock
    private LotteryDailyStateMapper dailyStateMapper;
    @Mock
    private LotteryBroadcastMessageMapper broadcastMapper;
    @Mock
    private UserAssetsMapper userAssetsMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private LotteryTxService lotteryTxService;

    @InjectMocks
    private LotteryServiceImpl lotteryService;

    private final Long userId = 100L;
    private static final String LOCK_KEY = "lottery:lock:100";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private void lockAcquired() {
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
    }

    private void lockRejected() {
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(false);
    }

    // ==================== draw - 锁编排（P0-6） ====================

    @Test
    @DisplayName("draw - 锁竞争返回429且不进入事务体、不释放锁")
    void testDrawLockBusy() {
        lockRejected();

        ResponseResult result = lotteryService.draw(userId, "single", false);

        assertEquals(429, result.getCode());
        verify(lotteryTxService, never()).drawTx(any(), anyString(), any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("draw - 抢锁成功委托事务体，结果透传并释放锁")
    void testDrawDelegatesAndUnlocks() {
        lockAcquired();
        ResponseResult txResult = ResponseResult.okResult("ok");
        when(lotteryTxService.drawTx(userId, "ten", false)).thenReturn(txResult);

        ResponseResult result = lotteryService.draw(userId, "ten", false);

        assertSame(txResult, result);
        verify(lotteryTxService).drawTx(userId, "ten", false);
        verify(redisTemplate).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("draw - 事务体抛异常 → unlock 仍执行（锁释放在事务边界之后），异常上抛")
    void testDrawTxThrowsStillUnlocks() {
        lockAcquired();
        doThrow(new IllegalStateException("db down")).when(lotteryTxService).drawTx(eq(userId), anyString(), any());

        assertThrows(IllegalStateException.class, () -> lotteryService.draw(userId, "single", false));

        verify(redisTemplate).delete(LOCK_KEY);
    }

    // ==================== claimPhysical - 锁编排（P0-6） ====================

    @Test
    @DisplayName("claimPhysical - 锁竞争返回429且不进入事务体")
    void testClaimPhysicalLockBusy() {
        lockRejected();

        ResponseResult result = lotteryService.claimPhysical(userId, new java.util.HashMap<>());

        assertEquals(429, result.getCode());
        verify(lotteryTxService, never()).claimPhysicalTx(any(), any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("claimPhysical - 抢锁成功委托事务体，结果透传并释放锁")
    void testClaimPhysicalDelegatesAndUnlocks() {
        lockAcquired();
        ResponseResult txResult = ResponseResult.okResult("ok");
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        when(lotteryTxService.claimPhysicalTx(userId, body)).thenReturn(txResult);

        ResponseResult result = lotteryService.claimPhysical(userId, body);

        assertSame(txResult, result);
        verify(lotteryTxService).claimPhysicalTx(userId, body);
        verify(redisTemplate).delete(LOCK_KEY);
    }

    // ==================== 只读路径（不受拆分影响） ====================

    @Test
    @DisplayName("getMyPrizes - 按ore类型过滤分页")
    void testGetMyPrizes() {
        LotteryDrawRecord rec = new LotteryDrawRecord();
        rec.setId(1L);
        rec.setPrizeName("矿石");
        rec.setPrizeType(1);
        rec.setOreAmount(500);
        rec.setCreatedAt(new Date());
        Page<LotteryDrawRecord> page = new Page<>(1, 20);
        page.setRecords(Collections.singletonList(rec));
        when(drawRecordMapper.selectPage(any(), any())).thenReturn(page);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.emptyList());

        ResponseResult result = lotteryService.getMyPrizes(userId, 1, 20, "ore");

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<?> list = (List<?>) data.get("list");
        assertEquals(1, list.size());
    }

    @Test
    @DisplayName("getBroadcast - 返回播报列表")
    void testGetBroadcast() {
        LotteryBroadcastMessage msg = new LotteryBroadcastMessage();
        msg.setUserId(userId);
        msg.setUserNickname(null);   // 触发默认"用户100"
        msg.setPrizeName("实物奖品");
        msg.setCreatedAt(new Date());
        when(broadcastMapper.selectList(any())).thenReturn(Collections.singletonList(msg));

        ResponseResult result = lotteryService.getBroadcast();

        assertEquals(200, result.getCode());
        List<?> list = (List<?>) result.getData();
        assertEquals(1, list.size());
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals("用户100", first.get("user"));
    }

    @Test
    @DisplayName("getDashboard - 无资产无daily返回默认值且免费可用")
    void testGetDashboardDefaults() {
        when(userAssetsMapper.selectById(userId)).thenReturn(null);
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(broadcastMapper.selectList(any())).thenReturn(Collections.emptyList());

        ResponseResult result = lotteryService.getDashboard(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, data.get("oreBalance"));
        assertEquals(true, data.get("freeDrawAvailable"));
        assertEquals(0, data.get("todayDrawCount"));
        assertEquals(Arrays.asList(), data.get("prizePool"));
    }
}
