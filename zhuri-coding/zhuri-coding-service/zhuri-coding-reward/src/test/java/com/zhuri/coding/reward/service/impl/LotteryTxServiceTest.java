package com.zhuri.coding.reward.service.impl;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.reward.entity.LotteryBroadcastMessage;
import com.zhuri.coding.reward.entity.LotteryDailyState;
import com.zhuri.coding.reward.entity.LotteryDrawRecord;
import com.zhuri.coding.reward.entity.LotteryPhysicalOrder;
import com.zhuri.coding.reward.entity.LotteryPrizePool;
import com.zhuri.coding.reward.entity.UserAssets;
import com.zhuri.coding.reward.mapper.LotteryBroadcastMessageMapper;
import com.zhuri.coding.reward.mapper.LotteryDailyStateMapper;
import com.zhuri.coding.reward.mapper.LotteryDrawRecordMapper;
import com.zhuri.coding.reward.mapper.LotteryPhysicalOrderMapper;
import com.zhuri.coding.reward.mapper.LotteryPrizePoolMapper;
import com.zhuri.coding.reward.mapper.UserAssetsMapper;
import com.zhuri.coding.reward.service.VirtualAssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LotteryTxService（抽奖事务体内核）单元测试 —— P0-6 修复。
 *
 * <p>承接原 LotteryServiceImplTest 的业务断言 + 新增 P0-6 专项：
 * <ol>
 *   <li>空奖池 → 400 且不消耗任何资源（不扣矿、不写记录），绝不 get(0) 越界；</li>
 *   <li>免费次数条件占用（markFreeUsed 原子 SQL），占用失败不回滚按付费继续（纵深防御分支）；</li>
 *   <li>每日次数原子累加（incrDrawCount），不再 updateById 整行读改写；</li>
 *   <li>当日状态并发首建撞唯一键 → 降级原子更新路径；</li>
 *   <li>实物收货地址条件更新抢占 status=1→2，并发双提交只成功一次。</li>
 * </ol>
 * 锁编排语义（429/finally unlock）见 {@link LotteryServiceImplTest}。
 */
class LotteryTxServiceTest {

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
    private VirtualAssetService virtualAssetService;

