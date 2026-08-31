package com.heima.reward.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.entity.LotteryBroadcastMessage;
import com.heima.reward.entity.LotteryDailyState;
import com.heima.reward.entity.LotteryDrawRecord;
import com.heima.reward.entity.LotteryPhysicalOrder;
import com.heima.reward.entity.LotteryPrizePool;
import com.heima.reward.entity.UserAssets;
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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LotteryServiceImpl 单元测试
 *
 * 核心诉求：
 * 1. 抽奖所需的矿石/免费次数校验，防止资产不足强行抽奖（资损）；
 * 2. 实物奖品领取的归属校验，防止越权领取他人订单；
 * 3. 免费成功抽奖的扣矿石、记录、每日状态落库链路完整；
 * 4. 实物奖品库存占用与售罄降级兜底（限量充足发放/售罄降级矿石/不限量不占用）。
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

    @InjectMocks
    private LotteryServiceImpl lotteryService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
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

    // ==================== draw - 校验分支 ====================

    @Test
    @DisplayName("draw - 免费次数今日已用返回400")
    void testDrawFreeAlreadyUsed() {
        LotteryDailyState daily = new LotteryDailyState();
        daily.setId(9L);
        daily.setFreeUsed(true);
        daily.setDrawCount(1);
        when(dailyStateMapper.selectOne(any())).thenReturn(daily);

        ResponseResult result = lotteryService.draw(userId, "single", true);

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("draw - 矿石不足(非免费)返回400")
    void testDrawNotEnoughOre() {
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        UserAssets assets = new UserAssets();
        assets.setOreBalance(50);   // 单抽需要200
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);

        ResponseResult result = lotteryService.draw(userId, "single", false);

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("draw - 十连抽矿石不足2000返回400")
    void testDrawTenNotEnoughOre() {
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        UserAssets assets = new UserAssets();
        assets.setOreBalance(1500);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);

        ResponseResult result = lotteryService.draw(userId, "ten", false);

        assertEquals(400, result.getCode());
    }

    // ==================== draw - 免费成功 ====================

    @Test
    @DisplayName("draw - 免费单抽成功落库")
    void testDrawFreeSuccess() {
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(orePrize()));

        ResponseResult result = lotteryService.draw(userId, "single", true);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, data.get("totalOreCost"));
        assertEquals(1, data.get("todayDrawCountUpdated"));
        verify(userAssetsMapper).insert(any(UserAssets.class));
        // 矿石奖励用原子累加、幸运值用原子更新，均已废弃读改写 updateById
        verify(userAssetsMapper).addOreBalance(userId, 500);
        verify(userAssetsMapper).updateLuckyValue(userId, 10);
        verify(dailyStateMapper).insert(any(LotteryDailyState.class));
        verify(drawRecordMapper).insert(any(LotteryDrawRecord.class));
    }

    @Test
    @DisplayName("draw - 付费单抽原子扣成本矿石，中奖矿石原子累加")
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
        // addOreBalance 无需 stub，Mockito 返回默认0；仅验证调用

        ResponseResult result = lotteryService.draw(userId, "single", false);

        assertEquals(200, result.getCode());
        // 成本用带余额检查的原子扣减，而非读改写
        verify(userAssetsMapper).deductOreBalance(userId, 200);
        // 抽中矿石奖励用原子累加（min=500,max=500 → oreAmount=500）
        verify(userAssetsMapper).addOreBalance(userId, 500);
        // 资产写回不含矿石（矿石已原子落库），仅更新幸运值；免费净矿石展示正确
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1300, data.get("remainingOre"));   // 1000 - 200 + 500
    }

    // ==================== draw - 实物库存占用 / 售罄降级 ====================

    @Test
    @DisplayName("draw - 实物限量且库存充足：原子占用成功并发发实物")
    void testDrawPhysicalStockAvailable() {
        LotteryPrizePool physical = physicalPrize();
        physical.setTotalStock(5);
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(physical));
        when(prizePoolMapper.deductStock("p9")).thenReturn(1);   // 占用成功

        ResponseResult result = lotteryService.draw(userId, "single", true);

        assertEquals(200, result.getCode());
        assertFirstPrizeType(result, "physical");
        verify(prizePoolMapper).deductStock("p9");
        verify(physicalOrderMapper).insert(any(LotteryPhysicalOrder.class));
        verify(broadcastMapper).insert(any(LotteryBroadcastMessage.class));
    }

    @Test
    @DisplayName("draw - 实物售罄/并发抢空：降级为矿石兜底且不创建实物订单")
    void testDrawPhysicalStockDepletedDowngradeToOre() {
        LotteryPrizePool physical = physicalPrize();
        physical.setTotalStock(5);
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(physical));
        when(prizePoolMapper.deductStock("p9")).thenReturn(0);   // 库存已被并发抽完

        ResponseResult result = lotteryService.draw(userId, "single", true);

        assertEquals(200, result.getCode());
        // 分发结果应为矿石（降级兜底），而非 physical
        assertFirstPrizeType(result, "ore");
        verify(prizePoolMapper).deductStock("p9");
        // 关键：绝不创建实物订单与中奖播报，杜绝"中奖实物却发不出"
        verify(physicalOrderMapper, never()).insert(any(LotteryPhysicalOrder.class));
        verify(broadcastMapper, never()).insert(any(LotteryBroadcastMessage.class));
    }

    @Test
    @DisplayName("draw - 实物不限量(null/-1)：不占用库存直接发放实物")
    void testDrawPhysicalUnlimitedNoDeduct() {
        LotteryPrizePool physical = physicalPrize();
        physical.setTotalStock(-1);   // 不限量
        when(dailyStateMapper.selectOne(any())).thenReturn(null);
        when(prizePoolMapper.selectList(any())).thenReturn(Collections.singletonList(physical));

        ResponseResult result = lotteryService.draw(userId, "single", true);

        assertEquals(200, result.getCode());
        assertFirstPrizeType(result, "physical");
        // 不限量不应发起库存占用
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

    // ==================== claimPhysical ====================

    @Test
    @DisplayName("claimPhysical - 手机号格式不正确返回400")
    void testClaimPhysicalInvalidPhone() {
        Map<String, Object> body = orderBody(1L);
        body.put("phone", "12345");
        ResponseResult result = lotteryService.claimPhysical(userId, body);
        assertEquals(400, result.getCode());
        verify(physicalOrderMapper, never()).updateById(any(LotteryPhysicalOrder.class));
    }

    @Test
    @DisplayName("claimPhysical - 收货人/地址超长或为空返回400")
    void testClaimPhysicalInvalidNameOrAddress() {
        // 收货人超长
        Map<String, Object> bodyLongName = orderBody(1L);
        bodyLongName.put("receiverName", "张".repeat(21));
        assertEquals(400, lotteryService.claimPhysical(userId, bodyLongName).getCode());

        // 地址超长
        Map<String, Object> bodyLongAddr = orderBody(1L);
        bodyLongAddr.put("address", "路".repeat(121));
        assertEquals(400, lotteryService.claimPhysical(userId, bodyLongAddr).getCode());
    }

    @Test
    @DisplayName("claimPhysical - 缺少订单ID返回400")
    void testClaimPhysicalMissingOrderId() {
        Map<String, Object> body = new HashMap<>();
        body.put("receiverName", "张三");
        ResponseResult result = lotteryService.claimPhysical(userId, body);
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("claimPhysical - 订单不存在返回400")
    void testClaimPhysicalOrderNotFound() {
        when(physicalOrderMapper.selectById(1L)).thenReturn(null);
        Map<String, Object> body = orderBody(1L);
        ResponseResult result = lotteryService.claimPhysical(userId, body);
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("claimPhysical - 无权操作他人订单返回400")
    void testClaimPhysicalNotOwned() {
        LotteryPhysicalOrder order = new LotteryPhysicalOrder();
        order.setId(1L);
        order.setUserId(999L);   // 他人订单
        order.setStatus(1);
        when(physicalOrderMapper.selectById(1L)).thenReturn(order);

        Map<String, Object> body = orderBody(1L);
        ResponseResult result = lotteryService.claimPhysical(userId, body);

        assertEquals(400, result.getCode());
        verify(physicalOrderMapper, never()).updateById(order);
    }

    @Test
    @DisplayName("claimPhysical - 订单状态不正确返回400")
    void testClaimPhysicalWrongStatus() {
        LotteryPhysicalOrder order = new LotteryPhysicalOrder();
        order.setId(1L);
        order.setUserId(userId);
        order.setStatus(2);   // 非待填地址
        when(physicalOrderMapper.selectById(1L)).thenReturn(order);

        Map<String, Object> body = orderBody(1L);
        ResponseResult result = lotteryService.claimPhysical(userId, body);

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("claimPhysical - 正常提交地址置为待发货")
    void testClaimPhysicalSuccess() {
        LotteryPhysicalOrder order = new LotteryPhysicalOrder();
        order.setId(1L);
        order.setUserId(userId);
        order.setStatus(1);
        when(physicalOrderMapper.selectById(1L)).thenReturn(order);

        Map<String, Object> body = orderBody(1L);
        ResponseResult result = lotteryService.claimPhysical(userId, body);

        assertEquals(200, result.getCode());
        assertEquals(2, order.getStatus());
        verify(physicalOrderMapper).updateById(order);
    }

    // ==================== getMyPrizes / getBroadcast / getDashboard ====================

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

    private Map<String, Object> orderBody(Long orderId) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", String.valueOf(orderId));
        body.put("receiverName", "张三");
        body.put("phone", "13800000000");
        body.put("address", "北京市朝阳区xx路");
        return body;
    }
}