package com.zhuri.coding.content.service.ai;

import com.zhuri.coding.model.article.dtos.AiAnswerVo;

/**
 * 社区 AI 问答（RAG）
 *
 * <p>流程：问题向量化 → pgvector 余弦 TopK 召回已发布文章 → 组装"资料+问题"上下文 → 大模型生成带来源引用的回答。
 */
public interface AiAskService {

    /**
     * 提问并生成带来源的回答。
     *
     * @param question 用户问题（≤200 字）
     * @param topK     召回数量（1~8，缺省 5）
     * @return 回答 + 来源；知识库/模型不可用或无可召回内容时返回 null（由调用方给出友好提示）
     */
    AiAnswerVo ask(String question, Integer topK, Boolean fast,
                 java.util.List<java.util.Map<String, String>> history);

    /**
     * fast 流式问答：向量召回 -> 组装上下文 -> SSE 逐段回调生成文本；返回完整结果（含 sources）。
     *
     * @param onDelta 增量文本回调（服务线程内同步调用，调用方勿阻塞）
     * @param userId  当前登录用户 id（用于会话/语义记忆持久化；SSE 异步线程 ThreadLocal 不可见，须由调用方显式传入）
     */
    AiAnswerVo streamFastAsk(String question, Integer topK,
                             java.util.List<java.util.Map<String, String>> history,
                             java.util.function.Consumer<String> onDelta, Integer userId);
}
