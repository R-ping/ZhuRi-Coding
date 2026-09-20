package com.zhuri.coding.content.behavior.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorResult;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.pins.pojos.ApPins;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationProcessor 单元测试（站内信通知后置处理器）
 *
 * 覆盖 postProcess(context, result) 的全部 if/switch 分支：
 * - client/目标用户缺失时跳过通知；
 * - 评论/点赞/收藏/关注各行为是否正确触发通知，以及默认分支（浏览等）不触发；
 * - 各发送方法通过 ArgumentCaptor 断言 createNotification 的 userId/type/sourceId 与 content 内字段；
 * - 解析目标标题（文章 title / 沸点 content）及异常兜底。
 */
@ExtendWith(MockitoExtension.class)
class NotificationProcessorTest {

    @Mock
    private INotificationClient notificationClient;
    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApPinsMapper apPinsMapper;

    @InjectMocks
    private NotificationProcessor processor;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 未登录的操作用户信息 */
    private BehaviorContext baseContext() {
        return new BehaviorContext(BehaviorType.COMMENT_ARTICLE, 100);
    }

    /** 构造一个评论文章的完整上下文（含用户信息、评论内容、评论ID） */
    private BehaviorContext commentArticleContext() {
        return new BehaviorContext(BehaviorType.COMMENT_ARTICLE, 100)
            .withTarget(1, 456L)
            .withTargetUser(789)
            .withUserInfo("张三", "avatar.png")
            .withExtra("commentId", 99L)
            .withExtra("commentContent", "这是一条很长的评论内容，用于验证截断逻辑");
    }

    /** 反射置空 notificationClient，模拟 Feign 客户端不可用的场景 */
    private void clearNotificationClient() throws Exception {
        Field f = NotificationProcessor.class.getDeclaredField("notificationClient");
        f.setAccessible(true);
        f.set(processor, null);
    }

    // ==================== 直接返回的分支 ====================

    @Test
    @DisplayName("notificationClient 不可用时不发送任何通知")
    void testSkipWhenClientNull() throws Exception {
        clearNotificationClient();
        BehaviorContext context = commentArticleContext();
        processor.postProcess(context, BehaviorResult.success(BehaviorType.COMMENT_ARTICLE));
        verify(notificationClient, never()).createNotification(any());
    }

    @Test
    @DisplayName("targetUserId 为 null 时不发送通知")
    void testSkipWhenTargetUserNull() {
        BehaviorContext context = baseContext()
            .withTarget(1, 456L)
            .withExtra("commentId", 99L)
            .withExtra("commentContent", "你好");
        // 未设置目标用户
        processor.postProcess(context, BehaviorResult.success(BehaviorType.COMMENT_ARTICLE));
        verify(notificationClient, never()).createNotification(any());
    }

    // ==================== 评论通知 ====================

    // 注：评论通知已统一由业务成功链路发送（文章→审核通过、沸点→评论创建成功），
    // NotificationProcessor 不再发送任何评论通知，故相关单测已移除；
    // 行为事件总线仅保留点赞/收藏/关注的互动通知。

    // ==================== 点赞通知 ====================

    @Test
    @DisplayName("LIKE_ARTICLE - 发送点赞通知")
    void testLikeArticleNotification() throws Exception {
        when(apArticleMapper.selectById(456L)).thenReturn(article("点赞的目标文章"));

        BehaviorContext context = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 100)
            .withTarget(1, 456L)
            .withTargetUser(789)
            .withUserInfo("李四", "l4.png");

