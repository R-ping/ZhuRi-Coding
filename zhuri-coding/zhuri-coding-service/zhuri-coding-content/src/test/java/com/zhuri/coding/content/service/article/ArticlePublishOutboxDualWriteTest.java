package com.zhuri.coding.content.service.article;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.event.ArticlePublishEventListener;
import com.zhuri.coding.content.mapper.article.ApArticleEventMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.outbox.OutboxEventMapper;
import com.zhuri.coding.content.schedule.listener.RedissonDelayQueue;
import com.zhuri.coding.content.service.outbox.OutboxService;
import com.zhuri.coding.content.service.outbox.handler.ArticlePublishHandler;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ArticleEvent;
import com.zhuri.coding.model.outbox.pojos.OutboxEvent;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * 文章发布「落锚」集成测试 —— 验证迁移阶段 2（切读）后 Outbox 是唯一执行依据。
 *
 * <p><b>为什么必须是集成测试</b>：{@code createArticleEvent} 的落锚是
 * 「业务与消息表同事务提交」，单测只能验证调用了哪个方法，验证不了这三件事：
 * <ol>
 *   <li><b>真实事务里是否真的落库</b>：{@code ApArticleServiceImpl} 带类级 {@code @Transactional}；</li>
 *   <li><b>唯一索引是否真实生效</b>：{@code uk_event_key} 是幂等的物理保证，
 *       只有连真库才会触发 {@code DuplicateKeyException} 走幂等短路；</li>
 *   <li><b>旧表是否真的不再被写</b>：切读的正确性标志就是 {@code article_event} 不再产生新行 ——
 *       这一点断言"表为空"比断言"调用了哪个方法"有力得多。</li>
 * </ol>
 *
 * <p><b>阶段沿革</b>：本测试最初（阶段 1）验证的是「双写」—— 断言
 * {@code article_event(INIT)} 与 {@code ap_outbox_event} 同时落库；阶段 2 切读后
 * 双写的前一半被移除，断言随之翻转为「旧表不再被写、新表正常落锚」。
 *
 * <p><b>隔离策略</b>（沿用 {@code ArticleCommentE2ETest}）：
 * <ul>
 *   <li>{@code @MockBean} 屏蔽 {@link ArticlePublishEventListener}：切读后它已无事件源不会被触发，
 *       此处 mock 属防御性隔离（若将来误恢复发布事件，本测试也不会被异步副作用污染）；</li>
 *   <li>{@code @MockBean} 屏蔽 Redisson，避免启动真实延迟队列消费者线程；</li>
 *   <li>用独立测试文章，{@code @AfterEach} 清理三张表，不污染既有数据。</li>
 * </ul>
 */
@SpringBootTest
class ArticlePublishOutboxDualWriteTest {

    @Autowired
    private ApArticleService apArticleService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleEventMapper apArticleEventMapper;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    /** 防御性隔离异步发布副作用（切读后已无事件源触发它） */
    @MockBean
    private ArticlePublishEventListener articlePublishEventListener;

    /** 用 Mock 替换 Redisson，避免启动真实延迟队列消费者线程 */
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    private RedissonClient redissonClient;

    @MockBean
    private RedissonDelayQueue redissonDelayQueue;

    /** 本次测试创建的文章 id，用于 @AfterEach 精确清理 */
    private Long articleId;

