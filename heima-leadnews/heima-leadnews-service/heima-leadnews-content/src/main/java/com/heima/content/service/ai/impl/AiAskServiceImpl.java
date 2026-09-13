package com.heima.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.ai.AiAskService;
import com.heima.content.service.ai.AiSemanticCacheService;
import com.heima.content.service.ai.AnswerFaithfulnessService;
import com.heima.content.service.ai.HybridRecallService;
import com.heima.content.service.ai.memory.AiConversationMemoryService;
import com.heima.content.service.ai.memory.UserMemoryService;
import com.heima.content.service.ai.spring.PromptSafetyAdvisor;
import com.heima.content.service.ai.spring.SafetyGuardException;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.content.utils.MarkdownUtils;
import com.heima.content.utils.TextChunker;
import com.heima.model.article.dtos.AiAnswerVo;
import com.heima.model.article.dtos.AiSourceVo;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.user.pojos.ApUser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 社区 AI 问答实现（RAG）
 *
 * <p>复用审核链已有的向量基建：DashScope embedding + pgvector(ap_article_embedding) 余弦检索；
 * 生成（同步/流式/改写/重排）统一走 Spring AI ChatClient；向量检索经 Spring AI EmbeddingModel + pgvector。
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
    /** 会话记忆滑窗：最多注入的消息条数（约 6 轮 user/assistant） */
    private static final int MEMORY_MAX_MESSAGES = 12;
    /** 每请求独立 memory 实例 + 固定会话 key（实例随请求销毁，天然隔离） */
    private static final String MEMORY_CONVERSATION_ID = "aiask-conversation";

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
        "5. 若对话历史（历史消息）中出现指代（如“它/那篇/上面提到”），结合历史理解用户意图，但引用标注仍只来自本轮【参考资料】。";

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    @Autowired
    private AiSemanticCacheService semanticCacheService;

    @Autowired
    private HybridRecallService hybridRecallService;

    @Autowired
    private AnswerFaithfulnessService faithfulnessService;

    /** 忠实度校验模式：off 关闭 / async 异步只记录（默认，不影响响应延迟）/ sync 同步并把简报塞进响应 */
    @Value("${ai.faithfulness.mode:async}")
    private String faithfulnessMode;

    /** 异步校验复用 SSE 线程池（与 AIGC 预审/复核同一套） */
    @Autowired
    @Qualifier("aiSseExecutor")
    private Executor aiSseExecutor;

    @Autowired
    private com.heima.content.service.ai.UserInterestService userInterestService;

    @Autowired
    private AiConversationMemoryService conversationMemoryService;

    @Autowired
    private UserMemoryService userMemoryService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleContentMapper contentMapper;

    @Autowired
    private org.springframework.ai.chat.model.ChatModel chatModel;

    @Autowired
    private PromptSafetyAdvisor promptSafetyAdvisor;

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

        // 0. 语义缓存：相似问题直返（命中即省掉 rewrite + rerank + 生成 三次模型调用）
        AiAnswerVo cached = semanticCacheService.lookup(q, currentUserId());
        if (cached != null) {
            cached.setLatencyMs(System.currentTimeMillis() - start);
            log.info("[AiAsk] 语义缓存命中, q={}, latency={}ms", truncate(q, 40), cached.getLatencyMs());
            return cached;
        }

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

        // 2. 统一检索管线：向量化 -> 宽召回(RECALL_TOPK) -> 过滤已发布 -> LLM Rerank -> 组装文档与来源
        Retrieval r = retrieveAndAssemble(searchQuery, q, k, RERANK_ENABLED);
        if (r == null) {
            log.warn("[AiAsk] 问题向量化失败，question={}", truncate(q, 50));
            return null;
        }
        if (r.hits == 0) {
            return emptyAnswer(start);
        }

        // 3. 生成回答（安全三层防御由 PromptSafetyAdvisor 横切处理；长期记忆/兴趣画像注入见 buildUser）
        String userPrompt = buildUser(r.docsText, q, r.queryEmbedding, currentUserId());
        String answer;
        try {
            answer = genText(SYSTEM_PROMPT, userPrompt, history, currentUserId());
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
        vo.setSources(r.sources);
        vo.setLatencyMs(System.currentTimeMillis() - start);
        // 回答成功：持久化会话记忆 + 沉淀语义记忆（Redis/PGVector，全部 fail-open 异步无关紧要）
        persistMemory(currentUserId(), q, answer.trim(), r.queryEmbedding);
        semanticCacheService.store(q, currentUserId(), vo.getAnswer(), r.sources);
        checkFaithfulness(vo, r);
        log.info("[AiAsk] question={}, hits={}, sources={}, latency={}ms",
            truncate(q, 50), r.hits, r.sources.size(), vo.getLatencyMs());
        return vo;
    }

    @Override
    public AiAnswerVo streamFastAsk(String question, Integer topK,
                                    java.util.List<java.util.Map<String, String>> history,
                                    java.util.function.Consumer<String> onDelta, Integer userId) {
        long start = System.currentTimeMillis();
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > MAX_QUESTION_LEN) {
            return null;
        }
        int k = topK == null ? DEFAULT_TOP_K : Math.max(1, Math.min(topK, MAX_TOP_K));
        // 0. 语义缓存：命中则按 chunk 回放（前端 delta 协议不变），跳过检索与生成
        AiAnswerVo cachedStream = semanticCacheService.lookup(q, userId);
        if (cachedStream != null) {
            replayDelta(cachedStream.getAnswer(), onDelta);
            // 命中路径不重复 embedding 沉淀，仅补记会话记忆，保持多轮上下文连续
            try {
                conversationMemoryService.appendTurn(userId, q, cachedStream.getAnswer());
            } catch (Exception e) {
                log.debug("[AiAsk-stream] 命中路径会话记忆写入失败, userId={}", userId, e);
            }
            cachedStream.setLatencyMs(System.currentTimeMillis() - start);
            log.info("[AiAsk-stream] 语义缓存命中, q={}, latency={}ms", truncate(q, 40), cachedStream.getLatencyMs());
            return cachedStream;
        }
        // 统一检索管线：向量化 -> 召回(k) -> 过滤已发布 -> 组装文档与来源（无 rewrite/rerank，低延迟）
        Retrieval r = retrieveAndAssemble(q, q, k, false);
        if (r == null) {
            return null;
        }
        if (r.hits == 0) {
            return emptyAnswer(start);
        }
        String userPrompt = buildUser(r.docsText, q, r.queryEmbedding, userId);
        StringBuilder acc = new StringBuilder();
        boolean ok = false;
        try {
            genStream(SYSTEM_PROMPT, userPrompt, history, userId,
                delta -> {
                    acc.append(delta);
                    onDelta.accept(delta);
                });
            ok = true;
        } catch (Exception e) {
            log.error("[AiAsk-stream] 生成失败, question={}", truncate(q, 40), e);
        }
        if (!ok || acc.length() == 0) {
            log.warn("[AiAsk-stream] 未获得流式输出，question={}", truncate(q, 40));
            return null;
        }
        // Layer 3：输出护栏 —— 流式完整文本命中顺从短语视为注入成功，丢弃并降级
        try {
            promptSafetyAdvisor.guardStreamed(acc.toString());
        } catch (SafetyGuardException e) {
            log.warn("[AiAsk-stream] 输出护栏命中（顺从短语），丢弃该回答并降级: {}", e.getMessage());
            return null;
        }
        AiAnswerVo vo = new AiAnswerVo();
        vo.setAnswer(acc.toString().trim());
        vo.setSources(r.sources);
        vo.setLatencyMs(System.currentTimeMillis() - start);
        // 回答成功：持久化会话记忆 + 沉淀语义记忆
        persistMemory(userId, q, vo.getAnswer(), r.queryEmbedding);
        semanticCacheService.store(q, userId, vo.getAnswer(), r.sources);
        checkFaithfulness(vo, r);
        log.info("[AiAsk-stream] question={}, sources={}, latency={}ms", truncate(q, 40), r.sources.size(), vo.getLatencyMs());
        return vo;
    }

    /** fast 模式：单次向量召回 + 一次生成（无 rewrite/rerank，低延迟低消耗） */
    private AiAnswerVo askFast(String q, int k, long start,
                               java.util.List<java.util.Map<String, String>> history) {
        Retrieval r = retrieveAndAssemble(q, q, k, false);
        if (r == null) {
            log.warn("[AiAsk-fast] 问题向量化失败，question={}", truncate(q, 50));
            return null;
        }
        if (r.hits == 0) {
            return emptyAnswer(start);
        }
        String userPrompt = buildUser(r.docsText, q, r.queryEmbedding, currentUserId());
        String answer;
        try {
            answer = genText(SYSTEM_PROMPT, userPrompt, history, currentUserId());
        } catch (Exception e) {
            log.error("[AiAsk-fast] 大模型生成失败, question={}", truncate(q, 50), e);
            return null;
        }
        if (answer == null || answer.isBlank()) {
            return null;
        }
        AiAnswerVo vo = new AiAnswerVo();
        vo.setAnswer(answer.trim());
        vo.setSources(r.sources);
        vo.setLatencyMs(System.currentTimeMillis() - start);
        // 回答成功：持久化会话记忆 + 沉淀语义记忆
        persistMemory(currentUserId(), q, answer.trim(), r.queryEmbedding);
        semanticCacheService.store(q, currentUserId(), vo.getAnswer(), r.sources);
        checkFaithfulness(vo, r);
        log.info("[AiAsk-fast] question={}, sources={}, latency={}ms", truncate(q, 40), r.sources.size(), vo.getLatencyMs());
        return vo;
    }

    /**
     * 存量已发布文章向量回填（幂等：已有向量的跳过）。每天凌晨执行，限量防压外部服务。
     * 仅当 PgVector 已启用(pgvector.enabled=true)且有向量缺失时才真正写库。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void backfillEmbeddings() {
        int batch = 200;
        long lastId = 0L;
        int scanned = 0;
        int filled = 0;
        int chunked = 0;
        try {
            // 游标分页扫全量已发布文章（修复原先只扫前 200 条导致永远填不完的问题）
            for (int page = 0; page < 1000; page++) {
                List<ApArticle> articles = apArticleMapper.selectList(
                    new LambdaQueryWrapper<ApArticle>()
                        .eq(ApArticle::getStatus, Status.PUBLISHED.getCode())
                        .gt(ApArticle::getId, lastId)
                        .orderByAsc(ApArticle::getId)
                        .last("LIMIT " + batch));
                if (articles.isEmpty()) {
                    break;
                }
                for (ApArticle a : articles) {
                    scanned++;
                    lastId = a.getId();
                    try {
                        boolean needArticleVec = embeddingService.getEmbedding(a.getId()) == null;
                        boolean needChunks = !embeddingService.hasChunks(a.getId());
                        if (!needArticleVec && !needChunks) {
                            continue; // 文章向量与分块均已就绪
                        }
                        ApArticleContent c = contentMapper.selectOne(
                            new LambdaQueryWrapper<ApArticleContent>()
                                .eq(ApArticleContent::getArticleId, a.getId())
                                .last("LIMIT 1"));
                        if (c == null || c.getContent() == null || c.getContent().isBlank()) {
                            continue;
                        }
                        if (needArticleVec) {
                            double[] emb = embeddingService.generateEmbedding(truncate(c.getContent(), 2000));
                            if (emb != null) {
                                embeddingService.saveEmbedding(a.getId(), emb);
                                filled++;
                            }
                        }
                        // 老语料补齐子块（分块检索上线后必须回填，否则只能走文章级回退）
                        if (needChunks) {
                            embeddingService.saveChunks(a.getId(), TextChunker.split(
                                MarkdownUtils.normalizeContent(c.getContent()),
                                TextChunker.DEFAULT_TARGET, TextChunker.DEFAULT_OVERLAP,
                                embeddingService.getChunkMaxPerArticle()));
                            chunked++;
                        }
                    } catch (Exception e) {
                        log.warn("[AiAsk] 回填向量失败 articleId={}", a.getId(), e);
                    }
                }
                if (articles.size() < batch) {
                    break;
                }
            }
            log.info("[AiAsk] 向量回填完成: 扫描={}, 新增文章向量={}, 补分块={}", scanned, filled, chunked);
        } catch (Exception e) {
            log.error("[AiAsk] 向量回填异常", e);
        }
    }

    /** Spring AI 同步文本生成：安全三层防御由 PromptSafetyAdvisor 横切；会话历史经 MessageChatMemoryAdvisor 注入（userId null=内部调用，不触碰持久化） */
    private String genText(String systemPrompt, String user,
                           java.util.List<java.util.Map<String, String>> history, Integer userId) {
        try {
            return org.springframework.ai.chat.client.ChatClient.builder(chatModel)
                .defaultAdvisors(promptSafetyAdvisor,
                    MessageChatMemoryAdvisor.builder(buildConversationMemory(userId, history)).build())
                .build()
                .prompt().system(systemPrompt).user(user == null ? "" : user)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, MEMORY_CONVERSATION_ID))
                .call().content();
        } catch (SafetyGuardException e) {
            log.warn("[AiAsk] 输出护栏命中（顺从短语），丢弃该回答并降级: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("[AiAsk] Spring AI 生成失败", e);
            return null;
        }
    }

    /** Spring AI 流式生成（逐段回调增量文本；返回完整文本；安全 + 会话记忆由 Advisor 处理） */
    private String genStream(String systemPrompt, String user,
                             java.util.List<java.util.Map<String, String>> history, Integer userId,
                             java.util.function.Consumer<String> onDelta) {
        reactor.core.publisher.Flux<String> flux =
            org.springframework.ai.chat.client.ChatClient.builder(chatModel)
                .defaultAdvisors(promptSafetyAdvisor,
                    MessageChatMemoryAdvisor.builder(buildConversationMemory(userId, history)).build())
                .build()
                .prompt().system(systemPrompt).user(user == null ? "" : user)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, MEMORY_CONVERSATION_ID))
                .stream().content();
        StringBuilder acc = new StringBuilder();
        flux.doOnNext(t -> {
            acc.append(t);
            onDelta.accept(t);
        }).blockLast();
        return acc.toString();
    }

    /** 统一检索管线产物：参考资料文本 + 来源列表 + 命中数 + 查询向量（供语义记忆复用） */
    private static final class Retrieval {
        final String docsText;
        final List<AiSourceVo> sources;
        final int hits;
        /** 本次查询的向量（改写后），供长期记忆沉淀/召回复用，避免重复 embedding 调用 */
        final double[] queryEmbedding;

        Retrieval(String docsText, List<AiSourceVo> sources, int hits, double[] queryEmbedding) {
            this.docsText = docsText;
            this.sources = sources;
            this.hits = hits;
            this.queryEmbedding = queryEmbedding;
        }
    }

    /**
     * 统一检索管线：向量化 -> 召回 -> 过滤已发布 ->（可选）LLM Rerank -> 组装【参考资料】文本与来源列表。
     *
     * <p>ask / askFast / streamFastAsk 三处共用，避免重复逻辑漂移。
     *
     * @param searchQuery  向量检索用查询（普通路径即问题原文；完整路径为 rewrite 后文本）
     * @param userQuestion 原始问题（用于 LLM Rerank；null 或 doRerank=false 则跳过精排）
     * @param topK         最终取前 N 篇
     * @param doRerank     候选达到阈值时是否启用 LLM 精排（完整路径 true，fast/流式 false）
     * @return null=向量化失败（调用方降级）；hits==0 表示无命中（调用方返回空答案）
     */
    private Retrieval retrieveAndAssemble(String searchQuery, String userQuestion, int topK, boolean doRerank) {
        int recall = doRerank ? RECALL_TOPK : topK;
        double[] queryEmb = embeddingService.generateEmbedding(searchQuery);
        if (queryEmb == null || queryEmb.length == 0) {
            return null;
        }
        // 混合召回：向量（父子分块，粒度细、长文后半段可召回）＋ BM25（术语/代码类查询强）→ RRF 融合排名；
        // 任一路失败自动退化为单路，上下游流程不变
        HybridRecallService.Recall hybrid = hybridRecallService.recall(searchQuery, queryEmb, recall);
        List<Long> candidates = hybrid.getIds();
        Map<Long, Double> simMap = new LinkedHashMap<>(hybrid.getVectorSims());
        if (candidates == null || candidates.isEmpty()) {
            return new Retrieval("", new ArrayList<>(), 0, queryEmb);
        }
        // 相似度补全：BM25 独有命中用已存文章向量本地算余弦（零模型调用），保证来源卡片口径统一
        Map<Long, Integer> fusedOrder = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            Long id = candidates.get(i);
            fusedOrder.put(id, i);
            if (!simMap.containsKey(id)) {
                simMap.put(id, localCosine(queryEmb, id));
            }
        }
        // 过滤仅已发布；候选顺序即 RRF 融合顺序（不再按向量相似度重排，否则融合排名白做）
        List<ApArticle> published = apArticleMapper.selectBatchIds(candidates).stream()
            .filter(a -> a.getStatus() != null && a.getStatus() == Status.PUBLISHED.getCode()
                && (a.getIsAigc() == null || a.getIsAigc() != 1))
            .sorted(Comparator.comparingInt(a -> fusedOrder.getOrDefault(a.getId(), Integer.MAX_VALUE)))
            .collect(Collectors.toList());
        if (published.isEmpty()) {
            return new Retrieval("", new ArrayList<>(), 0, queryEmb);
        }
        List<ApArticle> articles = published;
        // LLM Rerank：候选达到阈值时让模型挑选最相关至多 topK 篇（失败/关闭则取向量 TopK）
        if (doRerank && userQuestion != null && published.size() >= RERANK_MIN_CANDIDATES) {
            List<Long> reranked = rerankCandidates(userQuestion, published, topK);
            if (reranked != null && !reranked.isEmpty()) {
                Map<Long, ApArticle> byId = new HashMap<>();
                for (ApArticle a : published) {
                    byId.put(a.getId(), a);
                }
                List<ApArticle> ordered = new ArrayList<>();
                for (Long id : reranked) {
                    ApArticle a = byId.get(id);
                    if (a != null && ordered.size() < topK) {
                        ordered.add(a);
                    }
                }
                if (!ordered.isEmpty()) {
                    articles = ordered;
                    log.info("[AiAsk] rerank 生效: {} 候选 -> {} 篇", published.size(), ordered.size());
                }
            }
        }
        if (articles.size() > topK) {
            articles = articles.subList(0, topK);
        }
        List<Long> ids = articles.stream().map(ApArticle::getId).collect(Collectors.toList());
        Map<Long, String> contentMap = new HashMap<>();
        for (ApArticleContent c : contentMapper.selectList(
            new LambdaQueryWrapper<ApArticleContent>().in(ApArticleContent::getArticleId, ids))) {
            contentMap.put(c.getArticleId(), c.getContent());
        }
        // 组装上下文（docs 序号必须与 sources 一一对应，不可重排）
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
        return new Retrieval(docs.toString(), sources, candidates.size(), queryEmb);
    }

    /** BM25 独有命中的相似度补全：用已存文章向量本地算余弦（不调模型、不引入新依赖） */
    private double localCosine(double[] queryEmb, Long articleId) {
        try {
            com.heima.model.article.pojos.ApArticleEmbedding emb = embeddingService.getEmbedding(articleId);
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

    /**
     * 组装 user 输入：长期语义记忆 + 用户兴趣参考 + 参考资料 + 问题。
     * 对话历史改由 MessageChatMemoryAdvisor 以真实消息结构注入（见 buildConversationMemory）。
     */
    private String buildUser(String docsText, String q, double[] queryEmbedding, Integer userId) {
        StringBuilder sb = new StringBuilder();
        // 长期语义记忆：向量召回用户历史相似提问（PGVector, ap_user_memory），仅作个性化参考，全失败静默跳过
        try {
            if (userId != null && queryEmbedding != null && queryEmbedding.length > 0) {
                java.util.List<String> memories =
                    userMemoryService.recall(userId, queryEmbedding, UserMemoryService.DEFAULT_TOP_K, 0d);
                if (memories != null && !memories.isEmpty()) {
                    sb.append("【长期记忆】该用户近来关注过：").append(String.join("；", memories))
                        .append("。仅在问题与之相关时辅助理解，不相关请忽略；回答依据仍只来自【参考资料】。\n");
                }
            }
        } catch (Exception e) {
            log.debug("[AiAsk] 长期记忆召回失败，跳过个性化, q={}", truncate(q, 20));
        }
        // 个性化：注入用户近期阅读兴趣（收藏聚合标签），仅在相关时辅助理解；画像失败/无数据跳过
        try {
            ApUser u = com.heima.utils.thread.AppThreadLocalUtil.getUser();
            if (u != null && u.getId() != null) {
                java.util.List<String> tags = userInterestService.buildInterestTags(u.getId());
                if (tags != null && !tags.isEmpty()) {
                    sb.append("【用户兴趣参考】该用户近期常读方向：").append(String.join("、", tags))
                        .append("。仅在问题与之相关时辅助理解，不相关请忽略；回答依据仍只来自【参考资料】。\n");
                }
            }
        } catch (Exception e) {
            log.debug("[AiAsk] 兴趣画像注入失败，跳过个性化, q={}", truncate(q, 20));
        }
        sb.append("【参考资料】\n").append(docsText).append("\n【问题】").append(q);
        return sb.toString();
    }

    /**
     * 构建请求级会话记忆窗口（MessageChatMemoryAdvisor 注入用）。
     *
     * <p>记忆来源 = 服务端持久化（Redis，按用户）+ 前端携带的最近会话合并（尾部重叠去重），
     * 再按 MEMORY_MAX_MESSAGES 滑窗预载；实例随请求销毁互不污染。
     * userId 为 null（内部调用：Query Rewrite / Rerank）时跳过 Redis，杜绝内部 prompt 污染用户话题记忆。
     */
    private ChatMemory buildConversationMemory(Integer userId,
                                               java.util.List<java.util.Map<String, String>> history) {
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
            .maxMessages(MEMORY_MAX_MESSAGES)
            .build();
        java.util.List<java.util.Map<String, String>> persisted = new ArrayList<>();
        if (userId != null) {
            try {
                persisted = conversationMemoryService.load(userId);
            } catch (Exception e) {
                log.debug("[AiAsk] 持久化会话加载失败，退化为仅前端 history, userId={}", userId);
            }
        }
        java.util.List<java.util.Map<String, String>> merged =
            mergeTurns(persisted, normalizeHistory(history));
        memory.add(MEMORY_CONVERSATION_ID, toMessages(merged));
        return memory;
    }

    /** 回答成功后的记忆写回：会话记忆（Redis）+ 语义记忆（PGVector），全部 fail-open 不影响主链路 */
    private void persistMemory(Integer userId, String question, String answer, double[] queryEmbedding) {
        if (userId == null || answer == null || answer.isBlank()) {
            return;
        }
        try {
            conversationMemoryService.appendTurn(userId, question, answer);
        } catch (Exception e) {
            log.debug("[AiAsk] 会话记忆持久化失败, userId={}", userId, e);
        }
        try {
            userMemoryService.remember(userId, question, queryEmbedding);
        } catch (Exception e) {
            log.debug("[AiAsk] 语义记忆沉淀失败, userId={}", userId, e);
        }
    }

    private Integer currentUserId() {
        ApUser u = com.heima.utils.thread.AppThreadLocalUtil.getUser();
        return u == null ? null : u.getId();
    }

    /**
     * 答案忠实度校验（幻觉兜底）：检查答案里的引用是否真的被资料支撑。
     *
     * <p>三种模式：off 跳过；async（默认）走线程池异步校验，只写日志与指标、不影响响应；sync 同步校验并把
     * 简报放进响应（评测/调试用）。任何异常都只记 debug 日志——**校验失败不影响回答**。
     * 缓存命中路径不重复校验（入库时已校验过）。
     */
    private void checkFaithfulness(AiAnswerVo vo, Retrieval r) {
        if (vo == null || r == null || "off".equalsIgnoreCase(faithfulnessMode)) {
            return;
        }
        try {
            if ("sync".equalsIgnoreCase(faithfulnessMode)) {
                AnswerFaithfulnessService.Report report =
                    faithfulnessService.check(vo.getAnswer(), r.sources, r.docsText);
                vo.setFaithfulness(report.toBrief());
                return;
            }
            final String answer = vo.getAnswer();
            final java.util.List<com.heima.model.article.dtos.AiSourceVo> sources = r.sources;
            final String docs = r.docsText;
            CompletableFuture.runAsync(() -> {
                try {
                    faithfulnessService.check(answer, sources, docs);
                } catch (Exception e) {
                    log.debug("[AiAsk] 异步忠实度校验失败", e);
                }
            }, aiSseExecutor);
        } catch (Exception e) {
            log.debug("[AiAsk] 忠实度校验调度失败（忽略）", e);
        }
    }

    /**
     * 缓存命中回放：把整段答案切片走同一条 delta 回调通道，
     * 前端看到的仍是「delta... + done」协议，无需感知命中与否。
     */
    private static void replayDelta(String answer, java.util.function.Consumer<String> onDelta) {
        if (answer == null || answer.isEmpty() || onDelta == null) {
            return;
        }
        int step = 40;
        for (int i = 0; i < answer.length(); i += step) {
            onDelta.accept(answer.substring(i, Math.min(answer.length(), i + step)));
        }
    }

    /** 归一化前端 history：仅保留 role/content 且内容非空、按序输出 */
    private static java.util.List<java.util.Map<String, String>> normalizeHistory(
            java.util.List<java.util.Map<String, String>> history) {
        java.util.List<java.util.Map<String, String>> out = new ArrayList<>();
        if (history == null) {
            return out;
        }
        for (java.util.Map<String, String> t : history) {
            if (t == null) {
                continue;
            }
            String content = t.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            java.util.Map<String, String> turn = new LinkedHashMap<>();
            turn.put("role", "user".equalsIgnoreCase(t.get("role")) ? "user" : "assistant");
            turn.put("content", content);
            out.add(turn);
        }
        return out;
    }

    /**
     * 合并持久化会话与前端会话：前端尾部与持久化尾部重叠部分去重（同端增量场景），
     * 其余前端口径追加；返回最近 MEMORY_MAX_MESSAGES 条作为注入窗口。
     */
    private static java.util.List<java.util.Map<String, String>> mergeTurns(
            java.util.List<java.util.Map<String, String>> persisted,
            java.util.List<java.util.Map<String, String>> front) {
        java.util.List<java.util.Map<String, String>> merged = new ArrayList<>(persisted);
        int overlap = 0;
        int max = Math.min(front.size(), persisted.size());
        for (int i = 1; i <= max; i++) {
            java.util.Map<String, String> f = front.get(front.size() - i);
            java.util.Map<String, String> p = persisted.get(persisted.size() - i);
            if (f != null && p != null
                    && java.util.Objects.equals(f.get("role"), p.get("role"))
                    && java.util.Objects.equals(f.get("content"), p.get("content"))) {
                overlap++;
            } else {
                break;
            }
        }
        if (overlap < front.size()) {
            merged.addAll(front.subList(0, front.size() - overlap));
        }
        int from = Math.max(0, merged.size() - MEMORY_MAX_MESSAGES);
        return merged.subList(from, merged.size());
    }

    /** {role, content} 列表 -> Spring AI 消息列表（user 前 assistant 后，统一截断防上下文膨胀） */
    private static java.util.List<Message> toMessages(
            java.util.List<java.util.Map<String, String>> turns) {
        java.util.List<Message> msgs = new ArrayList<>();
        for (java.util.Map<String, String> turn : turns) {
            String role = turn.get("role");
            String content = turn.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("user".equalsIgnoreCase(role)) {
                msgs.add(new UserMessage(truncate(content, 300)));
            } else {
                msgs.add(new AssistantMessage(truncate(content, 300)));
            }
        }
        return msgs;
    }

    /** Query Rewrite：一次小模型调用（失败返回 null -> 调用方回退原文）；内部调用不触碰会话持久化 */
    private String queryRewrite(String question) {
        try {
            String raw = genText(REWRITE_PROMPT, question, null, null);
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
            String raw = genText(
                "你是信息检索重排器，严格按要求输出 JSON。", prompt, null, null);
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
