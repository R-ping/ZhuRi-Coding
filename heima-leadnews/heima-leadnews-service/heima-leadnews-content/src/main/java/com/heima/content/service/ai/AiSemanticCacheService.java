package com.heima.content.service.ai;

import com.heima.model.article.dtos.AiAnswerVo;
import com.heima.model.article.dtos.AiSourceVo;
import java.util.List;

/**
 * AI 问答语义缓存（按用户隔离）。
 *
 * <p>相似问题（问题向量余弦 ≥ 阈值）直接返回历史答案，省掉 Query Rewrite / LLM Rerank / 生成
 * 三次模型调用；答案内含用户长期记忆与兴趣画像注入，故必须按用户隔离，禁止跨用户复用。
 *
 * <p>全部方法 fail-open：任何异常/不可用一律按「未命中」处理，不影响问答主链路。
 */
public interface AiSemanticCacheService {

    /**
     * 查缓存：命中返回可直接响应的答案（含来源），未命中/不可复用返回 null。
     *
     * @param question 用户原始问题
     * @param userId   当前用户（null 直接不查）
     */
    AiAnswerVo lookup(String question, Integer userId);

    /**
     * 落缓存：仅缓存成功且来源非空的答案；按用户条数上限淘汰最旧。
     *
     * @param question 用户原始问题（与 lookup 使用同一向量口径）
     * @param sources  本次回答引用的来源（用于后续存活校验）
     */
    void store(String question, Integer userId, String answer, List<AiSourceVo> sources);
}
