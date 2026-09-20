package com.zhuri.coding.content.service.tip.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.tip.ApArticleTipOrderMapper;
import com.zhuri.coding.content.mapper.tip.ApArticleTipRecordMapper;
import com.zhuri.coding.content.service.pay.AlipayService;
import com.zhuri.coding.content.service.payment.PaymentRewardService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleTipOrder;
import com.zhuri.coding.model.article.pojos.ApArticleTipRecord;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TipServiceImpl 单元测试（文章打赏：下单/支付页/回调/汇总/列表/收益）
 *
 * 纯 @Service 类，@InjectMocks 注入各 mapper；@Value payBaseUrl 用 ReflectionTestUtils 注入。
 * 覆盖：
 * - createOrder 参数校验/文章校验/自打赏校验/下单成功；
 * - getPayPage 订单不存在/状态异常/正常生成；
 * - handleNotify 非成功/订单不存在/状态异常/金额不匹配/合法回调成功，含用户信息获取降级；
 * - getTipSummary/getTipList/getMyRevenue 汇总与分页；
 * - 金额校验 verifyAmount 各分支。
 */
class TipServiceImplTest {

    @Mock
    private ApArticleTipOrderMapper tipOrderMapper;
    @Mock
    private ApArticleTipRecordMapper tipRecordMapper;
    @Mock
    private ApArticleMapper articleMapper;
    @Mock
    private AlipayService alipayService;
    @Mock
    private IUserClient userClient;
    @Mock
    private PaymentRewardService paymentRewardService;

