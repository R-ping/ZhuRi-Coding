package com.zhuri.coding.reward.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.reward.entity.WelfareExchangeOrder;
import com.zhuri.coding.reward.entity.WelfareGoods;
import com.zhuri.coding.reward.mapper.UserAssetsMapper;
import com.zhuri.coding.reward.mapper.WelfareExchangeOrderMapper;
import com.zhuri.coding.reward.mapper.WelfareGoodsMapper;
import com.zhuri.coding.reward.mapper.WelfareStockLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兑换幂等外壳单测。
 *
 * <p>外壳只做三件事：命中幂等号就回放原单、没带号就抢 3 秒短锁、内核抛唯一键冲突时回查再回放。
 * 真正的扣减逻辑见 {@link WelfareExchangeTxServiceTest}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("兑换幂等外壳")
class WelfareServiceImplTest {

    @Mock private WelfareGoodsMapper goodsMapper;
    @Mock private WelfareExchangeOrderMapper exchangeOrderMapper;
    @Mock private WelfareStockLogMapper stockLogMapper;
    @Mock private UserAssetsMapper userAssetsMapper;
    @Mock private WelfareExchangeTxService exchangeTxService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private WelfareServiceImpl welfareService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    private Map<String, Object> body(String goodsId) {
        Map<String, Object> body = new HashMap<>();
        body.put("goodsId", goodsId);
        return body;
    }

    private WelfareExchangeOrder existingOrder() {
        WelfareExchangeOrder order = new WelfareExchangeOrder();
        order.setExchangeId("EX-EXIST");
        order.setUserId(userId);
        order.setGoodsName("虚拟商品");
        order.setIsVirtual(true);
        order.setOreCost(100);
        order.setStatus(2);
        order.setIdempotencyKey("req-1");
        return order;
    }

    @Test
    @DisplayName("带幂等号且已有订单：直接回放原单，不再进入扣减")
    void replaysWhenIdempotencyKeyHit() {
        when(exchangeOrderMapper.selectOne(any())).thenReturn(existingOrder());
        when(exchangeTxService.replayResult(eq(userId), any())).thenReturn(ResponseResult.okResult("replayed"));
        Map<String, Object> req = body("g1");
        req.put("requestId", "req-1");

        ResponseResult result = welfareService.exchange(userId, req);

        assertEquals("replayed", result.getData());
        verify(exchangeTxService, never()).exchangeInTx(any(), any(), anyString());
    }

    @Test
    @DisplayName("没带幂等号：短锁抢不到直接拒绝（挡连点）")
    void rejectsWhenShortLockHeld() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        ResponseResult result = welfareService.exchange(userId, body("g1"));

        assertEquals(400, result.getCode());
        verify(exchangeTxService, never()).exchangeInTx(any(), any(), any());
    }

    @Test
    @DisplayName("没带幂等号：短锁拿到后进入扣减，幂等号传 null")
    void delegatesWhenLockAcquired() {
        when(exchangeTxService.exchangeInTx(eq(userId), any(), isNull()))
                .thenReturn(ResponseResult.okResult("ok"));

        ResponseResult result = welfareService.exchange(userId, body("g1"));

        assertEquals("ok", result.getData());
        verify(exchangeTxService).exchangeInTx(eq(userId), any(), isNull());
    }

    @Test
    @DisplayName("并发同号：内核抛唯一键冲突后回查命中，回放已落地订单")
    void replaysAfterDuplicateKeyConflict() {
        // 入口查询没有；插入冲突后再查才有
        when(exchangeOrderMapper.selectOne(any())).thenReturn(null).thenReturn(existingOrder());
        when(exchangeTxService.exchangeInTx(eq(userId), any(), eq("req-1")))
                .thenThrow(new DuplicateKeyException("uk_user_idem"));
        when(exchangeTxService.replayResult(eq(userId), any())).thenReturn(ResponseResult.okResult("replayed"));
        Map<String, Object> req = body("g1");
        req.put("requestId", "req-1");

        ResponseResult result = welfareService.exchange(userId, req);

        assertEquals("replayed", result.getData());
    }

    @Test
    @DisplayName("并发同号但回查不到：异常继续上抛，不吞成成功")
    void rethrowsWhenConflictButNoOrder() {
        when(exchangeOrderMapper.selectOne(any())).thenReturn(null);
        when(exchangeTxService.exchangeInTx(eq(userId), any(), eq("req-1")))
                .thenThrow(new DuplicateKeyException("uk_user_idem"));
        Map<String, Object> req = body("g1");
        req.put("requestId", "req-1");

        assertThrows(DuplicateKeyException.class, () -> welfareService.exchange(userId, req));
    }

    // ==================== 非兑换链路（原样保留） ====================

    @Test
    @DisplayName("getGoodsDetail - 商品不存在返回400")
    void testGetGoodsDetailNotFound() {
        when(goodsMapper.selectById("g1")).thenReturn(null);
        ResponseResult result = welfareService.getGoodsDetail("g1");
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("getGoodsDetail - 商品存在返回详情")
    void testGetGoodsDetailExists() {
        when(goodsMapper.selectById("g1")).thenReturn(virtualGoods());
        ResponseResult result = welfareService.getGoodsDetail("g1");
        assertEquals(200, result.getCode());
    }

    // ==================== exchange - 校验分支 ====================

    @Test
    @DisplayName("getMyExchanges - 分页返回列表")
    void testGetMyExchanges() {
        WelfareExchangeOrder order = new WelfareExchangeOrder();
        order.setExchangeId("EX1");
        order.setGoodsName("流量卡");
        order.setIsVirtual(true);
        order.setOreCost(100);
        order.setStatus(2);
        order.setCreatedAt(new Date());
        Page<WelfareExchangeOrder> page = new Page<>(1, 20);
        page.setRecords(Collections.singletonList(order));
        when(exchangeOrderMapper.selectPage(any(), any())).thenReturn(page);

        ResponseResult result = welfareService.getMyExchanges(userId, 1, 20, "all");

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<?> list = (List<?>) data.get("list");
        assertEquals(1, list.size());
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals("已完成", first.get("statusText"));
    }

    // ==================== getGoodsList ====================

    @Test
    @DisplayName("getGoodsList - 默认分类分页")
    void testGetGoodsList() {
        WelfareGoods g = virtualGoods();
        Page<WelfareGoods> page = new Page<>(1, 20);
        page.setRecords(Collections.singletonList(g));
        when(goodsMapper.selectPage(any(), any())).thenReturn(page);

        ResponseResult result = welfareService.getGoodsList(1, 1, 20);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<?> list = (List<?>) data.get("list");
        assertEquals(1, list.size());
    }


    private WelfareGoods virtualGoods() {
        WelfareGoods g = new WelfareGoods();
        g.setId("g1");
        g.setName("流量卡");
        g.setStatus(1);
        g.setStock(10);
        g.setOrePrice(100);
        g.setIsVirtual(true);
        g.setVirtualCodeTemplate("VC-{timestamp}-{rand}");
        return g;
    }
}
