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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LotteryServiceImpl 单元测试
 *
 * 核心诉求：
 * 1. 抽奖所需的矿石/免费次数校验，防止资产不足强行抽奖（资损）；
 * 2. 实物奖品领取的归属校验，防止越权领取他人订单；
 * 3. 免费成功抽奖的扣矿石、记录、每日状态落库链路完整。
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
        verify(userAssetsMapper).updateById(any(UserAssets.class));
        verify(dailyStateMapper).insert(any(LotteryDailyState.class));
        verify(drawRecordMapper).insert(any(LotteryDrawRecord.class));
    }

    // ==================== claimPhysical ====================

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