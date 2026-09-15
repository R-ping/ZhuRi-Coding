package com.heima.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.ai.AnswerFaithfulnessService;
import com.heima.content.service.ai.AiSemanticCacheService;
import com.heima.content.service.ai.HybridRecallService;
import com.heima.content.service.ai.HybridRecallService.Recall;
import com.heima.content.service.ai.memory.AiConversationMemoryService;
import com.heima.content.service.ai.memory.UserMemoryService;
import com.heima.content.service.ai.spring.PromptSafetyAdvisor;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.model.article.dtos.AiAnswerVo;
import com.heima.model.article.dtos.AiSourceVo;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleContent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

/**
 * 社区 AI 问答（RAG）单测。
 *
 * <p>覆盖：空/超长问题短路；语义缓存命中快速返回（省模型调用）；完整链路（Query Rewrite →
 * 混合召回 → 组装参考资料 → 生成 → 持久化会话与语义记忆）；流式链路（增量回调 + 输出护栏放行 +
 * 记忆写回）；向量回填游标分页与幂等跳过。
 * 兜底路径使用真实空组件 PromptSafetyAdvisor（sanitizer/guard 均 null），保证 Advisor 链不 NPE 且可精确打桩 chatModel。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("社区 AI 问答（AiAskService：RAG 检索 + 记忆持久化）")
class AiAskServiceImplTest {

    private static final double[] VEC = {0.1, 0.2, 0.3, 0.4};
    private static final String ANSWER = "Redis 分布式锁通过 SETNX 与 Lua 脚本保证原子性，并需要续期与防误删机制。";

    @Mock private ArticleEmbeddingServiceImpl embeddingService;
    @Mock private AiSemanticCacheService semanticCacheService;
    @Mock private HybridRecallService hybridRecallService;
    @Mock private AnswerFaithfulnessService faithfulnessService;
    @Mock private Executor aiSseExecutor;
    @Mock private AiConversationMemoryService conversationMemoryService;
    @Mock private UserMemoryService userMemoryService;
    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApArticleContentMapper contentMapper;
    /** 统一 LLM 出口（P0-2）：AiAsk 已改为委托 gateway，模型调用桩打在这里 */
    @Mock private com.heima.content.service.ai.AiLlmGateway llmGateway;

    @Mock private com.heima.content.service.ai.AiMetricsCollector aiMetricsCollector;

    @Mock private com.heima.content.service.ai.AiFunnelMeter funnelMeter;

    @InjectMocks
    private AiAskServiceImpl service;

    @BeforeEach
    void installNoopAdvisor() {
        // genText/genStream 走 ChatClient.defaultAdvisors(promptSafetyAdvisor)：用真实空组件保证链不 NPE
        ReflectionTestUtils.setField(service, "promptSafetyAdvisor", new PromptSafetyAdvisor(null, null));
        ReflectionTestUtils.setField(service, "faithfulnessMode", "async");
    }

    /** 按 system 提示词路由 gateway 非流式调用：改写 / 生成 两条路径稳定响应 */
    private void stubChatModelBySystem() {
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenAnswer(inv -> {
                String sys = inv.getArgument(1);
                return sys != null && sys.contains("搜索查询改写器") ? "Redis 锁原理与实现" : ANSWER;
            });
    }

    private void stubRetrieval(boolean published) {
        when(embeddingService.generateEmbedding(anyString())).thenReturn(VEC);
        when(hybridRecallService.recall(anyString(), any(double[].class), anyInt()))
            .thenReturn(new Recall(List.of(1L), Map.of(1L, 0.92)));
        ApArticle a = article(1L, "Redis 分布式锁实战");
        if (published) {
            when(apArticleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(a));
            ApArticleContent c = new ApArticleContent();
            c.setArticleId(1L);
            c.setContent("正文：SETNX、Lua 脚本、看门狗续期……");
            when(contentMapper.selectList(any())).thenReturn(List.of(c));
        } else {
            when(apArticleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of());
        }
    }

    private ApArticle article(Long id, String title) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setTitle(title);
        a.setAuthorId(7L);
        a.setStatus(Status.PUBLISHED.getCode());
        a.setIsAigc(0);
        return a;
    }

    // ==================== ask：短路与语义缓存 ====================

    @Test
    @DisplayName("空白或超长问题直接返回 null")
    void askBlankOrOverlongQuestion() {
        assertNull(service.ask("   ", null, null, null));
        assertNull(service.ask("q".repeat(201), null, null, null));
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("语义缓存命中：直接返回缓存答案，跳过检索与模型调用")
    void askCacheHitFastReturn() {
        AiAnswerVo cached = new AiAnswerVo();
        cached.setAnswer("缓存答案");
        cached.setSources(List.of());
        // currentUserId() 无登录态 → null
        when(semanticCacheService.lookup("缓存命中问题", null)).thenReturn(cached);

        AiAnswerVo vo = service.ask("缓存命中问题", null, null, null);

        assertNotNull(vo);
        assertEquals("缓存答案", vo.getAnswer());
        verify(embeddingService, never()).generateEmbedding(anyString());
        verify(llmGateway, never()).generateOrNull(anyString(), anyString(), anyString(), any(), any());
    }

    // ==================== ask：完整 RAG 链路 ====================

    @Test
    @DisplayName("完整链路：Rewrite → 混合召回 → 生成 → 语义缓存落库")
    void askFullPipeline() {
        when(semanticCacheService.lookup(anyString(), any())).thenReturn(null);
        stubRetrieval(true);
        stubChatModelBySystem();

        AiAnswerVo vo = service.ask("Redis 分布式锁怎么实现", null, false, null);

        assertNotNull(vo);
        assertEquals(ANSWER, vo.getAnswer());
        assertEquals(1, vo.getSources().size());
        assertEquals(1L, vo.getSources().get(0).getArticleId());
        assertEquals("Redis 分布式锁实战", vo.getSources().get(0).getTitle());
        assertEquals(0.92, vo.getSources().get(0).getSimilarity());
        // 无登录态（userId=null）：记忆写回全部跳过，但语义缓存照常落库
        verify(conversationMemoryService, never()).appendTurn(any(), anyString(), anyString());
        verify(semanticCacheService).store(anyString(), any(), anyString(), any(List.class));
    }

    @Test
    @DisplayName("无命中时返回知识库兜底文案")
    void askNoHitReturnsEmptyAnswer() {
        when(semanticCacheService.lookup(anyString(), any())).thenReturn(null);
        stubRetrieval(false);

        AiAnswerVo vo = service.ask("完全不存在的冷门问题", null, false, null);

        assertNotNull(vo);
        assertTrue(vo.getAnswer().contains("暂未检索到"));
        assertTrue(vo.getSources().isEmpty());
    }

    @Test
    @DisplayName("向量化失败时返回 null（调用方降级）")
    void askEmbeddingFailureReturnsNull() {
        when(semanticCacheService.lookup(anyString(), any())).thenReturn(null);
        when(embeddingService.generateEmbedding(anyString())).thenReturn(null);

        assertNull(service.ask("问题", null, false, null));
    }

    // ==================== 消费漏斗（AI 问答链路阶段埋点） ====================

    @Test
    @DisplayName("完整链路：ask 依次计数 recall_done → generated（feature=ask）")
    void askFunnelFullPipeline() {
        when(semanticCacheService.lookup(anyString(), any())).thenReturn(null);
        stubRetrieval(true);
        stubChatModelBySystem();

        AiAnswerVo vo = service.ask("Redis 分布式锁怎么实现", null, false, null);

        assertNotNull(vo);
        verify(funnelMeter).incr(com.heima.content.service.ai.AiFeatures.ASK,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_RECALL_DONE);
        verify(funnelMeter).incr(com.heima.content.service.ai.AiFeatures.ASK,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_GENERATED);
        verify(funnelMeter, never()).incr(com.heima.content.service.ai.AiFeatures.ASK,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_CACHE_HIT);
    }

    @Test
    @DisplayName("缓存命中：只计数 cache_hit，不产生 recall_done / generated")
    void askFunnelCacheHitOnly() {
        AiAnswerVo cached = new AiAnswerVo();
        cached.setAnswer("缓存答案");
        cached.setSources(List.of());
        when(semanticCacheService.lookup("缓存命中问题", null)).thenReturn(cached);

        AiAnswerVo vo = service.ask("缓存命中问题", null, null, null);

        assertNotNull(vo);
        verify(funnelMeter).incr(com.heima.content.service.ai.AiFeatures.ASK,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_CACHE_HIT);
        verify(funnelMeter, never()).incr(com.heima.content.service.ai.AiFeatures.ASK,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_RECALL_DONE);
        verify(funnelMeter, never()).incr(com.heima.content.service.ai.AiFeatures.ASK,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_GENERATED);
    }

    @Test
    @DisplayName("fast 模式：检索与生成按 ask_fast 计数（与完整问答区分成本档位）")
    void askFunnelFastFeature() {
        when(semanticCacheService.lookup(anyString(), any())).thenReturn(null);
        stubRetrieval(true);
        stubChatModelBySystem();

        AiAnswerVo vo = service.ask("Redis 锁怎么实现", null, true, null);

        assertNotNull(vo);
        verify(funnelMeter).incr(com.heima.content.service.ai.AiFeatures.ASK_FAST,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_RECALL_DONE);
        verify(funnelMeter).incr(com.heima.content.service.ai.AiFeatures.ASK_FAST,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_GENERATED);
        verify(funnelMeter, never()).incr(com.heima.content.service.ai.AiFeatures.ASK,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_GENERATED);
    }

    // ==================== streamFastAsk ====================

    @Test
    @DisplayName("流式：语义缓存命中按 chunk 回放并补记会话记忆")
    void streamFastAskCacheHitReplaysDeltas() {
        AiAnswerVo cached = new AiAnswerVo();
        cached.setAnswer("缓存答案内容" + "x".repeat(100));
        cached.setSources(List.of());
        when(semanticCacheService.lookup("缓存问题", 7)).thenReturn(cached);

        AtomicInteger chunks = new AtomicInteger();
        AiAnswerVo vo = service.streamFastAsk("缓存问题", null, null, d -> chunks.incrementAndGet(), 7);

        assertNotNull(vo);
        assertTrue(chunks.get() >= 2, "长答案应切成多个 delta 回放");
        verify(conversationMemoryService).appendTurn(7, "缓存问题", cached.getAnswer());
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("流式：正常链路逐段回调，输出护栏放行，成功后写回记忆")
    void streamFastAskFullPath() {
        when(semanticCacheService.lookup(anyString(), any())).thenReturn(null);
        stubRetrieval(true);
        // 流式生成：gateway 逐段回调增量文本并返回完整文本
        when(llmGateway.generateStreamOrNull(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenAnswer(inv -> {
                java.util.function.Consumer<String> cb = inv.getArgument(5);
                String text = "第一段回答。";
                if (cb != null) {
                    cb.accept(text);
                }
                return text;
            });
        when(conversationMemoryService.load(7)).thenReturn(List.of());

        AtomicBoolean deltaReceived = new AtomicBoolean(false);
        AiAnswerVo vo = service.streamFastAsk("Redis 怎么实现分布式锁", null, null,
            d -> deltaReceived.set(true), 7);

        assertNotNull(vo);
        assertTrue(deltaReceived.get(), "增量回调应收到文本");
        assertTrue(vo.getAnswer().contains("第一段回答"));
        // 成功后会话记忆 + 语义记忆写回
        verify(conversationMemoryService).appendTurn(7, "Redis 怎么实现分布式锁", vo.getAnswer());
        verify(userMemoryService).remember(7, "Redis 怎么实现分布式锁", VEC);
        verify(semanticCacheService).store(anyString(), any(), anyString(), any(List.class));
    }

    // ==================== 向量回填 ====================

    @Test
    @DisplayName("向量回填：缺失向量与分块的文章补齐全量库，游标推进直到扫完")
    void backfillEmbeddingsFillsMissing() {
        ApArticle a1 = article(5L, "老文章");
        when(apArticleMapper.selectList(any()))
            .thenReturn(List.of(a1))
            .thenReturn(List.of());
        // P0-1：缺向量/缺分块 → 元信息为 null（改造前是 getEmbedding()==null / hasChunks()==false）
        when(embeddingService.getEmbeddingMeta(5L)).thenReturn(null);
        when(embeddingService.getChunksMeta(5L)).thenReturn(null);
        when(contentMapper.selectOne(any())).thenReturn(contentOf("老文章正文内容，用于向量化。"));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(VEC);
        when(embeddingService.getChunkMaxPerArticle()).thenReturn(20);
        // findSimilar... 无需 stub；saveEmbedding/saveChunks 为 void

        service.backfillEmbeddings();

        // 写入时一并落下内容指纹（P0-1：向量与内容版本绑定）
        verify(embeddingService).saveEmbedding(eq(5L), eq(VEC), anyString(), any());
        verify(embeddingService).saveChunks(eq(5L), any(List.class), anyString(), any());
        // 游标推进：第二页返回空 → 结束
        verify(apArticleMapper).selectList(any());
    }

    @Test
    @DisplayName("向量回填：内容已变更（指纹不一致）→ 重算向量（P0-1 核心场景）")
    void backfillEmbeddingsRefreshesStaleArticle() {
        ApArticle a1 = article(5L, "被编辑过的文章");
        when(apArticleMapper.selectList(any()))
            .thenReturn(List.of(a1))
            .thenReturn(List.of());
        String bodyNow = "编辑后的正文内容，与向量里的版本不同。";
        when(contentMapper.selectOne(any())).thenReturn(contentOf(bodyNow));
        // 向量记录里存的是"编辑前"的指纹 → 判定过期，应重算
        String outdatedHash = com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl
                .contentHash("编辑前的正文内容。");
        java.util.Date srcTime = new java.util.Date();
        when(embeddingService.getEmbeddingMeta(5L)).thenReturn(
            new com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl.EmbeddingMeta(outdatedHash, srcTime));
        when(embeddingService.getChunksMeta(5L)).thenReturn(
            new com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl.EmbeddingMeta(outdatedHash, srcTime));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(VEC);
        when(embeddingService.getChunkMaxPerArticle()).thenReturn(20);

        service.backfillEmbeddings();

        // 重算并以"当前正文指纹"覆盖写入
        String expectedHash = com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl.contentHash(bodyNow);
        verify(embeddingService).saveEmbedding(eq(5L), eq(VEC), eq(expectedHash), any());
        verify(embeddingService).saveChunks(eq(5L), any(List.class), eq(expectedHash), any());
    }

    @Test
    @DisplayName("向量回填：指纹一致则跳过（不做无谓 embedding 调用，省成本）")
    void backfillEmbeddingsSkipsReady() {
        ApArticle a1 = article(5L, "新文章");
        when(apArticleMapper.selectList(any()))
            .thenReturn(List.of(a1))
            .thenReturn(List.of());
        String body = "新文章正文内容，用于向量化。";
        when(contentMapper.selectOne(any())).thenReturn(contentOf(body));
        // 向量记录里的指纹与当前正文一致 → 版本新鲜，跳过
        String freshHash = com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl.contentHash(body);
        when(embeddingService.getEmbeddingMeta(5L)).thenReturn(
            new com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl.EmbeddingMeta(freshHash, new java.util.Date()));
        when(embeddingService.getChunksMeta(5L)).thenReturn(
            new com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl.EmbeddingMeta(freshHash, new java.util.Date()));

        service.backfillEmbeddings();

        verify(embeddingService, never()).saveEmbedding(any(), any(), anyString(), any());
        verify(embeddingService, never()).saveChunks(any(), any(List.class), anyString(), any());
    }

    private ApArticleContent contentOf(String text) {
        ApArticleContent c = new ApArticleContent();
        c.setArticleId(5L);
        c.setContent(text);
        return c;
    }
}