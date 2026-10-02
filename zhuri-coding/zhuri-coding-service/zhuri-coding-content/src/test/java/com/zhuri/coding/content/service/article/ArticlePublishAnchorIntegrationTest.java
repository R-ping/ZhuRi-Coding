package com.zhuri.coding.content.service.article;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.outbox.OutboxEventMapper;
import com.zhuri.coding.content.schedule.listener.RedissonDelayQueue;
import com.zhuri.coding.content.service.outbox.OutboxService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.outbox.pojos.OutboxEvent;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * 文章发布「落锚」集成测试 —— 验证 Outbox 是发布的唯一执行依据。
 *
 * <p><b>为什么必须是集成测试</b>：{@code submitPublish} 的落锚是
 * 「业务与消息表同事务提交」，单测只能验证调用了哪个方法，验证不了这三件事：
 * <ol>
 *   <li><b>真实事务里是否真的落库</b>：{@code ApArticleServiceImpl} 带类级 {@code @Transactional}；</li>
 *   <li><b>唯一索引是否真实生效</b>：{@code uk_event_key} 是幂等的物理保证，
 *       只有连真库才会触发 {@code DuplicateKeyException} 走幂等短路；</li>
 *   <li><b>注解声明的重试预算是否如实落库</b>：{@code maxRetries} 驱动 Dispatcher 的死信判定，
 *       配置没生效等于没有预算。</li>
 * </ol>
 *
 * <p><b>隔离策略</b>（沿用 {@code ArticleCommentE2ETest}）：
 * <ul>
 *   <li>{@code @MockBean} 屏蔽 Redisson，避免启动真实延迟队列消费者线程；</li>
 *   <li>用独立测试文章，{@code @AfterEach} 清理两张表，不污染既有数据。</li>
 * </ul>
 */
@SpringBootTest
class ArticlePublishAnchorIntegrationTest {

    @Autowired
    private ApArticleService apArticleService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

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
                .eq(OutboxEvent::getEventKey, "article_publish:" + articleId));
        apArticleMapper.deleteById(articleId);
    }

    @Test
    @DisplayName("落锚 - 事件与业务同事务落库，按注解声明的重试预算写入")
    void anchoringWritesOutbox() {
        // given：一条处于「待审」态的真实文章（submitPublish 要求文章已存在）
        articleId = insertPendingArticle();

        // when
        boolean anchored = apArticleService.submitPublish(article(articleId));

        // then
        assertThat(anchored).isTrue();
        OutboxEvent outboxEvent = selectOutboxEvent("article_publish:" + articleId);
        assertThat(outboxEvent)
                .as("落锚事件必须与业务同事务落库，eventKey=%s",
                        "article_publish:" + articleId)
                .isNotNull();
        assertThat(outboxEvent.getEventType()).isEqualTo("ARTICLE_PUBLISH");
        assertThat(outboxEvent.getPayload()).isEqualTo("[" + articleId + "]");
        // 注解声明的重试预算：文章同步有对账巡检兜底，3 次足够（支付联动无兜底，仍是默认 5）
        assertThat(outboxEvent.getMaxRetries()).isEqualTo(3);
    }

    @Test
    @DisplayName("落锚幂等 - 重复落锚靠 uk_event_key 短路，两次都返回 true 且只有一条事件")
    void anchoringIsIdempotentOnRepeat() {
        articleId = insertPendingArticle();

        // 第一次：正常落锚
        assertThat(apArticleService.submitPublish(article(articleId))).isTrue();
        // 第二次：延迟任务重投等场景 → record 命中 uk_event_key → 幂等短路 → 仍返回 true
        assertThat(apArticleService.submitPublish(article(articleId))).isTrue();

        assertThat(outboxEventMapper.selectCount(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getEventKey, "article_publish:" + articleId)))
                .as("幂等键唯一：重复投递不会产生第二条事件")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("Outbox 幂等短路 - 同 eventKey 重复 record 时靠唯一索引拦截并返回 false")
    void recordIsIdempotentByUniqueKey() {
        articleId = insertPendingArticle();
        String eventKey = "article_publish:" + articleId;

        boolean first = outboxService.record(
                eventKey, "ARTICLE_PUBLISH", "[" + articleId + "]");
        boolean second = outboxService.record(
                eventKey, "ARTICLE_PUBLISH", "[" + articleId + "]");

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

    /** 构造传给 submitPublish 的文章对象（只依赖 id 与存在性） */
    private ApArticle article(Long id) {
        ApArticle article = new ApArticle();
        article.setId(id);
        return article;
    }

    private OutboxEvent selectOutboxEvent(String eventKey) {
        return outboxEventMapper.selectOne(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getEventKey, eventKey));
    }
}