    @InjectMocks
    private TipServiceImpl tipService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(tipService, "payBaseUrl", "http://gw");
        // 预热 MybatisPlus 实体表元数据，使 lambda 包装器（如通知幂等 CAS 条件更新、文章汇总增量）自足，不依赖 Spring 上下文
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticleTipOrder.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticle.class);
    }

    private ApArticle article(Long authorId, boolean deleted) {
        ApArticle a = new ApArticle();
        a.setId(500L);
        a.setAuthorId(authorId);
        // isDeleted 字段为 Boolean，@Data 生成器名不固定，用反射写入保证删除态
        ReflectionTestUtils.setField(a, "isDeleted", deleted);
        // 默认非空，避免 getTipSummary 走空分支
        a.setTipCount(3);
        a.setTipAmount(new BigDecimal("6.66"));
        return a;
    }

    private ApArticleTipOrder order(int status) {
        ApArticleTipOrder o = new ApArticleTipOrder();
        o.setOrderNo("T123");
        o.setUserId(10);
        o.setArticleId(500L);
        o.setAuthorId(99);
        o.setAmount(new BigDecimal("5.00"));
        o.setStatus(status);
        return o;
    }

    // ---------- createOrder ----------
    @Test
    @DisplayName("createOrder 参数缺失返回 PARAM_INVALID")
    void createOrderMissingParam() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                tipService.createOrder(null, new BigDecimal("5"), null, 1L).getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                tipService.createOrder(500L, new BigDecimal("5"), null, null).getCode());
    }

    @Test
    @DisplayName("createOrder 金额越界返回错误")
    void createOrderAmountOutOfRange() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                tipService.createOrder(500L, new BigDecimal("0.5"), null, 1L).getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                tipService.createOrder(500L, new BigDecimal("10001"), null, 1L).getCode());
    }

    @Test
    @DisplayName("createOrder 文章不存在或已删除或作者缺失")
    void createOrderArticleInvalid() {
        when(articleMapper.selectById(500L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                tipService.createOrder(500L, new BigDecimal("5"), null, 1L).getCode());

        ApArticle deleted = article(99L, true);
        when(articleMapper.selectById(500L)).thenReturn(deleted);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                tipService.createOrder(500L, new BigDecimal("5"), null, 1L).getCode());

        ApArticle noAuthor = new ApArticle();
        noAuthor.setId(500L);
        when(articleMapper.selectById(500L)).thenReturn(noAuthor);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                tipService.createOrder(500L, new BigDecimal("5"), null, 1L).getCode());
    }

    @Test
    @DisplayName("createOrder 不能打赏自己的文章")
    void createOrderSelf() {
        when(articleMapper.selectById(500L)).thenReturn(article(1L, false));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                tipService.createOrder(500L, new BigDecimal("5"), "谢谢", 1L).getCode());
    }

    @Test
    @DisplayName("createOrder 下单成功生成订单号并返回支付地址")
    void createOrderSuccess() {
        when(articleMapper.selectById(500L)).thenReturn(article(99L, false));
        when(tipOrderMapper.insert(any(ApArticleTipOrder.class))).thenReturn(1);

        ResponseResult r = tipService.createOrder(500L, new BigDecimal("5"), "谢谢", 1L);
        assertEquals(200, r.getCode());
        Map<?, ?> data = (Map<?, ?>) r.getData();
        assertNotNull(data.get("orderNo"));
        assertTrue(((String) data.get("payUrl")).contains("/content/api/v1/tip/pay/page?orderNo="));
        verify(tipOrderMapper).insert(any(ApArticleTipOrder.class));
    }

    // ---------- getPayPage ----------
    @Test
    @DisplayName("getPayPage 订单不存在与状态异常返回提示页")
    void getPayPageInvalid() {
        when(tipOrderMapper.selectOne(any())).thenReturn(null);
        assertTrue(tipService.getPayPage("T1").contains("订单不存在"));

        when(tipOrderMapper.selectOne(any())).thenReturn(order(ApArticleTipOrder.Status.PAID.getCode()));
        assertTrue(tipService.getPayPage("T1").contains("订单状态异常"));
    }

    @Test
    @DisplayName("getPayPage 调用支付宝生成支付页")
    void getPayPageOk() {
        when(tipOrderMapper.selectOne(any())).thenReturn(order(ApArticleTipOrder.Status.PENDING.getCode()));
        when(alipayService.generatePayPage(any(), any(), any(), any(), any())).thenReturn("<form>pay</form>");
        String page = tipService.getPayPage("T1");
        assertTrue(page.contains("<form>"));
        // generatePayPage 的 orderNo 使用方法入参，而非订单冗余的 orderNo
        verify(alipayService).generatePayPage(eq("T1"), any(), eq("5.00"), any(), any());
    }

    // ---------- handleNotify ----------
    @Test
    @DisplayName("handleNotify 非成功状态直接返回 false")
    void handleNotifyNotSuccess() {
        assertFalse(tipService.handleNotify("tn", "T1", "5", "WAIT_BUYER_PAY"));
    }

    @Test
    @DisplayName("handleNotify 订单不存在返回 false")
    void handleNotifyOrderMissing() {
        when(tipOrderMapper.selectOne(any())).thenReturn(null);
        assertFalse(tipService.handleNotify("tn", "T1", "5", "TRADE_SUCCESS"));
    }

    @Test
    @DisplayName("handleNotify 订单状态异常幂等返回 true")
    void handleNotifyOrderWrongStatus() {
        when(tipOrderMapper.selectOne(any())).thenReturn(order(ApArticleTipOrder.Status.PAID.getCode()));
        assertTrue(tipService.handleNotify("tn", "T1", "5", "TRADE_SUCCESS"));
    }

    @Test
    @DisplayName("handleNotify 金额不匹配返回 false")
    void handleNotifyAmountMismatch() {
        when(tipOrderMapper.selectOne(any())).thenReturn(order(ApArticleTipOrder.Status.PENDING.getCode()));
        assertFalse(tipService.handleNotify("tn", "T1", "9.99", "TRADE_SUCCESS"));
    }

    @Test
    @DisplayName("handleNotify 回调金额为空或非法返回 false")
    void handleNotifyAmountInvalid() {
        when(tipOrderMapper.selectOne(any())).thenReturn(order(ApArticleTipOrder.Status.PENDING.getCode()));
        assertFalse(tipService.handleNotify("tn", "T1", "", "TRADE_SUCCESS"));
        assertFalse(tipService.handleNotify("tn", "T1", "abc", "TRADE_SUCCESS"));
    }

    @Test
    @DisplayName("handleNotify 合法回调完成入账与流水与汇总")
    void handleNotifyOk() {
        when(tipOrderMapper.selectOne(any())).thenReturn(order(ApArticleTipOrder.Status.PENDING.getCode()));
        when(tipOrderMapper.update(any(), any())).thenReturn(1); // CAS 抢占 PENDING→PAID
        when(userClient.getBasicInfo(anyLong())).thenReturn(
                ResponseResult.okResult(Map.of("nickname", "赏主", "avatar", "a.png")));
        when(tipRecordMapper.insert(any(ApArticleTipRecord.class))).thenReturn(1);
        when(articleMapper.update(any(), any())).thenReturn(1);

        boolean ok = tipService.handleNotify("TN", "T123", "5", "TRADE_SUCCESS");
        assertTrue(ok);
        verify(tipOrderMapper).update(any(), any()); // 条件更新抢占
        verify(tipRecordMapper).insert(any(ApArticleTipRecord.class));
        verify(articleMapper).update(any(), any());
        verify(paymentRewardService).onArticleRewardSuccess(anyLong(), any(), any(), eq("T123"));
    }

    @Test
    @DisplayName("handleNotify 用户信息获取异常时降级为空昵称头像")
    void handleNotifyUserClientException() {
        when(tipOrderMapper.selectOne(any())).thenReturn(order(ApArticleTipOrder.Status.PENDING.getCode()));
        when(tipOrderMapper.update(any(), any())).thenReturn(1); // CAS 抢占成功
        when(userClient.getBasicInfo(anyLong())).thenThrow(new RuntimeException("down"));
        when(tipRecordMapper.insert(any(ApArticleTipRecord.class))).thenReturn(1);
        when(articleMapper.update(any(), any())).thenReturn(1);
        doThrow(new RuntimeException("reward down")).when(paymentRewardService)
                .onArticleRewardSuccess(anyLong(), any(), any(), any());

        assertTrue(tipService.handleNotify("TN", "T123", "5", "TRADE_SUCCESS"));
        verify(paymentRewardService).onArticleRewardSuccess(anyLong(), any(), any(), any());
    }

    // ---------- getTipSummary / getTipList / getMyRevenue ----------
    @Test
    @DisplayName("getTipSummary 汇总计数与金额")
    void getTipSummary() {
        when(articleMapper.selectById(500L)).thenReturn(article(99L, false));
        Map<?, ?> data = (Map<?, ?>) tipService.getTipSummary(500L).getData();
        assertEquals(3, ((Number) data.get("tipCount")).intValue());
        assertEquals(0, new BigDecimal("6.66").compareTo(new BigDecimal(data.get("tipAmount").toString())));
    }

    @Test
    @DisplayName("getTipSummary 文章为空返回 0 兜底")
    void getTipSummaryNullArticle() {
        when(articleMapper.selectById(500L)).thenReturn(null);
        Map<?, ?> data = (Map<?, ?>) tipService.getTipSummary(500L).getData();
        assertEquals(0, ((Number) data.get("tipCount")).intValue());
    }

    @Test
    @DisplayName("getTipList 分页兜底与结果返回")
    void getTipList() {
        ApArticleTipRecord rec = new ApArticleTipRecord();
        rec.setArticleId(500L);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ApArticleTipRecord> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        page.setRecords(List.of(rec));
        page.setTotal(1);
        page.setCurrent(1);
        page.setSize(20);
        when(tipRecordMapper.selectPage(any(), any())).thenReturn(page);

        Map<?, ?> data = (Map<?, ?>) tipService.getTipList(500L, null, 999).getData();
        assertEquals(1L, ((Number) data.get("total")).longValue());
        assertEquals(20, ((Number) data.get("size")).intValue()); // size 被限制到 50 以内的兜底逻辑
    }

    @Test
    @DisplayName("getMyRevenue 汇总已支付订单金额")
    void getMyRevenue() {
        ApArticleTipOrder o1 = order(ApArticleTipOrder.Status.PAID.getCode());
        o1.setAmount(new BigDecimal("10"));
        ApArticleTipOrder o2 = order(ApArticleTipOrder.Status.PAID.getCode());
        o2.setAmount(new BigDecimal("2.5"));
        when(tipOrderMapper.selectList(any())).thenReturn(List.of(o1, o2));

        Map<?, ?> data = (Map<?, ?>) tipService.getMyRevenue(99L).getData();
        assertEquals(0, new BigDecimal("12.5").compareTo(new BigDecimal(data.get("totalAmount").toString())));
        assertEquals(2, ((Number) data.get("totalCount")).intValue());
    }
}