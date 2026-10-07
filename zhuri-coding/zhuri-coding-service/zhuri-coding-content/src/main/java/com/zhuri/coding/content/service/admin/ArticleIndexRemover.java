package com.zhuri.coding.content.service.admin;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 把「内容下线」这个跨服务副作用可靠地送达 search 服务。
 *
 * <p><b>为什么必须走本地消息表</b>：ES 索引里少一篇文档，用户在搜索/推荐里就还能点开一个
 * 已被平台下架的内容 —— 下架等于没下。而索引移除**没有任何自动兜底**：
 * 现有对账巡检（{@code ApArticleMapper#selectPublishedIdsSince}）只补推"已发布但缺索引"的，
 * 不会反向删除"已下架但还在索引里"的。也就是说，这一步失败如果只是记条日志，
 * 那条幽灵文档会一直留在检索结果里，直到有人手工发现。所以走 {@link LocalMessage}：
 * 登记与下架动作同事务提交，重放负责真正送达，失败自动计次重试。
 *
 * <p><b>为什么 maxRetries 用默认值</b>：判据是"死信之后有没有自动兜底"——这里没有，
 * 死信即人工介入。重试本身几乎零成本，多一次就少一次人工，所以不设更小的预算。
 *
 * <p>方法体天然可安全重试：ES 删除对不存在的 id 不报错，删两次与删一次结果相同。
 */
@Slf4j
@Component
public class ArticleIndexRemover {

    /** 事件类型：既是 Outbox 路由键，也是指标维度 */
    public static final String EVENT_TYPE = "ADMIN_ARTICLE_INDEX_REMOVE";

    @Autowired
    private ISearchClient searchClient;

    /**
     * 从检索索引中移除一篇文章（下架副作用）。
     *
     * <p><b>语义（重要）</b>：被 {@link LocalMessage} 标注后，<b>调用 ≠ 执行</b>——
     * 在业务事务里调用它只会把 {@code articleId} 存进本地消息表（与下架动作同事务提交），
     * 真正调用 search 服务发生在事务提交之后。必须在事务内、且经由注入的引用调用
     * （同类 {@code this.} 调用不走代理，切面不命中，就没存档）。
     *
     * @param articleId 文章ID
     */
    @LocalMessage(eventType = EVENT_TYPE, key = "'admin_article_index_remove:' + #a0")
    public void removeFromIndex(Long articleId) {
        searchClient.removeArticleIndex(articleId);
        log.info("[AdminTakeDown] 已从检索索引移除, articleId={}", articleId);
    }
}
