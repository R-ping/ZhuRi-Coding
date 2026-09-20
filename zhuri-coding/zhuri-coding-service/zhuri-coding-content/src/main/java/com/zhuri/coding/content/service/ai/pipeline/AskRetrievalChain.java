package com.zhuri.coding.content.service.ai.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.AiPromptRegistry;
import com.zhuri.coding.content.service.ai.HybridRecallService;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.model.article.dtos.AiSourceVo;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RAG 检索管线显式链（Prompt Chaining 落地：向量化 → 混合召回 → 过滤已发布 → LLM Rerank → 组装）。
 *
 * <p>原逻辑耦合在 {@code AiAskServiceImpl.retrieveAndAssemble} 内部，本链把每个阶段拆为具名步骤并
 * 以固定顺序编排（步骤内各自 fail-open），使「改写→检索→生成→校验」全链路中检索段与其它段一样
 * 显式可见、可独立测试、可复用（ask / askFast / streamFastAsk 三路径共用）。
 *
 * <p>约束：链是纯编排，内部仍调用与原来 **相同的 Spring bean**（embedding/hybridRecall/gateway/
 * 注册表/mapper），因此行为与旧实现等价；上游 {@code retrieveAndAssemble} 退化为薄壳并保留
 * 「过滤已发布 + sources 组装」职责边界清晰的契约。
 */
@Slf4j
@Component
public class AskRetrievalChain {

    /** 链输入：检索参数与运行期常量（来自调用方，避免链依赖服务私有常量） */
    public record ChainCtx(String searchQuery, String userQuestion, int topK, boolean doRerank,
                           int recallTopK, int rerankMinCandidates, int contextChars,
                           String rerankFallback) {
    }

    /** 链输出：参考资料文本 + 来源 + 命中数 + 查询向量 + 召回文章（供记忆/画像复用） */
    public record ChainResult(String docsText, List<AiSourceVo> sources, int hits,
                              double[] queryEmbedding, List<ApArticle> articles) {
    }

    /** 中间产物：召回候选 + 向量相似度（供过滤排序与来源相似度口径） */
    private record RecallStage(List<Long> candidates, Map<Long, Double> vectorSims) {
    }

    private final ArticleEmbeddingServiceImpl embeddingService;
    private final HybridRecallService hybridRecallService;
    private final AiLlmGateway llmGateway;
    private final AiPromptRegistry promptRegistry;
    private final ApArticleMapper apArticleMapper;
    private final ApArticleContentMapper contentMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AskRetrievalChain(ArticleEmbeddingServiceImpl embeddingService,
                             HybridRecallService hybridRecallService,
                             AiLlmGateway llmGateway,
                             AiPromptRegistry promptRegistry,
                             ApArticleMapper apArticleMapper,
                             ApArticleContentMapper contentMapper) {
        this.embeddingService = embeddingService;
        this.hybridRecallService = hybridRecallService;
        this.llmGateway = llmGateway;
        this.promptRegistry = promptRegistry;
        this.apArticleMapper = apArticleMapper;
        this.contentMapper = contentMapper;
    }

    /** 链执行入口：按「向量化 → 召回 → 过滤 → 精排 → 组装」五阶段固定顺序运行 */
    public ChainResult run(ChainCtx ctx) {
        // 阶段 1：向量化（失败即整体降级，与旧实现一致）
        double[] queryEmb = embed(ctx.searchQuery());
        if (queryEmb == null || queryEmb.length == 0) {
            log.warn("[AskChain] 问题向量化失败，question={}", truncate(ctx.searchQuery(), 50));
            return null;
        }
        // 阶段 2：混合召回（向量+BM25 → RRF 融合）+ 独有命中相似度补全
        RecallStage recall = recall(ctx, queryEmb);
        if (recall == null || recall.candidates().isEmpty()) {
            return empty(queryEmb);
        }
        int hits = recall.candidates().size(); // hits 口径 = 实际候选数（与原实现一致）
        // 阶段 3：过滤仅已发布 + 按融合顺序排序
        List<ApArticle> published = filterPublished(ctx, recall);
        if (published.isEmpty()) {
            return empty(queryEmb);
        }
        // 阶段 4：LLM Rerank（可选；失败/未启用保留召回序）
        List<ApArticle> articles = rerank(ctx, published);
        List<ApArticle> finalList = articles == null ? published : articles;
        if (finalList.size() > ctx.topK()) {
            finalList = finalList.subList(0, ctx.topK());
        }
        // 阶段 5：装载正文 + 组装【参考资料】与来源（docs 序号必须与 sources 一一对应）
        return assemble(ctx, finalList, recall.vectorSims(), queryEmb, hits);
    }

