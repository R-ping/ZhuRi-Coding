package com.zhuri.coding.content.service.outbox.handler;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zhuri.coding.content.service.article.ArticlePublishExecutor;
import com.zhuri.coding.content.service.outbox.FailPolicy;
import com.zhuri.coding.content.service.outbox.OutboxDispatcher;
import com.zhuri.coding.content.service.outbox.OutboxHandler;
import com.zhuri.coding.content.service.outbox.RetryWithoutCountingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 文章发布事件处理器 —— 把「发布 → 置 DB 发布态 → 同步 ES」并入统一 Outbox 调度。
 *
 * <p><b>业务逻辑不在本类</b>：它委托给 {@link ArticlePublishExecutor}，与旧链路
 * （{@code article_event} 状态机）**共用同一份实现** —— 避免"同一件事两份代码"的漂移。
 *
 * <p><b>为什么是 Executor 而不是直接调 {@code ApArticleEventService.executePublish}</b>：
 * 那个方法内部会写 {@code article_event} 表的状态。迁移阶段 1 里 `article_event` 仍是执行依据、
 * 旧链路正在运行，若新链路也去写同一张表，两条链路会互相覆盖状态。
 * 所以正确的切分是「**业务逻辑共用、状态记录各管各的**」—— 本类只把
 * {@link ArticlePublishExecutor.Outcome} 翻译成 Outbox 的语义，状态交给 Dispatcher。
 *
 * <p><b>三个策略声明</b>：
 * <ol>
 *   <li>{@link #failPolicy()} = {@code DISCARD}：ES 索引可全量重建，故超限时丢弃而非等人工；</li>
 *   <li>{@link #maxLifetimeMinutes()} = 15：<b>与下一条配套</b>。旧实现里"文章仍待审"是无限重试
 *       且无上限的，若文章永远停在待审态就永不收敛 —— 用时间上限兜底；</li>
 *   <li>抛 {@link RetryWithoutCountingException}：待审态是暂时性竞态，计数会挤占真正的故障重试预算。</li>
 * </ol>
 */
@Component
@Slf4j
public class ArticlePublishHandler implements OutboxHandler {

    /** 事件类型路由键（Dispatcher 按此值路由） */
    public static final String EVENT_TYPE = "ARTICLE_PUBLISH";

    /** 幂等键前缀：配合唯一索引保证「同一篇文章只有一条在途发布事件」 */
    private static final String EVENT_KEY_PREFIX = "article_publish:";

    /** 生命周期上限（分钟）：15 */
    private static final int MAX_LIFETIME_MINUTES = 15;

    /** 发布业务逻辑（与旧链路共用，避免同一件事两份代码） */
    @Autowired
    private ArticlePublishExecutor publishExecutor;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    /** 结果可重建（有全量重建 ES 索引的脚本），故超限走 DISCARD 而非 DEAD */
    @Override
    public FailPolicy failPolicy() {
        return FailPolicy.DISCARD;
    }

    /** 生命周期护栏：15 分钟未完成即判 DEAD —— 防止「不计数重试」退化为无限重试 */
    @Override
    public int maxLifetimeMinutes() {
        return MAX_LIFETIME_MINUTES;
    }

    /** 业务幂等键：`article_publish:{articleId}` */
    public static String eventKey(Long articleId) {
        return EVENT_KEY_PREFIX + articleId;
    }

    /** 事件载荷：只放 articleId（正文由 search 端反向拉取），故无需携带 VO 快照 */
    public static String payload(Long articleId) {
        return "{\"articleId\":" + articleId + "}";
    }

    /**
     * 执行发布事件：委托 {@link ArticlePublishExecutor}，再把业务结果翻译成 Outbox 语义。
     *
     * <p>异常语义（由 Dispatcher 解释）：
     * <ul>
     *   <li>{@link OutboxDispatcher.DeadSignal}：载荷损坏等永久失败 → 直接判死</li>
     *   <li>{@link RetryWithoutCountingException}：暂时性竞态 → 不计数重试（15 分钟上限兜底）</li>
     *   <li>其他异常（含 ES 同步失败）：计次重试 → 超限按 {@link #failPolicy()} 收敛</li>
     *   <li>正常返回：置为 DONE（含"文章不可发布故主动丢弃"的情形，此时已打 ERROR 日志）</li>
     * </ul>
     */
    @Override
    public void execute(String payload) throws Exception {
        Long articleId = parseArticleId(payload);
        if (articleId == null) {
            // 载荷损坏：重试也不会成功，直接判死（不占用重试次数）
            throw new OutboxDispatcher.DeadSignal("发布事件载荷缺少 articleId: " + payload);
        }

        ArticlePublishExecutor.Outcome outcome = publishExecutor.publish(articleId);
        switch (outcome) {
            case DONE -> log.info("文章发布事件执行成功, articleId={}", articleId);
            case STILL_PENDING ->
                // 暂时性竞态：不消耗重试配额（1 分钟后重试），15 分钟未成则由生命周期护栏判死
                throw new RetryWithoutCountingException("文章仍处于待审态, articleId=" + articleId);
            case ARTICLE_MISSING, ARTICLE_NOT_PUBLISHABLE ->
                // Executor 内已打 ERROR 日志；此处正常返回 → 交由 failPolicy=DISCARD 收敛
                log.error("发布事件终止({}), articleId={}", outcome, articleId);
        }
    }

    /** 解析载荷中的 articleId；载荷为空或格式错误时返回 null */
    private Long parseArticleId(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JSONObject node = JSONUtil.parseObj(payload);
            return node.getLong("articleId");
        } catch (Exception e) {
            log.warn("发布事件载荷解析失败: {}", payload, e);
            return null;
        }
    }
}
