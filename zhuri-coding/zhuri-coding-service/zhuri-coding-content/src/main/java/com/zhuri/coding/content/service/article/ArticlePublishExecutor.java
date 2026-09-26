package com.zhuri.coding.content.service.article;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 文章发布执行体 —— 把「置 DB 发布态 → 同步 ES」这段业务逻辑收敛到唯一一处。
 *
 * <p><b>为什么需要它</b>：本次迁移引入了两条并行的执行链路，若各写一份业务逻辑，
 * 就会出现"同一件事两份代码"的漂移风险（改了一处忘另一处，日志与报错栈也会分裂）：
 * <ul>
 *   <li><b>旧链路</b>：{@code article_event} 状态机 + 20s 补偿扫描
 *       （{@code ApArticleEventServiceImpl}）—— 迁移阶段 1 的执行依据；</li>
 *   <li><b>新链路</b>：Outbox 的 {@code ArticlePublishHandler} —— 阶段 2 起接管。</li>
 * </ul>
 *
 * <p><b>职责边界（关键）</b>：本类<b>只做业务，不碰任何消息表</b>。
 * 状态记录是各链路自己的事 —— 旧链路写 {@code article_event}，新链路交给
 * {@code OutboxDispatcher}。所以本类返回 {@link Outcome} 由调用方映射到各自的状态机，
 * 而不是自己决定"该标记成什么状态"。
 *
 * <p>这条边界也是"阶段 1 不能直接委托 {@code executePublish}"的原因：
 * 那个方法内部会写 {@code article_event}，若被 Outbox 侧的 Handler 调用，
 * 两条链路会同时修改同一张表的状态而互相覆盖。
 */
@Component
@Slf4j
public class ArticlePublishExecutor {

    /** 执行结果：由调用方映射到各自的状态机 */
    public enum Outcome {
        /** 执行完成（含"此前已发布"的幂等情形） */
        DONE,
        /** 文章不存在 → 事件应清理 */
        ARTICLE_MISSING,
        /** 文章处于不可发布终态（FAIL 等）→ 事件应清理 + 告警 */
        ARTICLE_NOT_PUBLISHABLE,
        /** 文章仍在待审态 → 暂时性竞态，应稍后重试（不计次） */
        STILL_PENDING
    }

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ISearchClient searchClient;

    /**
     * 执行一次发布：置 DB 发布态（幂等条件更新）→ 同步 ES。
     *
     * <p>ES 同步失败会**抛出异常**（而不是返回某个 Outcome）—— 由调用方决定
     * "这次失败该计次还是不计次"，因为那是链路语义，不是业务语义。
     *
     * @param articleId 文章ID
     * @return 业务结果；ES 同步异常时向外抛出
     */
    public Outcome publish(Long articleId) {
        // ① 置 DB 发布态：条件更新 SUBMIT→PUBLISHED，天然防重复上线
        if (apArticleMapper.markPublishedIfPending(articleId) == 0) {
            Byte status = resolveArticleStatus(articleId);
            if (status == null) {
                log.error("文章不存在，articleId={}", articleId);
                return Outcome.ARTICLE_MISSING;
            }
            if (status == ApArticle.Status.SUBMIT.getCode()) {
                // 仍处待审态：通常是延迟任务的锚点事务尚未提交这类暂时性竞态
                return Outcome.STILL_PENDING;
            }
            if (status != ApArticle.Status.PUBLISHED.getCode()) {
                log.error("文章处不可发布终态(status={})，articleId={}", status, articleId);
                return Outcome.ARTICLE_NOT_PUBLISHABLE;
            }
            // 已是 PUBLISHED：幂等放行，继续做 ES 同步
        }

        // ② 同步 ES（失败即抛出）
        syncToEs(articleId);
        return Outcome.DONE;
    }

    /** ES 同步：只传 articleId，正文由 search 端反向拉取 */
    public void syncToEs(Long articleId) {
        SearchArticleVo vo = new SearchArticleVo();
        vo.setId(articleId);
        searchClient.syncArticle(vo);
    }

    /** 查文章当前状态；文章不存在或状态为空时返回 null */
    private Byte resolveArticleStatus(Long articleId) {
        ApArticle article = apArticleMapper.selectById(articleId);
        return article == null || article.getStatus() == null ? null : article.getStatus().byteValue();
    }
}
