package com.heima.reward.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.entity.UserAssets;
import com.heima.reward.entity.WelfareStockLog;
import com.heima.reward.entity.WelfareExchangeOrder;
import com.heima.reward.entity.WelfareGoods;
import com.heima.reward.mapper.WelfareExchangeOrderMapper;
import com.heima.reward.mapper.WelfareGoodsMapper;
import com.heima.reward.mapper.WelfareStockLogMapper;
import com.heima.reward.mapper.UserAssetsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WelfareServiceImpl 单元测试
 *
 * 核心诉求：
 * 1. 兑换商品的库存/余额/下架/限时多道防线，防止资损或超卖；
 * 2. Redis 预扣负库存与数据库乐观锁失败时的回滚(increment)；
 * 3. 原子扣矿失败(并发被先扣光)时抛异常并回滚 Redis 预扣，保证三资源一致；
 * 4. 虚拟商品即时发货、实物需收货地址的差异校验。
 */
class WelfareServiceImplTest {

    @Mock
    private WelfareGoodsMapper goodsMapper;
    @Mock
    private WelfareExchangeOrderMapper exchangeOrderMapper;
    @Mock
    private WelfareStockLogMapper stockLogMapper;
    @Mock
    private UserAssetsMapper userAssetsMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private WelfareServiceImpl welfareService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
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

    private UserAssets assets() {
        UserAssets a = new UserAssets();
        a.setUserId(userId);
        a.setOreBalance(1000);
        return a;
    }

    // ==================== getGoodsDetail ====================

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
    @DisplayName("exchange - 缺少商品ID返回400")
    void testExchangeMissingGoodsId() {
        Map<String, Object> body = new HashMap<>();
        ResponseResult result = welfareService.exchange(userId, body);
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("exchange - 商品不存在或已下架返回400")
    void testExchangeGoodsOffShelf() {
        WelfareGoods g = virtualGoods();
        g.setStatus(0);
        when(goodsMapper.selectById("g1")).thenReturn(g);
        Map<String, Object> body = new HashMap<>();
        body.put("goodsId", "g1");
        ResponseResult result = welfareService.exchange(userId, body);
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("exchange - 库存0返回400")
    void testExchangeNoStock() {
        WelfareGoods g = virtualGoods();
        g.setStock(0);
        when(goodsMapper.selectById("g1")).thenReturn(g);
        ResponseResult result = welfareService.exchange(userId, body("g1"));
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("exchange - 矿石不足返回400")
    void testExchangeNotEnoughOre() {
        when(goodsMapper.selectById("g1")).thenReturn(virtualGoods());
        UserAssets assets = new UserAssets();
        assets.setOreBalance(10);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);
        ResponseResult result = welfareService.exchange(userId, body("g1"));
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("exchange - 实物商品缺收货地址返回400")
    void testExchangePhysicalMissingAddress() {
        WelfareGoods g = virtualGoods();
        g.setIsVirtual(false);
        when(goodsMapper.selectById("g1")).thenReturn(g);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets());
        Map<String, Object> body = new HashMap<>();
        body.put("goodsId", "g1");
        ResponseResult result = welfareService.exchange(userId, body);
        assertEquals(400, result.getCode());
    }

    // ==================== exchange - Redis/DB 库存防线 ====================

    @Test
    @DisplayName("exchange - Redis预扣库存为负返回400并回滚")
    void testExchangeRedisNegativeStock() {
        when(goodsMapper.selectById("g1")).thenReturn(virtualGoods());
        when(userAssetsMapper.selectById(userId)).thenReturn(assets());
        when(valueOperations.decrement(anyString())).thenReturn(-1L);

        ResponseResult result = welfareService.exchange(userId, body("g1"));

        assertEquals(400, result.getCode());
        verify(valueOperations).increment(anyString());
    }

    @Test
    @DisplayName("exchange - 数据库乐观锁扣库存失败返回400并回滚Redis")
    void testExchangeDbUpdateFail() {
        when(goodsMapper.selectById("g1")).thenReturn(virtualGoods());
        when(userAssetsMapper.selectById(userId)).thenReturn(assets());
        when(valueOperations.decrement(anyString())).thenReturn(50L);
        when(goodsMapper.updateStock(anyString())).thenReturn(0);

        ResponseResult result = welfareService.exchange(userId, body("g1"));

        assertEquals(400, result.getCode());
        verify(valueOperations).increment(anyString());
        verify(exchangeOrderMapper, never()).insert(any(WelfareExchangeOrder.class));
    }

    // ==================== exchange - 原子扣矿 & Redis 回滚 ====================

    @Test
    @DisplayName("exchange - 原子扣矿失败(并发下被先扣光)抛异常并回滚Redis预扣")
    void testExchangeAtomicOreDeductFailRollsBackRedis() {
        // 预检查余额充足（1000>=100），仅原子扣减阶段失败，模拟并发下被其它请求先扣光
        when(goodsMapper.selectById("g1")).thenReturn(virtualGoods());
        when(userAssetsMapper.selectById(userId)).thenReturn(assets());
        when(valueOperations.decrement(anyString())).thenReturn(50L);   // Redis 预扣成功
        when(goodsMapper.updateStock(anyString())).thenReturn(1);       // DB 库存扣减成功
        when(userAssetsMapper.deductOreBalance(userId, 100)).thenReturn(0); // 原子扣矿失败

        // 原子扣矿返回0 -> 抛 IllegalStateException 触发事务回滚
        assertThrows(IllegalStateException.class,
                () -> welfareService.exchange(userId, body("g1")));

        // catch 中必须回滚 Redis 预扣，保证 DB/Redis 库存与矿石余额三者回滚一致
        verify(valueOperations).increment(anyString());
        // 扣矿失败时不允许产生订单明细与库存流水
        verify(exchangeOrderMapper, never()).insert(any(WelfareExchangeOrder.class));
        verify(stockLogMapper, never()).insert(any(WelfareStockLog.class));
    }

    // ==================== exchange - 虚拟成功 ====================

    @Test
    @DisplayName("exchange - 虚拟商品兑换成功并出具兑换码")
    void testExchangeVirtualSuccess() {
        when(goodsMapper.selectById("g1")).thenReturn(virtualGoods());
        when(userAssetsMapper.selectById(userId)).thenReturn(assets());
        when(valueOperations.decrement(anyString())).thenReturn(50L);
        when(goodsMapper.updateStock(anyString())).thenReturn(1);
        when(userAssetsMapper.deductOreBalance(userId, 100)).thenReturn(1);

        ResponseResult result = welfareService.exchange(userId, body("g1"));

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals("completed", data.get("orderStatus"));
        assertTrue(((String) data.get("virtualCode")).startsWith("VC-"));
        assertEquals(900, data.get("remainingOre"));
        verify(exchangeOrderMapper).insert(any(WelfareExchangeOrder.class));
        verify(stockLogMapper).insert(any(WelfareStockLog.class));
        // exchanged_count 已在 updateStock 的 SQL 中原子累加，goods 不应再被额外的 updateById 回写
        verify(goodsMapper, never()).updateById(any(WelfareGoods.class));
        // 原子扣矿只调用一次，且金额为商品矿石价
        verify(userAssetsMapper).deductOreBalance(userId, 100);
    }

    // ==================== getMyExchanges ====================

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

    private Map<String, Object> body(String goodsId) {
        Map<String, Object> body = new HashMap<>();
        body.put("goodsId", goodsId);
        return body;
    }
}