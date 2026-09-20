package com.heima.content.service.ai;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 单篇文章 AI 服务（Step3·① 文章速览 + 单篇问答）
 *
 * <p>与社区级 RAG 问答（AiAskService，向量检索多篇）互补：
 * 本服务不检索，上下文仅取目标文章正文（Markdown），保证答案只来自本文。
 */
public interface ArticleQaService {

    /**
     * 生成文章 AI 摘要（Redis 缓存 24h）。
     *
     * @param articleId 文章ID
     * @return 摘要文本；文章不存在/未发布/未取到正文/生成失败时返回 null（调用方按"不可用"处理）
     */
    String genSummary(Long articleId);

    /**
     * 单篇流式问答：system 限定仅依据该文章正文回答。
     *
     * @param articleId 文章ID（不存在/未发布抛 IllegalArgumentException，由调用方转为 error 事件）
     * @param question  用户问题
     * @param history   多轮会话历史（可空）
     * @param onDelta   逐段增量回调（SSE delta）
     * @return 完整回答文本
     * @throws IllegalArgumentException 文章不存在/未发布/无正文
     */
    String streamAskArticle(Long articleId, String question,
                            List<Map<String, String>> history,
                            Consumer<String> onDelta);

    /**
     * 生成"读完想问"相关问题列表（LLM 基于本文产出 3~5 个读者追问，Redis 缓存 24h）。
     * 复用单篇问答的正文上下文（loadArticleBody）与生成链路，不触发向量检索。
     *
     * @param articleId 文章ID
     * @return 问题列表（≤5）；文章不存在/未发布/生成失败返回 null
     */
    List<String> genRelatedQuestions(Long articleId);
}