    @InjectMocks
    private LotteryTxService lotteryTxService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // claimPhysicalTx 的 LambdaUpdateWrapper 运行时解析 lambda 需要实体 TableInfo 缓存
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                LotteryPhysicalOrder.class);
    }

    private LotteryPrizePool orePrize() {
        LotteryPrizePool p = new LotteryPrizePool();
        p.setId("p1");
        p.setName("矿石");
        p.setType(1);
        p.setProbability(new BigDecimal("1.0"));
        p.setMinOre(500);
        p.setMaxOre(500);
        p.setStatus(1);
        p.setIsPhysical(false);
        return p;
    }

    /** 未解锁奖品（unlockRequiredDraws 远大于当前次数） */
    private LotteryPrizePool lockedPrize() {
        LotteryPrizePool p = orePrize();
        p.setId("p2");
        p.setName("未解锁大奖");
        p.setUnlockRequiredDraws(99);
        return p;
    }

    private LotteryPrizePool physicalPrize() {
        LotteryPrizePool p = new LotteryPrizePool();
        p.setId("p9");
        p.setName("实体徽章");
        p.setType(3);
        p.setProbability(new BigDecimal("1.0"));   // 概率=1.0，保证抽中它（rand<1 恒选中）
        p.setMinOre(0);
        p.setMaxOre(0);
        p.setStatus(1);
        p.setIsPhysical(true);
        return p;
    }

    // ==================== P0-6 专项：空奖池 ====================

    @Test
    @DisplayName("drawTx - 空奖池返回400且不消耗任何资源（不扣矿/不写记录/不越界）")
    void testDrawEmptyPoolRejected() {
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        UserAssets assets = new UserAssets();
        assets.setOreBalance(10000);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.emptyList());

        ResponseResult result = lotteryTxService.drawTx(userId, "single", false);

        assertEquals(400, result.getCode());
        // 关键：一分钱不花、一条记录不写
        verify(userAssetsMapper, never()).deductOreBalance(any(), anyInt());
        verify(userAssetsMapper, never()).addOreBalance(any(), anyInt());
        verify(drawRecordMapper, never()).insert(any(LotteryDrawRecord.class));
        verify(dailyStateMapper, never()).insert(any(LotteryDailyState.class));
    }

    @Test
    @DisplayName("randomDraw - 有效集为空(全部未解锁) → 兜底矿石，绝不越界或发未解锁奖品")
    void testRandomDrawAllLockedFallbackOre() {
        LotteryPrizePool fallback = lotteryTxService.randomDraw(
                Collections.singletonList(orePrize()), 0);   // orePrize 未配 unlock → 实际有效
        assertNotNull(fallback);

        // 全部未解锁：lockedPrize 需要 99 次，当前 0 次 → 有效集空 → 兜底矿石（pool 里还有 orePrize）
        LotteryPrizePool locked = lockedPrize();
        LotteryPrizePool ore = orePrize();
        LotteryPrizePool picked = lotteryTxService.randomDraw(java.util.Arrays.asList(locked, ore), 0);
        assertNotNull(picked);
        assertEquals(1, picked.getType());
        assertEquals("p1", picked.getId());   // 兜底拿到矿石，而不是未解锁的 p2
    }

    @Test
    @DisplayName("drawTx - 全池无矿石且全未解锁 → 显式回滚（IllegalStateException），不静默越界")
    void testDrawNoOreFallbackRollsBack() {
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        UserAssets assets = new UserAssets();
        assets.setOreBalance(10000);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(lockedPrize()));

        // lockedPrize 未配矿石兜底且被解锁过滤掉 → randomDraw 返回 null → 事务回滚
        // 注意：lockedPrize type=1 是矿石但被 unlock 过滤；fallbackOrePrize 在 pool 中找不到 type=1 可用？
        // lockedPrize 本身 type=1，fallbackOrePrize 只在「有效集为空」时从全池找 type=1 → 能找到
        // → 真正触发回滚需要：有效集空 + 全池无 type=1 → 构造全虚拟奖品且未解锁
        LotteryPrizePool virtualLocked = lockedPrize();
        virtualLocked.setType(2);
        virtualLocked.setVirtualItemCode("item1");
        virtualLocked.setId("v1");
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(virtualLocked));

        assertThrows(IllegalStateException.class,
                () -> lotteryTxService.drawTx(userId, "single", false));
        // 回滚前绝不动资产
        verify(userAssetsMapper, never()).deductOreBalance(any(), anyInt());
    }

    // ==================== P0-6 专项：免费次数 / 每日次数原子化 ====================

    @Test
    @DisplayName("drawTx - 当日已有记录时免费抽：markFreeUsed 条件占用 + incrDrawCount 原子累加")
    void testDrawFreeWithExistingDailyUsesAtomicSql() {
        LotteryDailyState daily = new LotteryDailyState();
        daily.setId(1L);
        daily.setFreeUsed(false);
        daily.setDrawCount(3);
        when(dailyStateMapper.selectOne(any())).thenReturn(daily);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(orePrize()));
        when(dailyStateMapper.markFreeUsed(eq(userId), any())).thenReturn(1);

        ResponseResult result = lotteryTxService.drawTx(userId, "single", true);

        assertEquals(200, result.getCode());
        verify(dailyStateMapper).markFreeUsed(eq(userId), any());
        verify(dailyStateMapper).incrDrawCount(eq(userId), any(), eq(1));
        // 不再 updateById 整行读改写（P0-6 核心断言）
        verify(dailyStateMapper, never()).updateById(any(LotteryDailyState.class));
    }

    @Test
    @DisplayName("drawTx - 免费次数条件占用失败(疑似并发穿透) → 不回滚按付费继续并返回成功")
    void testDrawFreeOccupiedConcurrentlyStillSucceeds() {
        LotteryDailyState daily = new LotteryDailyState();
        daily.setId(1L);
        daily.setFreeUsed(false);
        daily.setDrawCount(3);
        when(dailyStateMapper.selectOne(any())).thenReturn(daily);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(orePrize()));
        when(dailyStateMapper.markFreeUsed(eq(userId), any())).thenReturn(0);   // 纵深防御分支

        ResponseResult result = lotteryTxService.drawTx(userId, "single", true);

        // 奖品已发放：语义上按"付费抽"继续（免费抽按 0 成本），不回滚用户已到账的矿石
        assertEquals(200, result.getCode());
        verify(userAssetsMapper).addOreBalance(eq(userId), anyInt());
    }

    @Test
    @DisplayName("drawTx - 当日状态并发首建撞唯一键 → 降级原子更新路径")
    void testDrawFirstDailyInsertDuplicateFallsBackToAtomicUpdate() {
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(orePrize()));
        when(dailyStateMapper.insert(any(LotteryDailyState.class)))
                .thenThrow(new DuplicateKeyException("uniq_user_date"));

        ResponseResult result = lotteryTxService.drawTx(userId, "single", true);

        assertEquals(200, result.getCode());
        verify(dailyStateMapper).incrDrawCount(eq(userId), any(), eq(1));
        verify(dailyStateMapper).markFreeUsed(eq(userId), any());
    }

    @Test
    @DisplayName("drawTx - 免费次数今日已用返回400")
    void testDrawFreeAlreadyUsed() {
        LotteryDailyState daily = new LotteryDailyState();
        daily.setId(9L);
        daily.setFreeUsed(true);
        daily.setDrawCount(1);
        when(dailyStateMapper.selectOne(any())).thenReturn(daily);

        ResponseResult result = lotteryTxService.drawTx(userId, "single", true);

        assertEquals(400, result.getCode());
    }

    // ==================== 校验分支（平移自旧测试） ====================

    @Test
    @DisplayName("drawTx - 矿石不足(非免费)返回400")
    void testDrawNotEnoughOre() {
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        UserAssets assets = new UserAssets();
        assets.setOreBalance(50);   // 单抽需要200
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);

        ResponseResult result = lotteryTxService.drawTx(userId, "single", false);

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("drawTx - 十连抽矿石不足2000返回400")
    void testDrawTenNotEnoughOre() {
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        UserAssets assets = new UserAssets();
        assets.setOreBalance(1500);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);

        ResponseResult result = lotteryTxService.drawTx(userId, "ten", false);

        assertEquals(400, result.getCode());
    }

    // ==================== 成功链路（平移自旧测试） ====================

    @Test
    @DisplayName("drawTx - 免费单抽成功落库")
    void testDrawFreeSuccess() {
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(orePrize()));

        ResponseResult result = lotteryTxService.drawTx(userId, "single", true);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, data.get("totalOreCost"));
        assertEquals(1, data.get("todayDrawCountUpdated"));
        verify(userAssetsMapper).insert(any(UserAssets.class));
        verify(userAssetsMapper).addOreBalance(userId, 500);
        verify(userAssetsMapper).updateLuckyValue(userId, 10);
        verify(dailyStateMapper).insert(any(LotteryDailyState.class));
        verify(drawRecordMapper).insert(any(LotteryDrawRecord.class));
    }

    @Test
    @DisplayName("drawTx - 付费单抽原子扣成本矿石，中奖矿石原子累加")
    void testDrawPaidUsesAtomicOreOps() {
        LotteryDailyState daily = new LotteryDailyState();
        daily.setId(1L);
        daily.setFreeUsed(false);
        daily.setDrawCount(3);
        when(dailyStateMapper.selectOne(any())).thenReturn(daily);
        UserAssets assets = new UserAssets();
        assets.setUserId(userId);
        assets.setOreBalance(1000);   // 足够单抽200
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(orePrize()));
        when(userAssetsMapper.deductOreBalance(userId, 200)).thenReturn(1);

        ResponseResult result = lotteryTxService.drawTx(userId, "single", false);

        assertEquals(200, result.getCode());
        verify(userAssetsMapper).deductOreBalance(userId, 200);
        verify(userAssetsMapper).addOreBalance(userId, 500);
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1300, data.get("remainingOre"));   // 1000 - 200 + 500
    }

    // ==================== 实物库存占用 / 售罄降级（平移自旧测试） ====================

    @Test
    @DisplayName("drawTx - 实物限量且库存充足：原子占用成功并发发实物")
    void testDrawPhysicalStockAvailable() {
        LotteryPrizePool physical = physicalPrize();
        physical.setTotalStock(5);
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(physical));
        when(prizePoolMapper.deductStock("p9")).thenReturn(1);   // 占用成功

        ResponseResult result = lotteryTxService.drawTx(userId, "single", true);

        assertEquals(200, result.getCode());
        assertFirstPrizeType(result, "physical");
        verify(prizePoolMapper).deductStock("p9");
        verify(physicalOrderMapper).insert(any(LotteryPhysicalOrder.class));
        verify(broadcastMapper).insert(any(LotteryBroadcastMessage.class));
    }

    @Test
    @DisplayName("drawTx - 实物售罄/并发抢空：降级为矿石兜底且不创建实物订单")
    void testDrawPhysicalStockDepletedDowngradeToOre() {
        LotteryPrizePool physical = physicalPrize();
        physical.setTotalStock(5);
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(physical));
        when(prizePoolMapper.deductStock("p9")).thenReturn(0);   // 库存已被并发抽完

        ResponseResult result = lotteryTxService.drawTx(userId, "single", true);

        assertEquals(200, result.getCode());
        assertFirstPrizeType(result, "ore");
        verify(prizePoolMapper).deductStock("p9");
        verify(physicalOrderMapper, never()).insert(any(LotteryPhysicalOrder.class));
        verify(broadcastMapper, never()).insert(any(LotteryBroadcastMessage.class));
    }

    @Test
    @DisplayName("drawTx - 实物不限量(null/-1)：不占用库存直接发放实物")
    void testDrawPhysicalUnlimitedNoDeduct() {
        LotteryPrizePool physical = physicalPrize();
        physical.setTotalStock(-1);   // 不限量
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(physical));

        ResponseResult result = lotteryTxService.drawTx(userId, "single", true);

        assertEquals(200, result.getCode());
        assertFirstPrizeType(result, "physical");
        verify(prizePoolMapper, never()).deductStock(anyString());
        verify(physicalOrderMapper).insert(any(LotteryPhysicalOrder.class));
    }

    /** 断言返回结果中第一抽的奖品类型为期望的字符串（ore/virtual/physical） */
    private void assertFirstPrizeType(ResponseResult result, String expected) {
        Map<String, Object> data = (Map<String, Object>) result.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertEquals(1, results.size());
        assertEquals(expected, results.get(0).get("prizeType"));
    }

    // ==================== claimPhysicalTx（平移 + 条件更新改造） ====================

    private Map<String, Object> orderBody(Long orderId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("orderId", String.valueOf(orderId));
        body.put("receiverName", "张三");
        body.put("phone", "13800000000");
        body.put("address", "北京市朝阳区xx路");
        return body;
    }

    @Test
    @DisplayName("claimPhysicalTx - 手机号格式不正确返回400")
    void testClaimPhysicalInvalidPhone() {
        Map<String, Object> body = orderBody(1L);
        body.put("phone", "12345");
        ResponseResult result = lotteryTxService.claimPhysicalTx(userId, body);
        assertEquals(400, result.getCode());
        verify(physicalOrderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("claimPhysicalTx - 收货人/地址超长返回400")
    void testClaimPhysicalInvalidNameOrAddress() {
        Map<String, Object> bodyLongName = orderBody(1L);
        bodyLongName.put("receiverName", "张".repeat(21));
        assertEquals(400, lotteryTxService.claimPhysicalTx(userId, bodyLongName).getCode());

        Map<String, Object> bodyLongAddr = orderBody(1L);
        bodyLongAddr.put("address", "路".repeat(121));
        assertEquals(400, lotteryTxService.claimPhysicalTx(userId, bodyLongAddr).getCode());
    }

    @Test
    @DisplayName("claimPhysicalTx - 缺少订单ID返回400")
    void testClaimPhysicalMissingOrderId() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("receiverName", "张三");
        ResponseResult result = lotteryTxService.claimPhysicalTx(userId, body);
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("claimPhysicalTx - 订单不存在返回400")
    void testClaimPhysicalOrderNotFound() {
        when(physicalOrderMapper.selectById(1L)).thenReturn(null);
        Map<String, Object> body = orderBody(1L);
        ResponseResult result = lotteryTxService.claimPhysicalTx(userId, body);
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("claimPhysicalTx - 无权操作他人订单返回400")
    void testClaimPhysicalNotOwned() {
        LotteryPhysicalOrder order = new LotteryPhysicalOrder();
        order.setId(1L);
        order.setUserId(999L);   // 他人订单
        order.setStatus(1);
        when(physicalOrderMapper.selectById(1L)).thenReturn(order);

        Map<String, Object> body = orderBody(1L);
        ResponseResult result = lotteryTxService.claimPhysicalTx(userId, body);

        assertEquals(400, result.getCode());
        verify(physicalOrderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("claimPhysicalTx - 条件更新0行(状态非待填/并发已提交)返回400")
    void testClaimPhysicalConditionalUpdateMiss() {
        LotteryPhysicalOrder order = new LotteryPhysicalOrder();
        order.setId(1L);
        order.setUserId(userId);
        order.setStatus(1);
        when(physicalOrderMapper.selectById(1L)).thenReturn(order);
        when(physicalOrderMapper.update(any(), any())).thenReturn(0);   // 并发已被提交/状态非1

        Map<String, Object> body = orderBody(1L);
        ResponseResult result = lotteryTxService.claimPhysicalTx(userId, body);

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("claimPhysicalTx - 正常提交：条件更新抢占 status=1→2")
    void testClaimPhysicalSuccess() {
        LotteryPhysicalOrder order = new LotteryPhysicalOrder();
        order.setId(1L);
        order.setUserId(userId);
        order.setStatus(1);
        when(physicalOrderMapper.selectById(1L)).thenReturn(order);
        when(physicalOrderMapper.update(any(), any())).thenReturn(1);

        Map<String, Object> body = orderBody(1L);
        ResponseResult result = lotteryTxService.claimPhysicalTx(userId, body);

        assertEquals(200, result.getCode());
        // P0-6：条件更新原子抢占（替代 check-then-set 的 updateById）
        verify(physicalOrderMapper).update(any(), any());
        verify(physicalOrderMapper, never()).updateById(any(LotteryPhysicalOrder.class));
    }
}