    /** 阶段 1：查询向量化 */
    private double[] embed(String searchQuery) {
        try {
            return embeddingService.generateEmbedding(searchQuery);
        } catch (Exception e) {
            log.warn("[AskChain] embedding 异常", e);
            return null;
        }
    }

    /** 阶段 2：混合召回 + BM25 独有命中相似度补全（本地余弦，零模型调用） */
    private RecallStage recall(ChainCtx ctx, double[] queryEmb) {
        try {
            HybridRecallService.Recall hybrid = hybridRecallService.recall(ctx.searchQuery(), queryEmb, ctx.recallTopK());
            List<Long> candidates = hybrid.getIds();
            Map<Long, Double> simMap = new LinkedHashMap<>(hybrid.getVectorSims());
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            for (Long id : candidates) {
                if (!simMap.containsKey(id)) {
                    simMap.put(id, localCosine(queryEmb, id));
                }
            }
            return new RecallStage(candidates, simMap);
        } catch (Exception e) {
            log.warn("[AskChain] 混合召回异常", e);
            return null;
        }
    }

    /** 阶段 3：过滤仅已发布 + 非 AIGC，并按 RRF 融合顺序排序 */
    private List<ApArticle> filterPublished(ChainCtx ctx, RecallStage recall) {
        Map<Long, Integer> fusedOrder = new HashMap<>();
        for (int i = 0; i < recall.candidates().size(); i++) {
            fusedOrder.put(recall.candidates().get(i), i);
        }
        try {
            return apArticleMapper.selectBatchIds(recall.candidates()).stream()
                .filter(a -> a.getStatus() != null && a.getStatus() == Status.PUBLISHED.getCode()
                    && (a.getIsAigc() == null || a.getIsAigc() != 1))
                .sorted(Comparator.comparingInt(a -> fusedOrder.getOrDefault(a.getId(), Integer.MAX_VALUE)))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[AskChain] 已发布过滤异常", e);
            return new ArrayList<>();
        }
    }

