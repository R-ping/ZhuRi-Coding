package com.zhuri.coding.content.service.downlink;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 把「内容下线」这个跨服务副作用可靠地送达 search 服务。下线的两个入口（运营下架、作者删除）
 * 共用本类，保证两条路径的索引清理是同一套语义。
 *
 * <p><b>为什么必须走本地消息表</b>：ES 索引里少一篇文档，用户在搜索/推荐里就还能点开一个
 * 已经不在线的内容 —— 下线等于没下。登记与下线动作同事务提交，重放负责真正送达，失败自动计次重试。
 *
 * <p><b>送达之后还有两层兜底</b>：search 侧的移除是「先打可见性墓碑、再物理删除」，
 * 墓碑写入即从检索结果里消失，删除失败只留下一条不可见的残档；即便整个调用进了死信，
 * 也有反向对账巡检按窗口比对索引与库，把「索引有、库不可见」的文档清掉。
 *
 * <p><b>为什么 maxRetries 设小（3）</b>：判据是"死信之后有没有自动兜底"—— 这里有反向对账，
 * 死信不是终点。少几次重试、快点进死信，收敛更快，也不会让一批已无意义的调用长期占着派发队列。
 *
 * <p>方法体天然可安全重试：墓碑是幂等的部分更新，ES 删除对不存在的 id 也不报错。
 */
@Slf4j
@Component
public class ArticleIndexRemover {

    /**
     * 事件类型：既是 Outbox 路由键，也是指标维度。
     *
     * <p>取值是**已落库消息的路由契约**，改名会让存量待重放消息找不到处理器，故保持原值不变；
     * 前缀叫 ADMIN 只是历史命名，本类现由运营下架与作者删除共用。
     */
    public static final String EVENT_TYPE = "ADMIN_ARTICLE_INDEX_REMOVE";

    @Autowired
    private ISearchClient searchClient;

    /**
     * 从检索索引中移除一篇文章（下线副作用）。
     *
     * <p><b>语义（重要）</b>：被 {@link LocalMessage} 标注后，<b>调用 ≠ 执行</b>——
     * 在业务事务里调用它只会把 {@code articleId} 存进本地消息表（与下线动作同事务提交），
     * 真正调用 search 服务发生在事务提交之后。必须在事务内、且经由注入的引用调用
     * （同类 {@code this.} 调用不走代理，切面不命中，就没存档）。
     *
     * @param articleId 文章ID
     */
    @LocalMessage(eventType = EVENT_TYPE, key = "'article_index_remove:' + #a0", maxRetries = 3)
    public void removeFromIndex(Long articleId) {
        searchClient.removeArticleIndex(articleId);
        log.info("[ContentOffline] 已从检索索引移除, articleId={}", articleId);
    }
}
