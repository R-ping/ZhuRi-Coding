package com.zhuri.coding.content.service.article;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessage;
import com.zhuri.coding.content.utils.MarkdownUtils;
import com.zhuri.coding.content.utils.TextChunker;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 文章向量新鲜度处理：判断某篇文章的向量是否还对得上当前正文，对不上就重算。
 *
 * <p><b>两道入口共用同一套判定</b>：
 * <ul>
 *   <li><b>实时</b>——文章修订生效后登记 {@link #refreshAfterChange(Long)} 事件，事务提交后重放，
 *       让「编辑 → 可检索」的延迟从小时级压到一次派发周期（5 秒）+ 两次 embedding 调用；</li>
 *   <li><b>巡检</b>——每日全量与每 10 分钟轮转各扫一批，兜住事件丢失、批量改库、
 *       历史遗留这些实时链路看不到的情况；指纹一致就跳过，不会重复烧 embedding 额度。</li>
 * </ul>
 *
 * <p><b>为什么用正文指纹而不是「更新时间变了就重算」</b>：正文在 {@code ap_article_content}
 * 这张从表里，而文章表没有可靠的「正文最后修改时间」（{@code update_time} 只在实质更新时刷新，
 * 点赞、计数这些也会碰别的列）。指纹是内容本身的版本号：改了才算过期，没改就跳过，
 * 不需要维护任何时间列，也不受「同一个时间戳里改了两次」影响。
 */
@Slf4j
@Component
public class ArticleEmbeddingRefresher {

    /** 事件类型：随修订事务一起登记，提交后由派发器重放 */
    public static final String EVENT_TYPE = "ARTICLE_EMBEDDING_REFRESH";

    /** 文章级向量只取正文前 2000 字符：长文的重心在分块向量上，整篇只需要一个粗定位 */
    private static final int ARTICLE_EMBED_MAX_CHARS = 2000;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleContentMapper apArticleContentMapper;

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    /**
     * 修订生效后的向量刷新入口（本地消息表事件）。
     *
     * <p><b>语义（重要）</b>：标注 {@link LocalMessage} 后<b>调用 ≠ 执行</b>——
     * 在业务事务里调用只会把 {@code articleId} 存进本地消息表，与正文改动同事务提交；
     * 真正重算发生在提交之后，由派发器反射调回。所以正文没提交成功时不会白算一次向量。
     *
     * <p><b>失败重试 3 次</b>：判据是「死信之后有没有兜底」—— 有，10 分钟一轮的指纹巡检会再发现它。
     * 重试预算给满 5 次只会让一批已经过期的重算长期占着派发队列。
     *
     * @param articleId 文章ID
     */
    @LocalMessage(eventType = EVENT_TYPE, key = "'article_embedding_refresh:' + #a0", maxRetries = 3)
    public void refreshAfterChange(Long articleId) {
        Outcome outcome = refreshIfStale(articleId);
        if (outcome.changed()) {
            log.info("[EmbeddingRefresh] 修订后向量已同步, articleId={}, 新增={}, 重算={}, 分块={}",
                    articleId, outcome.articleWritten, outcome.articleRefreshed, outcome.chunksWritten);
        } else {
            log.debug("[EmbeddingRefresh] 修订后向量无需变更（正文指纹一致）, articleId={}", articleId);
        }
    }

    /**
     * 按 id 刷新：内部读一次文章，判断不可见态时直接跳过。
     *
     * <p>失败向上抛（读库异常、embedding 调用失败），由调用方决定是重试还是记日志跳过。
     */
    public Outcome refreshIfStale(Long articleId) {
        if (articleId == null) {
            return new Outcome();
        }
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null) {
            return new Outcome();
        }
        return refreshIfStale(article);
    }

    /**
     * 核心判定：读正文 → 比指纹 → 缺的补、旧的算、一致的跳过。
     *
     * <p>只有「已发布且未被删除」的文章需要向量：草稿/审核中还没上线，下架与删除的清理
     * 由残留清理任务负责，这里不抢它的活。
     */
    public Outcome refreshIfStale(ApArticle article) {
        Outcome outcome = new Outcome();
        if (article == null || article.getId() == null
                || article.isDeletedArticle() || !article.isPublished()) {
            return outcome;
        }
        Long articleId = article.getId();

        ApArticleContent content = apArticleContentMapper.selectOne(
                new LambdaQueryWrapper<ApArticleContent>()
                        .eq(ApArticleContent::getArticleId, articleId)
                        .last("LIMIT 1"));
        if (content == null || content.getContent() == null || content.getContent().isBlank()) {
            // 无正文（数据异常）→ 不动，避免误删已有向量
            return outcome;
        }
        String currentHash = ArticleEmbeddingServiceImpl.contentHash(content.getContent());
        if (currentHash == null) {
            return outcome;
        }

        ArticleEmbeddingServiceImpl.EmbeddingMeta vecMeta = embeddingService.getEmbeddingMeta(articleId);
        ArticleEmbeddingServiceImpl.EmbeddingMeta chunkMeta = embeddingService.getChunksMeta(articleId);

        // 已判 AIGC 水文的文章不应留在向量库：覆盖审核链漏掉的两类历史脏数据
        // （低置信度未打标先入库、事后升标；打标与入库的竞态残留）。判据与检索侧过滤口径一致。
        if (article.getIsAigc() != null && article.getIsAigc() == 1) {
            if (vecMeta != null || chunkMeta != null) {
                embeddingService.deleteEmbedding(articleId);
                embeddingService.deleteChunks(articleId);
                log.info("[EmbeddingRefresh] 清理已判水文的残留向量, articleId={}", articleId);
            }
            return outcome;
        }

        boolean vecMissing = vecMeta == null;
        boolean chunksMissing = chunkMeta == null;
        boolean vecStale = vecMissing
                || ArticleEmbeddingServiceImpl.isStale(vecMeta.contentHash, currentHash);
        boolean chunksStale = chunksMissing
                || ArticleEmbeddingServiceImpl.isStale(chunkMeta.contentHash, currentHash);
        if (!vecStale && !chunksStale) {
            return outcome; // 指纹一致，不做无谓的 embedding 调用
        }

        if (vecStale) {
            double[] embedding = embeddingService.generateEmbedding(
                    truncate(content.getContent(), ARTICLE_EMBED_MAX_CHARS));
            if (embedding != null) {
                embeddingService.saveEmbedding(articleId, embedding, currentHash, article.getPublishTime());
                if (vecMissing) {
                    outcome.articleWritten = true;
                } else {
                    outcome.articleRefreshed = true;
                    log.info("[EmbeddingRefresh] 正文已变更，重算文章向量, articleId={}, hash={}",
                            articleId, currentHash.substring(0, 8));
                }
            }
        }
        if (chunksStale) {
            embeddingService.saveChunks(articleId, TextChunker.split(
                    MarkdownUtils.normalizeContent(content.getContent()),
                    TextChunker.DEFAULT_TARGET, TextChunker.DEFAULT_OVERLAP,
                    embeddingService.getChunkMaxPerArticle()), currentHash, article.getPublishTime());
            outcome.chunksWritten = true;
        }
        return outcome;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 单篇处理结果：只用于日志与汇总计数，不参与判定 */
    public static final class Outcome {

        public boolean articleWritten;
        public boolean articleRefreshed;
        public boolean chunksWritten;

        public boolean changed() {
            return articleWritten || articleRefreshed || chunksWritten;
        }
    }
}
