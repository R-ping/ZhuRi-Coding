package com.zhuri.coding.content.service.article.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.content.service.outbox.OutboxDispatcher;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ArticleUpdateNotifyServiceImpl 单元测试（F5 收藏更新提醒投递）
 *
 * 覆盖：分批取收藏者、7 天去重跳过、注销用户过滤、作者跳过、默认文案、
 * 当日聚合文案、文章不可用判死、投递失败释放占位并触发重试。
 */
@ExtendWith(MockitoExtension.class)
class ArticleUpdateNotifyServiceImplTest {

    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApCollectionMapper apCollectionMapper;
    @Mock private INotificationClient notificationClient;
    @Mock private IUserClient userClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private ArticleUpdateNotifyServiceImpl service;

    private static final Long ARTICLE_ID = 100L;
    private static final Long AUTHOR_ID = 5L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ArticleUpdateNotifyServiceImpl(apArticleMapper, apCollectionMapper);
        ReflectionTestUtils.setField(service, "notificationClient", notificationClient);
        ReflectionTestUtils.setField(service, "userClient", userClient);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "dedupDays", 7);
        ReflectionTestUtils.setField(service, "batchSize", 200);
    }

    private ApArticle publishedArticle(String updateNote) {
        ApArticle a = new ApArticle();
        a.setId(ARTICLE_ID);
        a.setAuthorId(AUTHOR_ID);
        a.setTitle("Spring 教程");
        a.setStatus(ApArticle.Status.PUBLISHED.getCode());
        a.setUpdateTime(new Date());
        a.setUpdateNote(updateNote);
        return a;
    }

    /** Redis 可用且全部放行：去重占位成功、当日计数为 1 */
    private void stubRedisAllowing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.DAYS))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L);
    }

    /** 用户有效性校验放行（返回原列表） */
    private void stubAllUsersValid() {
        when(userClient.getValidUserIds(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> ResponseResult.okResult(inv.getArgument(0)));
    }

    private Map<String, Object> captureParams(int invocationIndex) throws Exception {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient, org.mockito.Mockito.atLeastOnce()).createCollectUpdateNotification(captor.capture());
        return captor.getAllValues().get(invocationIndex);
    }

    private Map<String, Object> contentOf(Map<String, Object> params) throws Exception {
        return JSON.readValue(String.valueOf(params.get("content")), new TypeReference<Map<String, Object>>() {});
    }

    @Test
    @DisplayName("实质更新后向收藏者投递单篇提醒：含标题、说明与锚点链接")
    void sendSingleDigest() throws Exception {
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(publishedArticle("适配 Spring Boot 3.5"));
        when(apCollectionMapper.selectUserIdsByArticleId(eq(ARTICLE_ID), anyInt(), anyInt()))
                .thenReturn(List.of(10L, 11L));
        stubAllUsersValid();
        stubRedisAllowing();
        when(notificationClient.createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseResult.okResult(1L));

        service.notifyCollectors(ARTICLE_ID, new Date().getTime());

        verify(notificationClient, times(2)).createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap());
        Map<String, Object> params = captureParams(0);
        assertEquals(10L, params.get("userId"));
        Map<String, Object> content = contentOf(params);
        assertEquals("collect_update", content.get("notification_type"));
        assertEquals("Spring 教程", content.get("title"));
        // message 键与既有系统通知约定一致（前端列表直接渲染该字段）
        assertEquals("你收藏的文章《Spring 教程》有内容更新：适配 Spring Boot 3.5", content.get("message"));
        assertTrue(String.valueOf(content.get("link")).endsWith("#updateNoteBox"));
        assertEquals(1L, ((Number) content.get("count")).longValue());
    }

    @Test
    @DisplayName("更新说明为空时用默认文案，链接落点为时效印章")
    void defaultNoteWhenMissing() throws Exception {
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(publishedArticle(null));
        when(apCollectionMapper.selectUserIdsByArticleId(eq(ARTICLE_ID), anyInt(), anyInt()))
                .thenReturn(List.of(10L));
        stubAllUsersValid();
        stubRedisAllowing();
        when(notificationClient.createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseResult.okResult(1L));

        service.notifyCollectors(ARTICLE_ID, new Date().getTime());

        Map<String, Object> content = contentOf(captureParams(0));
        assertEquals("你收藏的文章《Spring 教程》有内容更新：这篇文章有内容更新", content.get("message"));
        assertTrue(String.valueOf(content.get("link")).endsWith("#updateStamp"));
    }

    @Test
    @DisplayName("当日已有多条更新时合并文案为「你收藏的 N 篇文章有更新」")
    void aggregatedTextWhenSameDayCountGreaterThanOne() throws Exception {
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(publishedArticle("补充了配置样例"));
        when(apCollectionMapper.selectUserIdsByArticleId(eq(ARTICLE_ID), anyInt(), anyInt()))
                .thenReturn(List.of(10L));
        stubAllUsersValid();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.DAYS))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(3L);
        when(notificationClient.createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseResult.okResult(1L));

        service.notifyCollectors(ARTICLE_ID, new Date().getTime());

        Map<String, Object> content = contentOf(captureParams(0));
        assertEquals("你收藏的 3 篇文章有更新，最近一篇《Spring 教程》：补充了配置样例", content.get("message"));
        assertEquals(3L, ((Number) content.get("count")).longValue());
    }

    @Test
    @DisplayName("7 天冷却期内的用户被跳过（去重占位失败）")
    void coolingUsersSkipped() {
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(publishedArticle("x"));
        when(apCollectionMapper.selectUserIdsByArticleId(eq(ARTICLE_ID), anyInt(), anyInt()))
                .thenReturn(List.of(10L));
        stubAllUsersValid();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.DAYS))).thenReturn(false);

        service.notifyCollectors(ARTICLE_ID, new Date().getTime());

        verify(notificationClient, never()).createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("注销用户被过滤、作者本人不提醒")
    void invalidUsersAndAuthorSkipped() throws Exception {
        // 收藏者：10(有效)、11(已注销)、5(作者本人)
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(publishedArticle("x"));
        when(apCollectionMapper.selectUserIdsByArticleId(eq(ARTICLE_ID), anyInt(), anyInt()))
                .thenReturn(List.of(10L, 11L, AUTHOR_ID));
        when(userClient.getValidUserIds(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(ResponseResult.okResult(List.of(10L, AUTHOR_ID)));
        stubRedisAllowing();
        when(notificationClient.createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseResult.okResult(1L));

        service.notifyCollectors(ARTICLE_ID, new Date().getTime());

        verify(notificationClient, times(1)).createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap());
        assertEquals(10L, captureParams(0).get("userId"));
    }

    @Test
    @DisplayName("收藏者按批分页拉取，直到不足一批为止")
    void collectorsPagedInBatches() {
        ReflectionTestUtils.setField(service, "batchSize", 2);
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(publishedArticle("x"));
        when(apCollectionMapper.selectUserIdsByArticleId(eq(ARTICLE_ID), eq(0), eq(2)))
                .thenReturn(List.of(1L, 2L));
        when(apCollectionMapper.selectUserIdsByArticleId(eq(ARTICLE_ID), eq(2), eq(2)))
                .thenReturn(List.of(3L));
        stubAllUsersValid();
        stubRedisAllowing();
        when(notificationClient.createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseResult.okResult(1L));

        service.notifyCollectors(ARTICLE_ID, new Date().getTime());

        verify(apCollectionMapper).selectUserIdsByArticleId(ARTICLE_ID, 0, 2);
        verify(apCollectionMapper).selectUserIdsByArticleId(ARTICLE_ID, 2, 2);
        verify(notificationClient, times(3)).createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("文章不存在/非已发布 → 抛 DeadSignal 直接判死，不浪费重试")
    void articleUnavailableThrowsDeadSignal() {
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(null);
        assertThrows(OutboxDispatcher.DeadSignal.class,
                () -> service.notifyCollectors(ARTICLE_ID, new Date().getTime()));

        ApArticle draft = new ApArticle();
        draft.setId(ARTICLE_ID);
        draft.setStatus(ApArticle.Status.DRAFT.getCode());
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(draft);
        assertThrows(OutboxDispatcher.DeadSignal.class,
                () -> service.notifyCollectors(ARTICLE_ID, new Date().getTime()));
    }

    @Test
    @DisplayName("无实质更新时间的文章不发提醒（兜底）")
    void noUpdateTimeSkips() {
        ApArticle article = publishedArticle("x");
        article.setUpdateTime(null);
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(article);

        service.notifyCollectors(ARTICLE_ID, new Date().getTime());

        verify(notificationClient, never()).createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("投递失败：释放去重占位并抛异常交给 Outbox 重试")
    void sendFailureReleasesClaimAndThrows() {
        when(apArticleMapper.selectById(ARTICLE_ID)).thenReturn(publishedArticle("x"));
        when(apCollectionMapper.selectUserIdsByArticleId(eq(ARTICLE_ID), anyInt(), anyInt()))
                .thenReturn(List.of(10L));
        stubAllUsersValid();
        stubRedisAllowing();
        when(notificationClient.createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ResponseResult.errorResult(500, "通知服务不可用"));

        assertThrows(IllegalStateException.class,
                () -> service.notifyCollectors(ARTICLE_ID, new Date().getTime()));
        // 失败必须释放占位，否则重试时该用户会被误判为"已提醒过"而永久漏发
        verify(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("总开关关闭：不投递任何提醒")
    void disabledNoop() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.notifyCollectors(ARTICLE_ID, new Date().getTime());
        verify(apArticleMapper, never()).selectById(anyLong());
        verify(notificationClient, never()).createCollectUpdateNotification(org.mockito.ArgumentMatchers.anyMap());
    }
}