        processor.postProcess(context, BehaviorResult.success(BehaviorType.LIKE_ARTICLE));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> params = captor.getValue();
        assertEquals(789L, params.get("userId"));
        assertEquals(2, params.get("type")); // 赞/收藏通知
        assertEquals("456", params.get("sourceId"));
        Map<String, Object> content = objectMapper.readValue(params.get("content").toString(), Map.class);
        assertEquals("digg", content.get("notification_type"));
        assertEquals("赞了你的作品", content.get("action_type"));
        assertEquals("article", content.get("target_type"));
        assertEquals("点赞的目标文章", content.get("target_title"));
    }

    @Test
    @DisplayName("LIKE_PIN - 沸点点赞 target_type 为 pin")
    void testLikePinNotification() throws Exception {
        when(apPinsMapper.selectById(456L)).thenReturn(pin("沸点 999"));

        BehaviorContext context = new BehaviorContext(BehaviorType.LIKE_PIN, 100)
            .withTarget(2, 456L)
            .withTargetUser(789);

        processor.postProcess(context, BehaviorResult.success(BehaviorType.LIKE_PIN));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> content = objectMapper
            .readValue(captor.getValue().get("content").toString(), Map.class);
        assertEquals("pin", content.get("target_type"));
        assertEquals("沸点 999", content.get("target_title"));
    }

    // ==================== 收藏通知 ====================

    @Test
    @DisplayName("COLLECT_ARTICLE - 收藏文章 target_type 为 article")
    void testCollectArticleNotification() throws Exception {
        when(apArticleMapper.selectById(456L)).thenReturn(article("收藏的文章"));

        BehaviorContext context = new BehaviorContext(BehaviorType.COLLECT_ARTICLE, 100)
            .withTarget(1, 456L)
            .withTargetUser(789);

        processor.postProcess(context, BehaviorResult.success(BehaviorType.COLLECT_ARTICLE));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> params = captor.getValue();
        assertEquals(789L, params.get("userId"));
        assertEquals(2, params.get("type"));
        Map<String, Object> content = objectMapper.readValue(params.get("content").toString(), Map.class);
        assertEquals("收藏了你的作品", content.get("action_type"));
        assertEquals("article", content.get("target_type"));
        assertEquals("收藏的文章", content.get("target_title"));
    }

    @Test
    @DisplayName("COLLECT_ARTICLE - 收藏专栏（targetType!=1）target_type 为 column")
    void testCollectColumnNotification() throws Exception {
        when(apPinsMapper.selectById(456L)).thenReturn(pin("专栏沸点标题"));

        BehaviorContext context = new BehaviorContext(BehaviorType.COLLECT_ARTICLE, 100)
            .withTarget(5, 456L) // 5 = 专栏
            .withTargetUser(789);

        processor.postProcess(context, BehaviorResult.success(BehaviorType.COLLECT_ARTICLE));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> content = objectMapper
            .readValue(captor.getValue().get("content").toString(), Map.class);
        assertEquals("column", content.get("target_type"));
    }

    // ==================== 关注通知 ====================

    @Test
    @DisplayName("FOLLOW_USER - 发送粉丝通知并更新未读计数")
    void testFollowNotification() throws Exception {
        BehaviorContext context = new BehaviorContext(BehaviorType.FOLLOW_USER, 100)
            .withTarget(3, 789L)
            .withTargetUser(789)
            .withUserInfo("王五", "w5.png");

        processor.postProcess(context, BehaviorResult.success(BehaviorType.FOLLOW_USER));

        // createNotification
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> params = captor.getValue();
        assertEquals(789L, params.get("userId"));
        assertEquals(3, params.get("type")); // 粉丝通知
        assertEquals("100", params.get("sourceId")); // 来源为操作用户ID
        Map<String, Object> content = objectMapper.readValue(params.get("content").toString(), Map.class);
        assertEquals("follow", content.get("notification_type"));
        assertEquals("关注了你", content.get("action_type"));
        // 更新未读计数
        verify(notificationClient).incrUnread(789L);
    }

    // ==================== 默认分支 ====================

    @Test
    @DisplayName("默认分支（浏览等其它行为）不发送通知")
    void testDefaultBranchNoNotification() {
        BehaviorContext context = new BehaviorContext(BehaviorType.BROWSE_ARTICLE, 100)
            .withTarget(1, 456L)
            .withTargetUser(789);

        processor.postProcess(context, BehaviorResult.success(BehaviorType.BROWSE_ARTICLE));
        verify(notificationClient, never()).createNotification(any());
    }

    // ==================== 解析标题兜底 ====================

    @Test
    @DisplayName("沸点 content 为 null 时标题兜底为空字符串")
    void testPinNullContent() throws Exception {
        when(apPinsMapper.selectById(456L)).thenReturn(pin(null));

        BehaviorContext context = new BehaviorContext(BehaviorType.LIKE_PIN, 100)
            .withTarget(2, 456L)
            .withTargetUser(789);

        processor.postProcess(context, BehaviorResult.success(BehaviorType.LIKE_PIN));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> content = objectMapper
            .readValue(captor.getValue().get("content").toString(), Map.class);
        assertEquals("", content.get("target_title"));
    }

    @Test
    @DisplayName("getOrder 返回 4（在等级/热度之后执行）")
    void testGetOrder() {
        assertEquals(4, processor.getOrder());
    }

    // ==================== 帮助方法 ====================

    private ApArticle article(String title) {
        ApArticle a = new ApArticle();
        a.setTitle(title);
        return a;
    }

    private ApPins pin(String content) {
        ApPins p = new ApPins();
        p.setContent(content);
        return p;
    }
}