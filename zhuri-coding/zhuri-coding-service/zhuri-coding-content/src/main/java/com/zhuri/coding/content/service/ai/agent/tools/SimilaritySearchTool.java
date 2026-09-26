package com.zhuri.coding.content.service.ai.agent.tools;

import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 相似文章检索（pgvector 余弦 TopK）—— 由系统侧确定性调用，<b>不暴露给模型</b>。
 *
 * <p>调用方只有两处预检实现：{@code PrecheckWorkflow#runSimilarity}（主路径的显式 DAG 阶段）与
 * {@code PublishAssistantServiceImpl#fillSimilarity}（兜底路径）。二者共用本类的
 * {@link #searchSimilar(String)}，并各自收敛到自己的 {@code applySimilarity} 唯一写入出口。
 *
 * <p><b>为什么不作为工具交给模型</b>：相似度是确定性事实 —— 模型只能"补全得像"，不能"算得对"。
 * 此前本类曾实现 {@code AgentTool}、并通过 {@code @Tool} 包装（AiSimilarityTools）交给主编 Agent 调用，
 * 结果是：它的返回值无论如何都会被系统侧再查一遍覆盖；而模型在未调用该工具时，又会为了满足
 * 输出 schema 的"字段不能缺失"而编造一个格式完全合法、内容虚构的值。
 * 交给它不会增加任何正确性，只会多一次出错的机会。
 */
@Slf4j
@Component
public class SimilaritySearchTool {

    /**
     * 相似度预警阈值 —— <b>全项目唯一来源</b>，两条预检链路都引用本常量。
     *
     * <p>此前工具侧写 0.70、预检侧各写 0.72，同一个口径散在三处且值不一致：
     * 0.70~0.72 之间的文章会被工具返回、又被上层过滤掉。统一取 0.72。
     */
    public static final double ALERT_THRESHOLD = 0.72;

    private static final int TOP_K = 5;
    private static final int SAMPLE_CHARS = 2000;

    /** 相似文章命中：文章 + 相似度（已过滤为已发布） */
    public record SimilarArticle(ApArticle article, double similarity) {
    }

    private final ArticleEmbeddingServiceImpl embeddingService;
    private final ApArticleMapper apArticleMapper;

    public SimilaritySearchTool(ArticleEmbeddingServiceImpl embeddingService, ApArticleMapper apArticleMapper) {
        this.embeddingService = embeddingService;
        this.apArticleMapper = apArticleMapper;
    }

    /**
     * 核心相似检索：正文向量化 -> 余弦 TopK(TopK=5) -> 仅保留已发布文章，按相似度降序。
     *
     * <p>向量化失败或无命中时返回空列表 —— 调用方据此判定"确定性地无高相似"，
     * 并把该结论落成 VO 上显式的 null（而不是"什么都不做"，见各调用方的 applySimilarity）。
     *
     * @param content 待检测正文（内部截断至 2000 字符，避免超长输入拖慢 embedding）
     */
    public List<SimilarArticle> searchSimilar(String content) {
        try {
            if (content == null || content.isBlank()) {
                return List.of();
            }
            String sample = content.length() > SAMPLE_CHARS ? content.substring(0, SAMPLE_CHARS) : content;
            double[] emb = embeddingService.generateEmbedding(sample);
            if (emb == null || emb.length == 0) {
                return List.of();
            }
            List<Object[]> hits = embeddingService.findSimilarArticles(emb, TOP_K, 0);
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            for (Object[] h : hits) {
                ids.add((Long) h[0]);
            }
            List<SimilarArticle> result = new ArrayList<>();
            for (ApArticle a : apArticleMapper.selectBatchIds(ids)) {
                if (a.getStatus() == null || a.getStatus() != Status.PUBLISHED.getCode()) {
                    continue;
                }
                double sim = 0;
                for (Object[] h : hits) {
                    if (a.getId().equals(h[0]) && h.length > 1 && h[1] != null) {
                        sim = (Double) h[1];
                    }
                }
                result.add(new SimilarArticle(a, sim));
            }
            result.sort(Comparator.comparing(SimilarArticle::similarity).reversed());
            return result;
        } catch (Exception e) {
            log.warn("[SimilaritySearchTool] 相似检索失败", e);
            return List.of();
        }
    }

    // ==================== 从检索结果到 VO 的唯二入口（两条预检链路共用） ====================
    //
    // 这两个静态方法刻意放在本类，而不是各链路各写一份：
    // 两条链路（显式 DAG 的 PrecheckWorkflow / 兜底的 PublishAssistantServiceImpl）此前
    // 各写了一份逐字相同的 applySimilarity 与"排除自身 + 取最相似一篇"的循环 ——
    // 靠人记住"改一处别忘了另一处"是约定，只有一份才是结构。

    /**
     * 从候选里挑出唯一一篇预警对象：排除自身 → 取最相似的一篇 → 过阈值。
     *
     * <p>候选已按相似度**降序**，因此排除自身后的第一篇就是最相似的一篇；
     * 它不达阈值即说明其余更低，无需继续遍历。
     *
     * @param hits             检索候选（可能为 null / 空）
     * @param excludeArticleId 需要排除的自身文章 id（可为 null，表示无需排除）
     * @return 达标的那一篇；无候选、候选里只有自己、或最相似一篇未达阈值时返回 null
     */
    public static SimilarArticle pickAlert(List<SimilarArticle> hits, Long excludeArticleId) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        for (SimilarArticle hit : hits) {
            if (excludeArticleId != null && excludeArticleId.equals(hit.article().getId())) {
                continue; // 排除自身
            }
            return hit.similarity() >= ALERT_THRESHOLD ? hit : null;
        }
        return null; // 候选里只有自己
    }

    /**
     * 唯一写入出口：把检索结论落到 VO 上。
     *
     * <p>best 为 null 即"确定性地无高相似"（未命中 / 检索失败 / 未查重）→ **显式清空**。
     * 之所以强调"显式"，是因为这三个字段曾经也收到过模型的填值，而"命中才覆盖"的写法
     * 会让未命中时残留那个值 —— 把"无高相似"表达成一个明确的 null，才能让
     * "该字段只有一个写入出口"成为代码层面可验证的事实。
     */
    public static void applyToVo(AiPrecheckVo vo, SimilarArticle best) {
        if (vo == null) {
            return;
        }
        if (best == null) {
            vo.setSimilarArticleId(null);
            vo.setSimilarTitle(null);
            vo.setSimilarity(null);
            return;
        }
        vo.setSimilarArticleId(best.article().getId());
        vo.setSimilarTitle(best.article().getTitle());
        vo.setSimilarity(Math.round(best.similarity() * 10000) / 10000.0);
    }
}
