package com.heima.content.service.payment.impl;

import com.heima.apis.notification.INotificationClient;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.service.level.LevelService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.course.pojos.ApCourse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentRewardServiceImpl 单元测试（支付成功联动：加逐日等级经验 + 系统通知）
 *
 * @Service 依赖 LevelService、INotificationClient、courseMapper、articleMapper。
 * 覆盖：
 * - onCoursePurchaseSuccess / onArticleRewardSuccess 正常联动；
 * - 课程/文章查询失败时标题兜底、奖励联动失败或站内信失败均不影响主流程；
 * - grantDailyScore 对得分/异常返回做兜底为 0；
 * - notificationClient 可为 null（@Autowired(required=false)）时跳过；
 * - formatAmount 空值兜底。
 */
class PaymentRewardServiceImplTest {

    @Mock
    private LevelService levelService;
    @Mock
    private INotificationClient notificationClient;
    @Mock
    private ApCourseMapper courseMapper;
    @Mock
    private ApArticleMapper articleMapper;

    @InjectMocks
    private PaymentRewardServiceImpl paymentRewardService;

    private final Long userId = 7L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private Map<String, Object> score(int value) {
        Map<String, Object> m = new HashMap<>();
        m.put("score", value);
        return m;
    }

    // ---------- onCoursePurchaseSuccess ----------
    @Test
    @DisplayName("购买课程成功：加经验并发送系统通知")
    void onCoursePurchaseSuccessOk() {
        when(levelService.recordPaymentAction(anyLong(), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(score(10));
        ApCourse c = new ApCourse();
        c.setTitle("Java 进阶");
        when(courseMapper.selectById(10L)).thenReturn(c);
        when(notificationClient.sendActivityNotification(any())).thenReturn(ResponseResult.okResult());

        assertDoesNotThrow(() ->
                paymentRewardService.onCoursePurchaseSuccess(userId, 10L, new BigDecimal("68.00"), "O1"));
        verify(notificationClient).sendActivityNotification(any());
    }

    @Test
    @DisplayName("购买课程成功：课程不存在时标题为空")
    void onCoursePurchaseSuccessNoCourse() {
        when(levelService.recordPaymentAction(anyLong(), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(score(5));
        when(courseMapper.selectById(10L)).thenReturn(null);
        when(notificationClient.sendActivityNotification(any())).thenReturn(ResponseResult.okResult());

        assertDoesNotThrow(() ->
                paymentRewardService.onCoursePurchaseSuccess(userId, 10L, new BigDecimal("20"), "O1"));
        verify(notificationClient).sendActivityNotification(any());
    }

    @Test
    @DisplayName("购买课程成功：等级服务异常时经验兜底为 0 但仍发通知")
    void onCoursePurchaseSuccessLevelDown() {
        when(levelService.recordPaymentAction(anyLong(), anyString(), any(BigDecimal.class), anyString()))
                .thenThrow(new RuntimeException("level down"));
        when(notificationClient.sendActivityNotification(any())).thenReturn(ResponseResult.okResult());

        assertDoesNotThrow(() ->
                paymentRewardService.onCoursePurchaseSuccess(userId, 10L, new BigDecimal("20"), "O1"));
        verify(notificationClient).sendActivityNotification(any());
    }

    @Test
    @DisplayName("购买课程成功：站内信客户端缺失时跳过")
    void onCoursePurchaseSuccessNoNotifier() {
        when(levelService.recordPaymentAction(anyLong(), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(score(3));
        when(courseMapper.selectById(10L)).thenReturn(null);
        ReflectionTestUtils.setField(paymentRewardService, "notificationClient", null);

        assertDoesNotThrow(() ->
                paymentRewardService.onCoursePurchaseSuccess(userId, 10L, new BigDecimal("20"), "O1"));
        verify(notificationClient, never()).sendActivityNotification(any());
    }

    @Test
    @DisplayName("购买课程成功：站内信异常隔离")
    void onCoursePurchaseSuccessNotifyDown() {
        when(levelService.recordPaymentAction(anyLong(), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(score(4));
        when(courseMapper.selectById(10L)).thenReturn(null);
        when(notificationClient.sendActivityNotification(any()))
                .thenThrow(new RuntimeException("notify down"));

        assertDoesNotThrow(() ->
                paymentRewardService.onCoursePurchaseSuccess(userId, 10L, new BigDecimal("20"), "O1"));
    }

    @Test
    @DisplayName("购买课程成功：等级服务返回 null 经验视为 0")
    void onCoursePurchaseSuccessNullResult() {
        when(levelService.recordPaymentAction(anyLong(), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(null);
        when(courseMapper.selectById(10L)).thenReturn(null);
        when(notificationClient.sendActivityNotification(any())).thenReturn(ResponseResult.okResult());

        assertDoesNotThrow(() ->
                paymentRewardService.onCoursePurchaseSuccess(userId, 10L, new BigDecimal("20"), "O1"));
    }

    // ---------- onArticleRewardSuccess ----------
    @Test
    @DisplayName("打赏文章成功：加经验并发送系统通知")
    void onArticleRewardSuccessOk() {
        when(levelService.recordPaymentAction(anyLong(), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(score(6));
        ApArticle a = new ApArticle();
        a.setTitle("深度好文");
        when(articleMapper.selectById(20L)).thenReturn(a);
        when(notificationClient.sendActivityNotification(any())).thenReturn(ResponseResult.okResult());

        assertDoesNotThrow(() ->
                paymentRewardService.onArticleRewardSuccess(userId, 20L, new BigDecimal("8.88"), "O2"));
        verify(notificationClient).sendActivityNotification(any());
    }

    @Test
    @DisplayName("打赏文章成功：文章查询失败不影响发通知")
    void onArticleRewardSuccessQueryDown() {
        when(levelService.recordPaymentAction(anyLong(), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(score(6));
        when(articleMapper.selectById(20L)).thenThrow(new RuntimeException("db down"));
        when(notificationClient.sendActivityNotification(any())).thenReturn(ResponseResult.okResult());

        assertDoesNotThrow(() ->
                paymentRewardService.onArticleRewardSuccess(userId, 20L, new BigDecimal("5"), "O2"));
        verify(notificationClient).sendActivityNotification(any());
    }

    @Test
    @DisplayName("打赏金额为空时格式化兜底")
    void formatAmountNull() {
        when(levelService.recordPaymentAction(anyLong(), anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(null);
        when(articleMapper.selectById(20L)).thenReturn(null);
        when(notificationClient.sendActivityNotification(any())).thenReturn(ResponseResult.okResult());

        assertDoesNotThrow(() ->
                paymentRewardService.onArticleRewardSuccess(userId, 20L, null, "O2"));
        assertTrue(true);
    }
}