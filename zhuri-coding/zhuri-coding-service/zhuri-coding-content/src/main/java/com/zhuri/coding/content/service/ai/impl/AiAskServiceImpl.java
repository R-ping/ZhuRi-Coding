package com.zhuri.coding.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.ai.AiAskService;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.AiSemanticCacheService;
import com.zhuri.coding.content.service.ai.AnswerFaithfulnessService;
import com.zhuri.coding.content.service.ai.HybridRecallService;
import com.zhuri.coding.content.service.ai.memory.AiConversationMemoryService;
import com.zhuri.coding.content.service.ai.memory.UserMemoryService;
import com.zhuri.coding.content.service.ai.spring.PromptSafetyAdvisor;
import com.zhuri.coding.content.service.ai.spring.SafetyGuardException;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.content.utils.MarkdownUtils;
import com.zhuri.coding.content.utils.TextChunker;
import com.zhuri.coding.model.article.dtos.AiAnswerVo;
import com.zhuri.coding.model.article.dtos.AiSourceVo;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.user.pojos.ApUser;
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

    /** 向量轮转同步游标（Redis）：记住上次扫到的 article_id，实现不依赖时间列的轮转比对 */
    private static final String EMBED_SWEEP_CURSOR_KEY = "ai:embed:sweep:cursor";
    /** 每批轮转的文章数（10 分钟一批；500 篇 ≈ 与"扫描+比对指纹"的 CPU/DB 开销平衡） */
    private static final int SWEEP_BATCH = 500;

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

    /** Prompt 注册表（P2-1）：DB 版本化 + 灰度 + 代码兜底；单测未注入时走兜底 */
    @Autowired(required = false)
    private com.zhuri.coding.content.service.ai.AiPromptRegistry promptRegistry;

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
    private com.zhuri.coding.content.service.ai.UserInterestService userInterestService;

    @Autowired
    private AiConversationMemoryService conversationMemoryService;

    @Autowired
    private UserMemoryService userMemoryService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleContentMapper contentMapper;

    /** 流式 Layer 3 输出护栏兜底（汇聚完整文本后判定）；非流式由 gateway 内 advisor 横切 */
    @Autowired
    private PromptSafetyAdvisor promptSafetyAdvisor;

    /** 统一 LLM 出口：安全横切 + token 计量（P0-2 成本观测） */
    @Autowired
    private com.zhuri.coding.content.service.ai.AiLlmGateway llmGateway;

    /** 业务指标（调用计数 + TTFT 等延迟观测） */
    @Autowired
    private com.zhuri.coding.content.service.ai.AiMetricsCollector aiMetricsCollector;

    /** 消费漏斗计（缓存命中/检索/生成 阶段打点） */
    @Autowired
    private com.zhuri.coding.content.service.ai.AiFunnelMeter funnelMeter;

    /** Redis（向量轮转同步游标） */
    @Autowired
    private com.zhuri.coding.common.redis.CacheService cacheService;

    /** 检索管线显式链（P2 Prompt Chaining）：向量化→召回→过滤→精排→组装 */
    @Autowired
    private com.zhuri.coding.content.service.ai.pipeline.AskRetrievalChain retrievalChain;

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
            // 消费漏斗：缓存命中（省掉检索与生成）
            funnelMeter.incr(Boolean.TRUE.equals(fast) ? AiFeatures.ASK_FAST : AiFeatures.ASK,
                    com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_CACHE_HIT);
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
        // 消费漏斗：检索管线执行完毕（含 hits==0）
        funnelMeter.incr(AiFeatures.ASK, com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_RECALL_DONE);
        if (r.hits == 0) {
            return emptyAnswer(start);
        }

        // 3. 生成回答（安全三层防御由 PromptSafetyAdvisor 横切处理；长期记忆/兴趣画像注入见 buildUser）
        String userPrompt = buildUser(r.docsText, q, r.queryEmbedding, currentUserId());
        com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt sysPrompt =
            prompt("ai_ask_system", SYSTEM_PROMPT, currentUserId());
        String answer;
        try {
            answer = genText(AiFeatures.ASK, sysPrompt.content, userPrompt, history, currentUserId());
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
        // P2-1 归因：记录本次使用的 prompt 版本（0 = 代码兜底版）
        java.util.Map<String, Integer> pv = new java.util.LinkedHashMap<>();
        pv.put("ai_ask_system", sysPrompt.version);
        pv.put("ai_ask_rewrite", prompt("ai_ask_rewrite", REWRITE_PROMPT, null).version);
        pv.put("ai_ask_rerank", prompt("ai_ask_rerank", RERANK_PROMPT, null).version);
        vo.setPromptVersions(pv);
        // 回答成功：持久化会话记忆 + 沉淀语义记忆（Redis/PGVector，全部 fail-open 异步无关紧要）
        persistMemory(currentUserId(), q, answer.trim(), r);
        semanticCacheService.store(q, currentUserId(), vo.getAnswer(), r.sources);
        checkFaithfulness(vo, r);
        log.info("[AiAsk] question={}, hits={}, sources={}, latency={}ms, promptVersions={}",
            truncate(q, 50), r.hits, r.sources.size(), vo.getLatencyMs(), pv);
        // 消费漏斗：生成成功（vo 返回给用户）
        funnelMeter.incr(AiFeatures.ASK, com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_GENERATED);
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
            // 消费漏斗：缓存命中（省掉检索与生成）
            funnelMeter.incr(AiFeatures.ASK_STREAM, com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_CACHE_HIT);
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
        // 消费漏斗：检索管线执行完毕（含 hits==0）
        funnelMeter.incr(AiFeatures.ASK_STREAM, com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_RECALL_DONE);
        if (r.hits == 0) {
            return emptyAnswer(start);
        }
        String userPrompt = buildUser(r.docsText, q, r.queryEmbedding, userId);
        com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt sysPrompt =
            prompt("ai_ask_system", SYSTEM_PROMPT, userId);
        StringBuilder acc = new StringBuilder();
        boolean cancelled = false;
        try {
            genStream(AiFeatures.ASK_STREAM, sysPrompt.content, userPrompt, history, userId,
                delta -> {
                    if (acc.length() == 0) {
                        // P2-3 TTFT：首 token 延迟（检索 + 模型首响应总耗时）
                        aiMetricsCollector.record("aiask_stream_ttft", System.currentTimeMillis() - start);
                    }
                    acc.append(delta);
                    onDelta.accept(delta);
                });
        } catch (java.util.concurrent.CancellationException e) {
            // P2-3 流式取消：客户端断开，gateway 抛 CancellationException 传播至此。
            // 丢弃已生成部分：不落语义缓存、不写记忆、不再做护栏/忠实度校验
            cancelled = true;
        } catch (Exception e) {
            log.error("[AiAsk-stream] 生成失败, question={}", truncate(q, 40), e);
        }
        if (cancelled) {
            aiMetricsCollector.incr("aiask_stream_cancelled");
            log.info("[AiAsk-stream] 客户端取消，丢弃已生成 {} 字符, question={}", acc.length(), truncate(q, 40));
            return null;
        }
        if (acc.length() == 0) {
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
        // P2-1 归因：流式路径仅 system prompt 参与
        vo.setPromptVersions(java.util.Map.of("ai_ask_system", sysPrompt.version));
        // 回答成功：持久化会话记忆 + 沉淀语义记忆
        persistMemory(userId, q, vo.getAnswer(), r);
        semanticCacheService.store(q, userId, vo.getAnswer(), r.sources);
        checkFaithfulness(vo, r);
        log.info("[AiAsk-stream] question={}, sources={}, latency={}ms", truncate(q, 40), r.sources.size(), vo.getLatencyMs());
        // 消费漏斗：生成成功（vo 返回给用户）
        funnelMeter.incr(AiFeatures.ASK_STREAM, com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_GENERATED);
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
        // 消费漏斗：检索管线执行完毕（含 hits==0）
        funnelMeter.incr(AiFeatures.ASK_FAST, com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_RECALL_DONE);
        if (r.hits == 0) {
            return emptyAnswer(start);
        }
        String userPrompt = buildUser(r.docsText, q, r.queryEmbedding, currentUserId());
        com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt sysPrompt =
            prompt("ai_ask_system", SYSTEM_PROMPT, currentUserId());
        String answer;
        try {
            answer = genText(AiFeatures.ASK_FAST, sysPrompt.content, userPrompt, history, currentUserId());
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
        // P2-1 归因：fast 路径仅 system prompt 参与
        vo.setPromptVersions(java.util.Map.of("ai_ask_system", sysPrompt.version));
        // 回答成功：持久化会话记忆 + 沉淀语义记忆
        persistMemory(currentUserId(), q, answer.trim(), r);
        semanticCacheService.store(q, currentUserId(), vo.getAnswer(), r.sources);
        checkFaithfulness(vo, r);
        log.info("[AiAsk-fast] question={}, sources={}, latency={}ms", truncate(q, 40), r.sources.size(), vo.getLatencyMs());
        // 消费漏斗：生成成功（vo 返回给用户）
        funnelMeter.incr(AiFeatures.ASK_FAST, com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_GENERATED);
        return vo;
    }

    /**
     * 存量文章向量回填/刷新（P0-1 数据新鲜度）。每天凌晨执行，限量防压外部服务。
     *
     * <p>与改造前的区别：不再只判断"向量是否存在"，而是<b>比对来源正文的内容指纹（SHA-256）</b>——
     * <ul>
     *   <li>缺向量/缺分块 → 补写；</li>
     *   <li>有向量但指纹与当前正文不一致（文章被编辑过）→ <b>重算</b>（这是改造前完全漏掉的场景）；</li>
     *   <li>指纹一致 → 跳过（不做无谓 embedding 调用）。</li>
     * </ul>
     * 顺带清理「非已发布文章的残留向量」（下架/删除后不再被检索到）。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void backfillEmbeddings() {
        int batch = 200;
        long lastId = 0L;
        int scanned = 0;
        int filled = 0;
        int refreshed = 0;
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
                    RefreshResult r = refreshIfStale(a);
                    filled += r.articleWritten;
                    refreshed += r.articleRefreshed;
                    chunked += r.chunkWritten;
                }
                if (articles.size() < batch) {
                    break;
                }
            }
            log.info("[AiAsk] 向量回填完成: 扫描={}, 新增文章向量={}, 刷新过期向量={}, 补分块={}",
                    scanned, filled, refreshed, chunked);
        } catch (Exception e) {
            log.error("[AiAsk] 向量回填异常", e);
        }
        // 残留清理：非已发布（下架/删除/驳回）文章不再保留向量，避免 RAG 检索到过期内容
        cleanupOrphanEmbeddings();
    }

    /**
     * 增量同步（P0-1）：每 10 分钟对已发布文章做一批「内容指纹比对」，变了就重算向量。
     *
     * <p><b>为什么是轮转而不是"扫最近更新"</b>：{@code ap_article} 表没有 updated_time 列
     * （只有 created_time / publish_time），无法按"最近修改"筛选。因此改为
     * <b>游标轮转扫描</b>：用 Redis 记住上次扫到的 article_id，每次取下一批（500 篇）比对，
     * 扫到结尾回到 0 重新轮转 —— 全量 N 篇一轮耗时 ≈ N/500 × 10 分钟，
     * 把"编辑 → 向量更新"的延迟从 24 小时（每日全量）压到小时级，且不依赖任何时间列。
     *
     * <p>编辑后需要立即生效的场景由发布链路保证（编辑重新发布时会走
     * {@code SimilarityProcessor} → 直接重写向量与指纹），本任务是兜底。
     */
    @Scheduled(cron = "0 */10 * * * ?")
    public void syncStaleEmbeddings() {
        try {
            long cursor = readSweepCursor();
            List<ApArticle> batch = apArticleMapper.selectList(
                new LambdaQueryWrapper<ApArticle>()
                    .eq(ApArticle::getStatus, Status.PUBLISHED.getCode())
                    .gt(ApArticle::getId, cursor)
                    .orderByAsc(ApArticle::getId)
                    .last("LIMIT " + SWEEP_BATCH));
            if (batch.isEmpty()) {
                // 一轮扫完 → 游标归零，下轮从头开始
                writeSweepCursor(0L);
                return;
            }
            int refreshed = 0;
            for (ApArticle a : batch) {
                RefreshResult r = refreshIfStale(a);
                if (r.articleRefreshed > 0 || r.articleWritten > 0 || r.chunkWritten > 0) {
                    refreshed++;
                }
            }
            writeSweepCursor(batch.get(batch.size() - 1).getId());
            if (refreshed > 0) {
                log.info("[AiAsk] 向量轮转同步: 本批={}, 实际刷新={}, 游标={}-{}",
                        batch.size(), refreshed, batch.get(0).getId(), batch.get(batch.size() - 1).getId());
            }
        } catch (Exception e) {
            log.error("[AiAsk] 增量向量同步异常", e);
        }
    }

    /** 读取轮转游标（Redis 不可用则返回 0，退化为每次从头扫，不影响正确性） */
    private long readSweepCursor() {
        try {
            String v = cacheService.getstringRedisTemplate().opsForValue().get(EMBED_SWEEP_CURSOR_KEY);
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }

    private void writeSweepCursor(long cursor) {
        try {
            cacheService.getstringRedisTemplate().opsForValue().set(EMBED_SWEEP_CURSOR_KEY, String.valueOf(cursor));
        } catch (Exception e) {
            log.debug("[AiAsk] 写入轮转游标失败: {}", e.getMessage());
        }
    }

    /** 单篇刷新结果（文章级写入/刷新、分块写入，用于汇总日志） */
    private static final class RefreshResult {
        int articleWritten;
        int articleRefreshed;
        int chunkWritten;
    }

    /**
     * 单篇文章的向量新鲜度处理（全量回填与增量任务共用同一口径）。
     *
     * <p>判定：正文指纹与向量记录不一致（或缺失）→ 重算；一致 → 跳过。
     */
    private RefreshResult refreshIfStale(ApArticle article) {
        RefreshResult r = new RefreshResult();
        Long articleId = article.getId();
        try {
            ApArticleContent c = contentMapper.selectOne(
                new LambdaQueryWrapper<ApArticleContent>()
                    .eq(ApArticleContent::getArticleId, articleId)
                    .last("LIMIT 1"));
            if (c == null || c.getContent() == null || c.getContent().isBlank()) {
                return r; // 无正文（数据异常）→ 不动，避免误删已有向量
            }
            String currentHash = ArticleEmbeddingServiceImpl.contentHash(c.getContent());
            if (currentHash == null) {
                return r;
            }

            ArticleEmbeddingServiceImpl.EmbeddingMeta vecMeta = embeddingService.getEmbeddingMeta(articleId);
            ArticleEmbeddingServiceImpl.EmbeddingMeta chunkMeta = embeddingService.getChunksMeta(articleId);

            // D 兜底（2026-09-18）：已判水文的文章不应留在向量库。
            // 覆盖审核链仍可能漏掉的两类历史脏数据：① L1 在 45~69 未打标先入库、事后 L2/L3 升标；
            // ② 打标与入库的历史竞态残留。判据直接读 is_aigc，与检索侧过滤口径一致。
            if (article.getIsAigc() != null && article.getIsAigc() == 1) {
                if (vecMeta != null || chunkMeta != null) {
                    embeddingService.deleteEmbedding(articleId);
                    embeddingService.deleteChunks(articleId);
                    log.info("[AiAsk] 清理已判水文的残留向量, articleId={}", articleId);
                }
                return r;
            }

            boolean vecMissing = vecMeta == null;
            boolean chunksMissing = chunkMeta == null;
            boolean vecStale = vecMissing
                || ArticleEmbeddingServiceImpl.isStale(vecMeta.contentHash, currentHash);
            boolean chunksStale = chunksMissing
                || ArticleEmbeddingServiceImpl.isStale(chunkMeta.contentHash, currentHash);
            if (!vecStale && !chunksStale) {
                return r; // 版本一致，跳过（不做无谓 embedding 调用）
            }

            if (vecStale) {
                double[] emb = embeddingService.generateEmbedding(truncate(c.getContent(), 2000));
                if (emb != null) {
                    embeddingService.saveEmbedding(articleId, emb, currentHash, article.getPublishTime());
                    if (vecMissing) {
                        r.articleWritten = 1;
                    } else {
                        r.articleRefreshed = 1;
                        log.info("[AiAsk] 内容已变更，重算文章向量: articleId={}, hash={}",
                                articleId, currentHash.substring(0, 8));
                    }
                }
            }
            if (chunksStale) {
                embeddingService.saveChunks(articleId, TextChunker.split(
                    MarkdownUtils.normalizeContent(c.getContent()),
                    TextChunker.DEFAULT_TARGET, TextChunker.DEFAULT_OVERLAP,
                    embeddingService.getChunkMaxPerArticle()), currentHash, article.getPublishTime());
                r.chunkWritten = 1;
            }
        } catch (Exception e) {
            log.warn("[AiAsk] 刷新向量失败 articleId={}", articleId, e);
        }
        return r;
    }

    /**
     * 清理「非可检索文章的残留向量」：把 PG 侧有向量的 article_id 分批拿到 MySQL 校验状态，
     * 非 PUBLISHED（下架/删除/审核驳回）、**已判 AIGC 水文（is_aigc=1）** 或文章已不存在
     * → 删除其向量与分块。
     *
     * <p>跨库无法 JOIN，故按 PG → MySQL 单向校验；每轮最多处理 20 批 × 200 条，避免长事务。
     */
    private void cleanupOrphanEmbeddings() {
        int batch = 200;
        long lastId = 0L;
        int scanned = 0;
        int removed = 0;
        try {
            for (int page = 0; page < 20; page++) {
                List<Long> ids = embeddingService.listEmbeddedArticleIds(lastId, batch);
                if (ids == null || ids.isEmpty()) {
                    break;
                }
                scanned += ids.size();
                lastId = ids.get(ids.size() - 1);
                // 一次性查回这些 id 的发布状态（MySQL 侧；未查到 = 文章已删除）
                // 判据与检索侧口径一致：仅「已发布 且 未判水文」的文章才允许保留向量
                List<ApArticle> existing = apArticleMapper.selectList(
                    new LambdaQueryWrapper<ApArticle>()
                        .in(ApArticle::getId, ids)
                        .eq(ApArticle::getStatus, Status.PUBLISHED.getCode())
                        .and(w -> w.isNull(ApArticle::getIsAigc).or().ne(ApArticle::getIsAigc, 1)));
                java.util.Set<Long> publishedIds = new java.util.HashSet<>();
                for (ApArticle a : existing) {
                    publishedIds.add(a.getId());
                }
                for (Long id : ids) {
                    if (!publishedIds.contains(id)) {
                        embeddingService.deleteEmbedding(id);
                        embeddingService.deleteChunks(id);
                        removed++;
                    }
                }
                if (ids.size() < batch) {
                    break;
                }
            }
            if (removed > 0) {
                log.info("[AiAsk] 残留向量清理完成: 校验={}, 删除={}", scanned, removed);
            }
        } catch (Exception e) {
            log.warn("[AiAsk] 残留向量清理异常: {}", e.getMessage());
        }
    }


    /**
     * 同步文本生成统一入口（P0-2 改造）：委托 {@link com.zhuri.coding.content.service.ai.AiLlmGateway}，
     * 由 gateway 装配安全三层防御 advisor + 自动计量 token；本方法只负责构建请求级会话记忆窗口。
     *
     * @param feature 功能标识（成本归因维度，见 {@link AiFeatures}）
     */
    private String genText(String feature, String systemPrompt, String user,
                           java.util.List<java.util.Map<String, String>> history, Integer userId) {
        return llmGateway.generateOrNull(feature, systemPrompt, user,
                buildConversationMemory(userId, history), MEMORY_CONVERSATION_ID);
    }

    /** 流式生成统一入口（P0-2 改造）：委托 gateway（含流式 token 计量），记忆窗口语义不变 */
    private String genStream(String feature, String systemPrompt, String user,
                             java.util.List<java.util.Map<String, String>> history, Integer userId,
                             java.util.function.Consumer<String> onDelta) {
        return llmGateway.generateStreamOrNull(feature, systemPrompt, user,
                buildConversationMemory(userId, history), MEMORY_CONVERSATION_ID, onDelta);
    }

    /** 统一检索管线产物：参考资料文本 + 来源列表 + 命中数 + 查询向量（供语义记忆复用） */
    private static final class Retrieval {
        final String docsText;
        final List<AiSourceVo> sources;
        final int hits;
        /** 本次查询的向量（改写后），供长期记忆沉淀/召回复用，避免重复 embedding 调用 */
        final double[] queryEmbedding;
        /** 本次召回的文章实体（P2-3 冷启动：即时兴趣沉淀取标签用） */
        final List<ApArticle> articles;

        Retrieval(String docsText, List<AiSourceVo> sources, int hits, double[] queryEmbedding,
                  List<ApArticle> articles) {
            this.docsText = docsText;
            this.sources = sources;
            this.hits = hits;
            this.queryEmbedding = queryEmbedding;
            this.articles = articles == null ? new ArrayList<>() : articles;
        }
    }

    /**
     * 统一检索管线（显式链版）：委托 {@link com.zhuri.coding.content.service.ai.pipeline.AskRetrievalChain}
     * 按「向量化 → 混合召回 → 过滤已发布 → LLM Rerank → 组装」五阶段执行（Prompt Chaining 落地）。
     *
     * <p>ask / askFast / streamFastAsk 三处共用；运行期常量（召回宽口径/精排阈值/正文截断等）
     * 经 {@code ChainCtx} 透传，链不再依赖本服务的私有常量。
     *
     * @param searchQuery  向量检索用查询（普通路径即问题原文；完整路径为 rewrite 后文本）
     * @param userQuestion 原始问题（用于 LLM Rerank；null 或 doRerank=false 则跳过精排）
     * @param topK         最终取前 N 篇
     * @param doRerank     候选达到阈值时是否启用 LLM 精排（完整路径 true，fast/流式 false）
     * @return null=向量化失败（调用方降级）；hits==0 表示无命中（调用方返回空答案）
     */
    private Retrieval retrieveAndAssemble(String searchQuery, String userQuestion, int topK, boolean doRerank) {
        com.zhuri.coding.content.service.ai.pipeline.AskRetrievalChain.ChainResult cr =
            retrievalChain.run(new com.zhuri.coding.content.service.ai.pipeline.AskRetrievalChain.ChainCtx(
                searchQuery, userQuestion, topK, doRerank, RECALL_TOPK, RERANK_MIN_CANDIDATES, CONTEXT_CHARS, RERANK_PROMPT));
        if (cr == null) {
            return null;
        }
        return new Retrieval(cr.docsText(), cr.sources(), cr.hits(), cr.queryEmbedding(), cr.articles());
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
            ApUser u = com.zhuri.coding.utils.thread.AppThreadLocalUtil.getUser();
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

    /** 回答成功后的记忆写回：会话记忆（Redis）+ 语义记忆（PGVector）+ 即时兴趣（P2-3 冷启动），全部 fail-open 不影响主链路 */
    private void persistMemory(Integer userId, String question, String answer, Retrieval r) {
        if (userId == null || answer == null || answer.isBlank()) {
            return;
        }
        try {
            conversationMemoryService.appendTurn(userId, question, answer);
        } catch (Exception e) {
            log.debug("[AiAsk] 会话记忆持久化失败, userId={}", userId, e);
        }
        try {
            userMemoryService.remember(userId, question, r.queryEmbedding);
        } catch (Exception e) {
            log.debug("[AiAsk] 语义记忆沉淀失败, userId={}", userId, e);
        }
        // 冷启动即时兴趣：画像为空的用户从本次召回文章标签沉淀，第二问起即有个性化参考
        try {
            if (r.articles != null && !r.articles.isEmpty()) {
                List<Long> ids = new ArrayList<>();
                for (ApArticle a : r.articles) {
                    ids.add(a.getId());
                }
                userInterestService.learnFromQuery(userId, ids);
            }
        } catch (Exception e) {
            log.debug("[AiAsk] 即时兴趣沉淀失败, userId={}", userId, e);
        }
    }

    private Integer currentUserId() {
        ApUser u = com.zhuri.coding.utils.thread.AppThreadLocalUtil.getUser();
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
            final java.util.List<com.zhuri.coding.model.article.dtos.AiSourceVo> sources = r.sources;
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

    /**
     * Prompt 注册表解析（P2-1）：注册表未注入（纯单测）或解析失败时退回代码常量（version=0）。
     */
    private com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt prompt(
        String key, String fallback, Integer userId) {
        if (promptRegistry == null) {
            return new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
        try {
            return promptRegistry.resolve(key, fallback, userId);
        } catch (Exception e) {
            log.warn("[AiAsk] prompt 注册表解析异常，走代码兜底, key={}", key);
            return new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
    }

    /** Query Rewrite：一次小模型调用（失败返回 null -> 调用方回退原文）；内部调用不触碰会话持久化 */
    private String queryRewrite(String question) {
        try {
            String sys = prompt("ai_ask_rewrite", REWRITE_PROMPT, null).content;
            String raw = genText(AiFeatures.REWRITE, sys, question, null, null);
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
