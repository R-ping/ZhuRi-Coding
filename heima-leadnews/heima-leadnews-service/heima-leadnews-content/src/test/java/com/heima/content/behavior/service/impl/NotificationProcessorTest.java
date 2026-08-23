package com.heima.content.behavior.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.apis.notification.INotificationClient;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.pins.pojos.ApPins;
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

    @Test
    @DisplayName("COMMENT_ARTICLE - 发送评论通知，参数与 content 正确")
    void testCommentArticleNotification() throws Exception {
        when(apArticleMapper.selectById(456L)).thenReturn(article("机器学习实战"));

        processor.postProcess(commentArticleContext(),
            BehaviorResult.success(BehaviorType.COMMENT_ARTICLE));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> params = captor.getValue();
        // 顶层参数
        assertEquals(789L, params.get("userId"));
        assertEquals(1, params.get("type"));
        assertEquals("456", params.get("sourceId"));
        // content 内字段
        Map<String, Object> content = objectMapper.readValue(params.get("content").toString(), Map.class);
        assertEquals("comment", content.get("notification_type"));
        assertEquals("评论了你的作品", content.get("action_type"));
        assertEquals("article", content.get("target_type")); // targetType==1 -> article
        assertEquals(456, content.get("target_id"));
        assertEquals(99, content.get("comment_id"));
        assertEquals("机器学习实战", content.get("target_title"));
        // trigger_user
        Map<String, Object> triggerUser = (Map<String, Object>) content.get("trigger_user");
        assertEquals("张三", triggerUser.get("name"));
        assertEquals("avatar.png", triggerUser.get("avatar"));
    }

    @Test
    @DisplayName("COMMENT_ARTICLE - 长评论超出截断长度时带省略号")
    void testCommentContentTruncated() throws Exception {
        BehaviorContext context = new BehaviorContext(BehaviorType.COMMENT_ARTICLE, 100)
            .withTarget(1, 456L)
            .withTargetUser(789)
            .withExtra("commentId", 99L)
            // 25 个字符，超过 20 上限
            .withExtra("commentContent", "abcdefghijklmnopqrstuvwxyz");

        processor.postProcess(context, BehaviorResult.success(BehaviorType.COMMENT_ARTICLE));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> content = objectMapper
            .readValue(captor.getValue().get("content").toString(), Map.class);
        // 前 20 个字符 + "..."
        assertTrue(content.get("message").toString().startsWith("abcdefghijklmnopqrst"));
        assertTrue(content.get("message").toString().endsWith("..."));
        assertEquals(23, content.get("message").toString().length());
    }

    @Test
    @DisplayName("COMMENT_PIN - 沸点评论 target_type 为 pin")
    void testCommentPinNotification() throws Exception {
        when(apPinsMapper.selectById(456L)).thenReturn(pin("沸点内容样本"));

        BehaviorContext context = new BehaviorContext(BehaviorType.COMMENT_PIN, 100)
            .withTarget(2, 456L)
            .withTargetUser(789)
            .withExtra("commentId", 99L)
            .withExtra("commentContent", "不错");

        processor.postProcess(context, BehaviorResult.success(BehaviorType.COMMENT_PIN));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> content = objectMapper
            .readValue(captor.getValue().get("content").toString(), Map.class);
        assertEquals("pin", content.get("target_type"));
        assertEquals("沸点内容样本", content.get("target_title"));
        assertEquals(1, captor.getValue().get("type"));
    }

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

    @Test
    @DisplayName("点赞/评论通知并发起时校验不依赖 result 数据")
    void testResultDataNotUsed() throws Exception {
        when(apArticleMapper.selectById(456L)).thenReturn(article("结果无关标题"));
        BehaviorContext context = commentArticleContext();
        // result.getData() 为空也照常发送（通知不依赖 result 数据）
        BehaviorResult result = BehaviorResult.success(BehaviorType.COMMENT_ARTICLE);
        assertNotNull(result.getData());
        processor.postProcess(context, result);
        verify(notificationClient).createNotification(any());
    }

    // ==================== 解析标题兜底 ====================

    @Test
    @DisplayName("article 查询返回 null 时标题兜底为空字符串")
    void testArticleNullTitle() throws Exception {
        when(apArticleMapper.selectById(456L)).thenReturn(null);

        processor.postProcess(commentArticleContext(),
            BehaviorResult.success(BehaviorType.COMMENT_ARTICLE));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> content = objectMapper
            .readValue(captor.getValue().get("content").toString(), Map.class);
        assertEquals("", content.get("target_title"));
    }

    @Test
    @DisplayName("解析目标标题抛异常时兜底为空字符串")
    void testResolveTitleException() throws Exception {
        when(apArticleMapper.selectById(456L)).thenThrow(new RuntimeException("db down"));

        processor.postProcess(commentArticleContext(),
            BehaviorResult.success(BehaviorType.COMMENT_ARTICLE));

        // 标题解析异常不影响通知本身发送
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> content = objectMapper
            .readValue(captor.getValue().get("content").toString(), Map.class);
        assertEquals("", content.get("target_title"));
    }

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