package com.heima.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.common.bailian.DashScopeClient;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.ai.AiAskService;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.model.article.dtos.AiAnswerVo;
import com.heima.model.article.dtos.AiSourceVo;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleContent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 社区 AI 问答实现（RAG）
 *
 * <p>复用审核链已有的向量基建：DashScope embedding + pgvector(ap_article_embedding) 余弦检索；
 * 生成回答复用 DashScopeClient.callGeneration 通用文本能力。
 */
@Slf4j
@Service
public class AiAskServiceImpl implements AiAskService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 8;
    private static final int MAX_QUESTION_LEN = 200;
    /** 每篇文章提供给模型的正文上限（字符），控制上下文与成本 */
    private static final int CONTEXT_CHARS = 1200;
    /** 宽召回候选数（后续 LLM Rerank 精排到 topK） */
    private static final int RECALL_TOPK = 15;
    /** Query Rewrite 开关（A/B 调优用） */
    private static final boolean REWRITE_ENABLED = true;
    /** LLM Rerank 开关：候选数达到阈值时启用模型精排 */
    private static final boolean RERANK_ENABLED = true;
    private static final int RERANK_MIN_CANDIDATES = 4;

    private static final String REWRITE_PROMPT =
        "你是搜索查询改写器。把用户的口语化问题改写为一个更利于向量检索的简洁技术查询（保留关键实体与限定词，去掉客套语），"
        + "只输出改写后的查询文本本身（≤60 字），不要任何解释。若无需改写，原样输出问题。";

    private static final String RERANK_PROMPT =
        "你是信息检索重排器。给定用户问题与候选文章（[序号] 标题），选出与问题【最相关】的至多 {maxN} 篇。\n"
        + "只输出 JSON：{\"selected\":[序号,...]}（按相关度从高到低），不要任何额外文字。若候选均不相关输出 {\"selected\":[]}。";

    private static final String SYSTEM_PROMPT =
        "你是《逐日 Coding》技术社区的知识助手。请遵守：\n" +
        "1. 只能依据【参考资料】中的文章回答，禁止使用资料外的知识编造；\n" +
        "2. 引用资料时在句末标注来源序号，如 [1][2]；\n" +
        "3. 若资料与问题无关或信息不足，明确回答“社区知识库中暂未找到相关内容”；\n" +
        "4. 用简体中文、条理清晰地回答，控制在 300 字以内；\n" +
        "5. 若【对话历史】中出现指代（如“它/那篇/上面提到”），结合历史理解用户意图，但引用标注仍只来自本轮【参考资料】。";

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleContentMapper contentMapper;

    @Autowired
    private DashScopeClient dashScopeClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AiAnswerVo ask(String question, Integer topK, Boolean fast,
                          java.util.List<java.util.Map<String, String>> history) {
        long start = System.currentTimeMillis();
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > MAX_QUESTION_LEN) {
            return null;
        }
        int k = topK == null ? DEFAULT_TOP_K : Math.max(1, Math.min(topK, MAX_TOP_K));

        // fast 模式：单次向量召回 + 一次生成（跳过 rewrite/rerank，省 2/3 模型调用）
        if (Boolean.TRUE.equals(fast)) {
            return askFast(q, k, start, history);
        }

        // 1. Query Rewrite：口语问题 -> 利于向量检索的查询（失败回退原文）
        String searchQuery = q;
        if (REWRITE_ENABLED) {
            String rewritten = queryRewrite(q);
            if (rewritten != null && !rewritten.isBlank()) {
                searchQuery = rewritten;
                log.info("[AiAsk] query rewrite: {} -> {}", truncate(q, 30), truncate(searchQuery, 40));
            }
        }

        // 2. 问题向量化（基于改写后的查询）
        double[] queryEmb = embeddingService.generateEmbedding(searchQuery);
        if (queryEmb == null || queryEmb.length == 0) {
            log.warn("[AiAsk] 问题向量化失败，question={}", truncate(q, 50));
            return null;
        }

        // 3. 宽召回：向量 TopK=RECALL_TOPK（未限阈值），后续过滤 + Rerank 精排
        List<Object[]> hits = embeddingService.findSimilarArticles(queryEmb, RECALL_TOPK, 0);
        if (hits == null || hits.isEmpty()) {
            return emptyAnswer(start);
        }
        Map<Long, Double> simMap = new LinkedHashMap<>();
        for (Object[] hit : hits) {
            Long articleId = (Long) hit[0];
            double similarity = hit.length > 1 && hit[1] != null ? (Double) hit[1] : 0d;
            simMap.put(articleId, similarity);
        }

        // 4. 过滤仅已发布
        List<ApArticle> published = apArticleMapper.selectBatchIds(simMap.keySet()).stream()
            .filter(a -> a.getStatus() != null && a.getStatus() == Status.PUBLISHED.getCode())
            .sorted((a1, a2) -> Double.compare(
                simMap.getOrDefault(a2.getId(), 0d), simMap.getOrDefault(a1.getId(), 0d)))
            .collect(Collectors.toList());
        if (published.isEmpty()) {
            return emptyAnswer(start);
        }

        // 5. LLM Rerank：候选达到阈值时让模型挑选最相关至多 k 篇（失败/关闭则取向量 TopK）
        List<ApArticle> articles = published;
        if (RERANK_ENABLED && published.size() >= RERANK_MIN_CANDIDATES) {
            List<Long> reranked = rerankCandidates(q, published, k);
            if (reranked != null && !reranked.isEmpty()) {
                Map<Long, ApArticle> byId = new HashMap<>();
                for (ApArticle a : published) {
                    byId.put(a.getId(), a);
                }
                List<ApArticle> ordered = new ArrayList<>();
                for (Long id : reranked) {
                    ApArticle a = byId.get(id);
                    if (a != null && ordered.size() < k) {
                        ordered.add(a);
                    }
                }
                if (!ordered.isEmpty()) {
                    articles = ordered;
                    log.info("[AiAsk] rerank 生效: {} 候选 -> {} 篇", published.size(), ordered.size());
                }
            }
        }
        if (articles.size() > k) {
            articles = articles.subList(0, k);
        }
        List<Long> ids = articles.stream().map(ApArticle::getId).collect(Collectors.toList());
        Map<Long, String> contentMap = new HashMap<>();
        for (ApArticleContent c : contentMapper.selectList(
            new LambdaQueryWrapper<ApArticleContent>().in(ApArticleContent::getArticleId, ids))) {
            contentMap.put(c.getArticleId(), c.getContent());
        }

        // 4. 组装上下文
        StringBuilder docs = new StringBuilder();
        List<AiSourceVo> sources = new ArrayList<>();
        int idx = 1;
        for (ApArticle a : articles) {
            double sim = simMap.getOrDefault(a.getId(), 0d);
            docs.append("[").append(idx).append("] 标题：").append(a.getTitle())
                .append("；作者：").append(a.getAuthorName()).append("\n");
            String body = contentMap.getOrDefault(a.getId(), "");
            docs.append(truncate(body, CONTEXT_CHARS)).append("\n----\n");

            AiSourceVo src = new AiSourceVo();
            src.setArticleId(a.getId());
            src.setTitle(a.getTitle());
            src.setAuthor(a.getAuthorName());
            src.setLikes(a.getLikes());
            src.setSimilarity(Math.round(sim * 10000) / 10000.0);
            sources.add(src);
            idx++;
        }
        String userPrompt = buildUser(history, docs, q);

        // 5. 生成回答
        String answer;
        try {
            answer = dashScopeClient.callGeneration(SYSTEM_PROMPT, userPrompt);
        } catch (Exception e) {
            log.error("[AiAsk] 大模型生成失败, question={}", truncate(q, 50), e);
            return null;
        }
        if (answer == null || answer.isBlank()) {
            return null;
        }
        // 注意：sources 顺序必须与 docs 序号 [n] 一致（rerank 后非相似度序），不可再重排

        AiAnswerVo vo = new AiAnswerVo();
        vo.setAnswer(answer.trim());
        vo.setSources(sources);
        vo.setLatencyMs(System.currentTimeMillis() - start);
        log.info("[AiAsk] question={}, hits={}, sources={}, latency={}ms",
            truncate(q, 50), hits.size(), sources.size(), vo.getLatencyMs());
        return vo;
    }

    @Override
    public AiAnswerVo streamFastAsk(String question, Integer topK,
                                    java.util.List<java.util.Map<String, String>> history,
                                    java.util.function.Consumer<String> onDelta) {
        long start = System.currentTimeMillis();
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > MAX_QUESTION_LEN) {
            return null;
        }
        int k = topK == null ? DEFAULT_TOP_K : Math.max(1, Math.min(topK, MAX_TOP_K));
        double[] queryEmb = embeddingService.generateEmbedding(q);
        if (queryEmb == null || queryEmb.length == 0) {
            return null;
        }
        List<Object[]> hits = embeddingService.findSimilarArticles(queryEmb, k, 0);
        if (hits == null || hits.isEmpty()) {
            return emptyAnswer(start);
        }
        Map<Long, Double> simMap = new LinkedHashMap<>();
        for (Object[] hit : hits) {
            Long articleId = (Long) hit[0];
            double similarity = hit.length > 1 && hit[1] != null ? (Double) hit[1] : 0d;
            simMap.put(articleId, similarity);
        }
        List<ApArticle> articles = apArticleMapper.selectBatchIds(simMap.keySet()).stream()
            .filter(a -> a.getStatus() != null && a.getStatus() == Status.PUBLISHED.getCode())
            .sorted((a1, a2) -> Double.compare(
                simMap.getOrDefault(a2.getId(), 0d), simMap.getOrDefault(a1.getId(), 0d)))
            .limit(k)
            .collect(Collectors.toList());
        if (articles.isEmpty()) {
            return emptyAnswer(start);
        }
        List<Long> ids = articles.stream().map(ApArticle::getId).collect(Collectors.toList());
        Map<Long, String> contentMap = new HashMap<>();
        for (ApArticleContent c : contentMapper.selectList(
            new LambdaQueryWrapper<ApArticleContent>().in(ApArticleContent::getArticleId, ids))) {
            contentMap.put(c.getArticleId(), c.getContent());
        }
        StringBuilder docs = new StringBuilder();
        List<AiSourceVo> sources = new ArrayList<>();
        int idx = 1;
        for (ApArticle a : articles) {
            double sim = simMap.getOrDefault(a.getId(), 0d);
            docs.append("[").append(idx).append("] 标题：").append(a.getTitle())
                .append("；作者：").append(a.getAuthorName()).append("\n");
            String body = contentMap.getOrDefault(a.getId(), "");
            docs.append(truncate(body, CONTEXT_CHARS)).append("\n----\n");
            AiSourceVo src = new AiSourceVo();
            src.setArticleId(a.getId());
            src.setTitle(a.getTitle());
            src.setAuthor(a.getAuthorName());
            src.setLikes(a.getLikes());
            src.setSimilarity(Math.round(sim * 10000) / 10000.0);
            sources.add(src);
            idx++;
        }
        String userPrompt = buildUser(history, docs, q);
        StringBuilder acc = new StringBuilder();
        boolean ok = false;
        try {
            ok = dashScopeClient.streamChat(SYSTEM_PROMPT, userPrompt,
                delta -> {
                    acc.append(delta);
                    onDelta.accept(delta);
                });
        } catch (Exception e) {
            log.error("[AiAsk-stream] 生成失败, question={}", truncate(q, 40), e);
        }
        if (!ok || acc.length() == 0) {
            log.warn("[AiAsk-stream] 未获得流式输出，question={}", truncate(q, 40));
            return null;
        }
        AiAnswerVo vo = new AiAnswerVo();
        vo.setAnswer(acc.toString().trim());
        vo.setSources(sources);
        vo.setLatencyMs(System.currentTimeMillis() - start);
        log.info("[AiAsk-stream] question={}, sources={}, latency={}ms", truncate(q, 40), sources.size(), vo.getLatencyMs());
        return vo;
    }

    /** fast 模式：向量 TopK -> 过滤已发布 -> 单次生成（无 rewrite/rerank，低延迟低消耗） */
    private AiAnswerVo askFast(String q, int k, long start,
                               java.util.List<java.util.Map<String, String>> history) {
        double[] queryEmb = embeddingService.generateEmbedding(q);
        if (queryEmb == null || queryEmb.length == 0) {
            log.warn("[AiAsk-fast] 问题向量化失败，question={}", truncate(q, 50));
            return null;
        }
        List<Object[]> hits = embeddingService.findSimilarArticles(queryEmb, k, 0);
        if (hits == null || hits.isEmpty()) {
            return emptyAnswer(start);
        }
        Map<Long, Double> simMap = new LinkedHashMap<>();
        for (Object[] hit : hits) {
            Long articleId = (Long) hit[0];
            double similarity = hit.length > 1 && hit[1] != null ? (Double) hit[1] : 0d;
            simMap.put(articleId, similarity);
        }
        List<ApArticle> articles = apArticleMapper.selectBatchIds(simMap.keySet()).stream()
            .filter(a -> a.getStatus() != null && a.getStatus() == Status.PUBLISHED.getCode())
            .sorted((a1, a2) -> Double.compare(
                simMap.getOrDefault(a2.getId(), 0d), simMap.getOrDefault(a1.getId(), 0d)))
            .limit(k)
            .collect(Collectors.toList());
        if (articles.isEmpty()) {
            return emptyAnswer(start);
        }
        List<Long> ids = articles.stream().map(ApArticle::getId).collect(Collectors.toList());
        Map<Long, String> contentMap = new HashMap<>();
        for (ApArticleContent c : contentMapper.selectList(
            new LambdaQueryWrapper<ApArticleContent>().in(ApArticleContent::getArticleId, ids))) {
            contentMap.put(c.getArticleId(), c.getContent());
        }
        StringBuilder docs = new StringBuilder();
        List<AiSourceVo> sources = new ArrayList<>();
        int idx = 1;
        for (ApArticle a : articles) {
            double sim = simMap.getOrDefault(a.getId(), 0d);
            docs.append("[").append(idx).append("] 标题：").append(a.getTitle())
                .append("；作者：").append(a.getAuthorName()).append("\n");
            String body = contentMap.getOrDefault(a.getId(), "");
            docs.append(truncate(body, CONTEXT_CHARS)).append("\n----\n");
            AiSourceVo src = new AiSourceVo();
            src.setArticleId(a.getId());
            src.setTitle(a.getTitle());
            src.setAuthor(a.getAuthorName());
            src.setLikes(a.getLikes());
            src.setSimilarity(Math.round(sim * 10000) / 10000.0);
            sources.add(src);
            idx++;
        }
        String userPrompt = buildUser(history, docs, q);
        String answer;
        try {
            answer = dashScopeClient.callGeneration(SYSTEM_PROMPT, userPrompt);
        } catch (Exception e) {
            log.error("[AiAsk-fast] 大模型生成失败, question={}", truncate(q, 50), e);
            return null;
        }
        if (answer == null || answer.isBlank()) {
            return null;
        }
        AiAnswerVo vo = new AiAnswerVo();
        vo.setAnswer(answer.trim());
        vo.setSources(sources);
        vo.setLatencyMs(System.currentTimeMillis() - start);
        log.info("[AiAsk-fast] question={}, sources={}, latency={}ms", truncate(q, 40), sources.size(), vo.getLatencyMs());
        return vo;
    }

    /**
     * 存量已发布文章向量回填（幂等：已有向量的跳过）。每天凌晨执行，限量防压外部服务。
     * 仅当 PgVector 已启用(pgvector.enabled=true)且有向量缺失时才真正写库。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void backfillEmbeddings() {
        int batch = 200;
        try {
            List<ApArticle> articles = apArticleMapper.selectList(
                new LambdaQueryWrapper<ApArticle>()
                    .eq(ApArticle::getStatus, Status.PUBLISHED.getCode())
                    .last("LIMIT " + batch));
            int filled = 0;
            for (ApArticle a : articles) {
                try {
                    if (embeddingService.getEmbedding(a.getId()) != null) {
                        continue; // 已有向量
                    }
                    ApArticleContent c = contentMapper.selectOne(
                        new LambdaQueryWrapper<ApArticleContent>()
                            .eq(ApArticleContent::getArticleId, a.getId())
                            .last("LIMIT 1"));
                    if (c == null || c.getContent() == null || c.getContent().isBlank()) {
                        continue;
                    }
                    double[] emb = embeddingService.generateEmbedding(truncate(c.getContent(), 2000));
                    if (emb != null) {
                        embeddingService.saveEmbedding(a.getId(), emb);
                        filled++;
                    }
                } catch (Exception e) {
                    log.warn("[AiAsk] 回填向量失败 articleId={}", a.getId(), e);
                }
            }
            log.info("[AiAsk] 向量回填完成: 扫描={}, 新增={}", articles.size(), filled);
        } catch (Exception e) {
            log.error("[AiAsk] 向量回填异常", e);
        }
    }

    /** 组装 user 输入：对话历史（可选，滑窗最近几轮）+ 参考资料 + 问题 */
    private String buildUser(java.util.List<java.util.Map<String, String>> history,
                             StringBuilder docs, String q) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            sb.append("【对话历史】\n");
            int count = 0;
            for (int i = history.size() - 1; i >= 0 && count < 6; i--, count++) {
                java.util.Map<String, String> turn = history.get(i);
                String role = turn == null ? null : turn.get("role");
                String content = turn == null ? null : turn.get("content");
                if (role == null || content == null) {
                    continue;
                }
                sb.append(role).append(": ").append(truncate(content, 300)).append("\n");
            }
            sb.append("\n");
        }
        sb.append("【参考资料】\n").append(docs).append("\n【问题】").append(q);
        return sb.toString();
    }

    /** Query Rewrite：一次小模型调用（失败返回 null -> 调用方回退原文） */
    private String queryRewrite(String question) {
        try {
            String raw = dashScopeClient.callGeneration(REWRITE_PROMPT, question);
            if (raw == null) {
                return null;
            }
            String r = raw.trim();
            if (r.length() >= 2 && ((r.startsWith("\"") && r.endsWith("\"")) || (r.startsWith("“") && r.endsWith("”")))) {
                r = r.substring(1, r.length() - 1);
            }
            return r.length() > 80 ? r.substring(0, 80) : r;
        } catch (Exception e) {
            log.warn("[AiAsk] query rewrite 失败", e);
            return null;
        }
    }

    /** LLM Rerank：候选 -> 最相关 id 列表（失败返回 null -> 调用方回退向量序） */
    private List<Long> rerankCandidates(String question, List<ApArticle> candidates, int maxN) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ApArticle a : candidates) {
            sb.append("[").append(i).append("] ").append(a.getTitle()).append("\n");
            i++;
        }
        String prompt = RERANK_PROMPT.replace("{maxN}", String.valueOf(maxN))
            + "\n\n【问题】" + question + "\n【候选】\n" + sb;
        try {
            String raw = dashScopeClient.callGeneration(
                "你是信息检索重排器，严格按要求输出 JSON。", prompt);
            if (raw == null) {
                return null;
            }
            // 解析 {"selected":[...]}（容忍模型在首尾附加解释文本）
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
                        if (idx >= 1 && idx <= candidates.size()) {
                            ids.add(candidates.get(idx - 1).getId());
                        }
                    }
                }
            }
            if (ids.isEmpty()) {
                log.warn("[AiAsk] rerank selected 为空, raw={}", truncate(raw, 200));
            }
            return ids.isEmpty() ? null : ids;
        } catch (Exception e) {
            log.warn("[AiAsk] rerank 失败", e);
            return null;
        }
    }

    private AiAnswerVo emptyAnswer(long start) {
        AiAnswerVo vo = new AiAnswerVo();
        vo.setAnswer("社区知识库中暂未检索到与问题相关的内容，换个问法试试吧。");
        vo.setSources(Collections.emptyList());
        vo.setLatencyMs(System.currentTimeMillis() - start);
        return vo;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
