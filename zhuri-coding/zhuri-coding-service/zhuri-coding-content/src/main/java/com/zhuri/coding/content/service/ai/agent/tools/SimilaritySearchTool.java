package com.zhuri.coding.content.service.ai.agent.tools;

import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
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
}