    @AfterEach
    void cleanup() {
        if (articleId == null) {
            return;
        }
        outboxEventMapper.delete(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getEventKey, ArticlePublishHandler.eventKey(articleId)));
        apArticleEventMapper.delete(new LambdaQueryWrapper<ArticleEvent>()
                .eq(ArticleEvent::getArticleId, articleId));
        apArticleMapper.deleteById(articleId);
    }

    @Test
    @DisplayName("切读落锚 - 只写 ap_outbox_event，不再写 article_event")
    void anchoringWritesOutboxOnly() {
        // given：一条处于「待审」态的真实文章（createArticleEvent 要求文章已存在）
        articleId = insertPendingArticle();

        // when
        boolean anchored = apArticleService.createArticleEvent(article(articleId));

        // then ① 切读的正确性标志：旧状态机载体不再产生新行
        assertThat(anchored).isTrue();
        assertThat(selectEvents(articleId))
                .as("阶段 2 切读后 article_event 不应再被写入")
                .isEmpty();

        // then ② Outbox 成为唯一执行依据，幂等键/载荷与 Handler 约定一致
        OutboxEvent outboxEvent = selectOutboxEvent(ArticlePublishHandler.eventKey(articleId));
        assertThat(outboxEvent)
                .as("落锚事件必须与业务同事务落库，eventKey=%s",
                        ArticlePublishHandler.eventKey(articleId))
                .isNotNull();
        assertThat(outboxEvent.getEventType()).isEqualTo(ArticlePublishHandler.EVENT_TYPE);
        assertThat(outboxEvent.getPayload()).isEqualTo(ArticlePublishHandler.payload(articleId));
    }

    @Test
    @DisplayName("切读幂等 - 重复落锚靠 uk_event_key 短路，两次都返回 true 且只有一条事件")
    void anchoringIsIdempotentOnRepeat() {
        articleId = insertPendingArticle();

        // 第一次：正常落锚
        assertThat(apArticleService.createArticleEvent(article(articleId))).isTrue();
        // 第二次：延迟任务重投等场景 → record 命中 uk_event_key → 幂等短路 → 仍返回 true
        // （切读后不再有 article_event 的唯一索引兜底，幂等完全由 uk_event_key 承担）
        assertThat(apArticleService.createArticleEvent(article(articleId))).isTrue();

        assertThat(selectEvents(articleId)).isEmpty();
        assertThat(outboxEventMapper.selectCount(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getEventKey, ArticlePublishHandler.eventKey(articleId))))
                .as("幂等键唯一：重复投递不会产生第二条事件")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("Outbox 幂等短路 - 同 eventKey 重复 record 时靠唯一索引拦截并返回 false")
    void recordIsIdempotentByUniqueKey() {
        articleId = insertPendingArticle();
        String eventKey = ArticlePublishHandler.eventKey(articleId);

        boolean first = outboxService.record(
                eventKey, ArticlePublishHandler.EVENT_TYPE, ArticlePublishHandler.payload(articleId));
        boolean second = outboxService.record(
                eventKey, ArticlePublishHandler.EVENT_TYPE, ArticlePublishHandler.payload(articleId));

        assertThat(first).as("首次写入成功").isTrue();
        assertThat(second).as("重复写入应被唯一索引拦截并幂等短路").isFalse();
        assertThat(outboxEventMapper.selectCount(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getEventKey, eventKey))).isEqualTo(1L);
    }

    /** 插入一条待审(SUBMIT)的测试文章，返回其 id */
    private Long insertPendingArticle() {
        ApArticle article = new ApArticle();
        article.setTitle("outbox落锚验证-临时数据");
        article.setStatus(ApArticle.Status.SUBMIT.getCode());
        article.setCreatedTime(new Date());
        article.setPublishTime(new Date());
        apArticleMapper.insert(article);
        assertThat(article.getId()).as("测试文章应已生成主键").isNotNull();
        return article.getId();
    }

    /** 构造传给 createArticleEvent 的文章对象（只依赖 id 与存在性） */
    private ApArticle article(Long id) {
        ApArticle article = new ApArticle();
        article.setId(id);
        return article;
    }

    private List<ArticleEvent> selectEvents(Long articleId) {
        return apArticleEventMapper.selectList(new LambdaQueryWrapper<ArticleEvent>()
                .eq(ArticleEvent::getArticleId, articleId));
    }

    private OutboxEvent selectOutboxEvent(String eventKey) {
        return outboxEventMapper.selectOne(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getEventKey, eventKey));
    }
}
