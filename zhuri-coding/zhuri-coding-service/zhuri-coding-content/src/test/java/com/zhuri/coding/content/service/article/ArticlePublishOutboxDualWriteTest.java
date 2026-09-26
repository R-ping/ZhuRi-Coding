package com.zhuri.coding.content.service.article;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.common.constants.ArticleConstants;
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
 * 文章发布「双写」集成测试 —— 迁移阶段 1 的真实环境验证。
 *
 * <p><b>为什么需要它（单测覆盖不到的地方）</b>：阶段 1 的双写段是
 * {@code ApArticleServiceImpl.createArticleEvent} 里的一段 try-catch，
 * 单测只能验证「逻辑被调用」，验证不了这三件事：
 * <ol>
 *   <li><b>真实事务里是否真的落库</b>：{@code createArticleEvent} 所在类带
 *       {@code @Transactional}，双写必须与 {@code article_event} 锚点同事务提交；</li>
 *   <li><b>唯一索引是否真实生效</b>：{@code uk_event_key} 是幂等的物理保证，
 *       只有连真库才会触发 {@code DuplicateKeyException}；</li>
 *   <li><b>eventKey / payload 的取值是否正确</b>：新老两条链路靠它们对齐同一业务事件。</li>
 * </ol>
 *
 * <p><b>隔离策略</b>（沿用 {@code ArticleCommentE2ETest} 的做法）：
 * <ul>
 *   <li>{@code @MockBean} 屏蔽 {@link ArticlePublishEventListener}：本测试只验「落锚 + 双写」，
 *       不验其后的异步置位 + ES 同步（那会改动文章状态、且需要 search 服务）；</li>
 *   <li>{@code @MockBean} 屏蔽 Redisson：避免启动真实延迟队列消费者线程；
 *       发布链路不依赖 Redis，mock 后上下文可正常加载；</li>
 *   <li>用独立测试文章，{@code @AfterEach} 清理 {@code ap_article} /
 *       {@code article_event} / {@code ap_outbox_event}，不污染既有数据。</li>
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

    /** 屏蔽异步发布副作用（置位 + ES 同步）：本测试只关注落锚与双写 */
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
    @DisplayName("双写落库 - 落锚 article_event(INIT) 的同时写入 ap_outbox_event")
    void dualWritePersistsBothRecords() {
        // given：一条处于「待审」态的真实文章（createArticleEvent 要求文章已存在）
        articleId = insertPendingArticle();

        // when
        boolean anchored = apArticleService.createArticleEvent(article(articleId));

        // then ① 老链路：article_event 落锚为 INIT
        assertThat(anchored).isTrue();
        List<ArticleEvent> events = selectEvents(articleId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatus()).isEqualTo(ArticleConstants.EVENT_STATUS_INIT);

        // then ② 新链路：ap_outbox_event 同时写入，且幂等键/载荷与 Handler 约定一致
        OutboxEvent outboxEvent = selectOutboxEvent(ArticlePublishHandler.eventKey(articleId));
        assertThat(outboxEvent)
                .as("双写必须与锚点同事务落库，eventKey=%s", ArticlePublishHandler.eventKey(articleId))
                .isNotNull();
        assertThat(outboxEvent.getEventType()).isEqualTo(ArticlePublishHandler.EVENT_TYPE);
        assertThat(outboxEvent.getPayload()).isEqualTo(ArticlePublishHandler.payload(articleId));
    }

    @Test
    @DisplayName("双写幂等 - 重复落锚被 article_id 唯一索引挡住，不会产生第二条 outbox 事件")
    void dualWriteNotDuplicatedOnSecondAnchoring() {
        articleId = insertPendingArticle();

        // 第一次：正常落锚 + 双写
        assertThat(apArticleService.createArticleEvent(article(articleId))).isTrue();

        // 第二次：article_event.article_id 唯一索引拦截 → insertArticleEvent 抛 DuplicateKeyException
        // 被 createArticleEvent 外层 catch 捕获 → 返回 false，且**双写段不会执行**
        assertThat(apArticleService.createArticleEvent(article(articleId))).isFalse();

        assertThat(selectEvents(articleId)).hasSize(1);
        assertThat(selectOutboxEvent(ArticlePublishHandler.eventKey(articleId))).isNotNull();
        // 幂等键唯一：两处只会有一条
        Long count = outboxEventMapper.selectCount(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getEventKey, ArticlePublishHandler.eventKey(articleId)));
        assertThat(count).isEqualTo(1L);
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
        article.setTitle("outbox双写验证-临时数据");
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