    /** 阶段 4：LLM Rerank（候选达到阈值时，模型挑选最相关至多 topK 篇；失败回退向量序） */
    private List<ApArticle> rerank(ChainCtx ctx, List<ApArticle> published) {
        if (!ctx.doRerank() || ctx.userQuestion() == null || published.size() < ctx.rerankMinCandidates()) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (ApArticle a : published) {
                sb.append("[").append(i).append("] ").append(a.getTitle()).append("\n");
                i++;
            }
            String sys = prompt("ai_ask_rerank", ctx.rerankFallback()).content;
            String user = sys.replace("{maxN}", String.valueOf(ctx.topK()))
                + "\n\n【问题】" + ctx.userQuestion() + "\n【候选】\n" + sb;
            String raw = llmGateway.generateOrNull(AiFeatures.RERANK,
                "你是信息检索重排器，严格按要求输出 JSON。", user, null, null);
            if (raw == null) {
                return null;
            }
            // 解析 {"selected":[...]}（容忍首尾附加解释文本）
            String body = raw;
            int bs = raw.indexOf('{');
            int es = raw.lastIndexOf('}');
            if (bs >= 0 && es > bs) {
                body = raw.substring(bs, es + 1);
            }
            com.fasterxml.jackson.databind.JsonNode selNode = objectMapper.readTree(body).path("selected");
            List<Long> ids = new ArrayList<>();
            if (selNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : selNode) {
                    if (item.isInt() || item.isLong()) {
                        int idx = item.asInt();
                        if (idx >= 1 && idx <= published.size()) {
                            ids.add(published.get(idx - 1).getId());
                        }
                    }
                }
            }
            if (ids.isEmpty()) {
                log.warn("[AskChain] rerank selected 为空, raw={}", truncate(raw, 200));
                return null;
            }
            Map<Long, ApArticle> byId = new HashMap<>();
            for (ApArticle a : published) {
                byId.put(a.getId(), a);
            }
            List<ApArticle> ordered = new ArrayList<>();
            for (Long id : ids) {
                ApArticle a = byId.get(id);
                if (a != null && ordered.size() < ctx.topK()) {
                    ordered.add(a);
                }
            }
            if (ordered.isEmpty()) {
                return null;
            }
            log.info("[AskChain] rerank 生效: {} 候选 -> {} 篇", published.size(), ordered.size());
            return ordered;
        } catch (Exception e) {
            log.warn("[AskChain] rerank 失败", e);
            return null;
        }
    }

    /** 阶段 5：装载正文并组装【参考资料】文本与来源列表 */
    private ChainResult assemble(ChainCtx ctx, List<ApArticle> articles, Map<Long, Double> simMap,
                                 double[] queryEmb, int hits) {
        List<Long> ids = articles.stream().map(ApArticle::getId).collect(Collectors.toList());
        Map<Long, String> contentMap = new HashMap<>();
        try {
            for (ApArticleContent c : contentMapper.selectList(
                new LambdaQueryWrapper<ApArticleContent>().in(ApArticleContent::getArticleId, ids))) {
                contentMap.put(c.getArticleId(), c.getContent());
            }
        } catch (Exception e) {
            log.warn("[AskChain] 正文装载异常，来源文本可能为空", e);
        }
        StringBuilder docs = new StringBuilder();
        List<AiSourceVo> sources = new ArrayList<>();
        int idx = 1;
        for (ApArticle a : articles) {
            double sim = simMap.getOrDefault(a.getId(), 0d);
            docs.append("[").append(idx).append("] 标题：").append(a.getTitle())
                .append("；作者：").append(a.getAuthorName()).append("\n");
            String body = contentMap.getOrDefault(a.getId(), "");
            docs.append(truncate(body, ctx.contextChars())).append("\n----\n");
            AiSourceVo src = new AiSourceVo();
            src.setArticleId(a.getId());
            src.setTitle(a.getTitle());
            src.setAuthor(a.getAuthorName());
            src.setLikes(a.getLikes());
            src.setSimilarity(Math.round(sim * 10000) / 10000.0);
            sources.add(src);
            idx++;
        }
        return new ChainResult(docs.toString(), sources, hits, queryEmb, articles);
    }

    /** 无命中产物（保持旧实现语义：hits=0） */
    private ChainResult empty(double[] queryEmb) {
        return new ChainResult("", new ArrayList<>(), 0, queryEmb, new ArrayList<>());
    }

    /** BM25 独有命中相似度补全：用已存文章向量本地算余弦（零模型调用） */
    private double localCosine(double[] queryEmb, Long articleId) {
        try {
            com.zhuri.coding.model.article.pojos.ApArticleEmbedding emb = embeddingService.getEmbedding(articleId);
            if (emb == null || emb.getEmbedding() == null || queryEmb == null) {
                return 0d;
            }
            double[] v = emb.getEmbedding();
            int n = Math.min(queryEmb.length, v.length);
            double dot = 0;
            double na = 0;
            double nb = 0;
            for (int i = 0; i < n; i++) {
                dot += queryEmb[i] * v[i];
                na += queryEmb[i] * queryEmb[i];
                nb += v[i] * v[i];
            }
            if (na <= 0 || nb <= 0) {
                return 0d;
            }
            return dot / (Math.sqrt(na) * Math.sqrt(nb));
        } catch (Exception e) {
            return 0d;
        }
    }

    /** Prompt 注册表解析（带 null 兜底） */
    private AiPromptRegistry.ResolvedPrompt prompt(String key, String fallback) {
        if (promptRegistry == null) {
            return new AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
        try {
            return promptRegistry.resolve(key, fallback, null);
        } catch (Exception e) {
            return new AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}