package com.zhuri.coding.content.service.ai;

import com.zhuri.coding.model.article.dtos.AiAnswerVo;
import com.zhuri.coding.model.article.dtos.AiSourceVo;
import java.util.List;

/**
 * AI 问答语义缓存（按用户隔离）。
 *
 * <p>相似问题（问题向量余弦 ≥ 阈值）直接返回历史答案，省掉 Query Rewrite / LLM Rerank / 生成
 * 三次模型调用；答案内含用户长期记忆与兴趣画像注入，故必须按用户隔离，禁止跨用户复用。
 *
 * <p>失效维度：TTL + 引用存活 + 语料指纹 + <b>prompt 版本</b>（P1-4：签名由 {@link PromptStamp}
 * 生成，写入时快照、命中时比对，不一致即失效——防止 prompt 更新在 TTL 内不生效、灰度期答案与归因错配）。
 *
 * <p>全部方法 fail-open：任何异常/不可用一律按「未命中」处理，不影响问答主链路。
 */
public interface AiSemanticCacheService {

    /**
     * 查缓存：命中返回可直接响应的历史答案（含来源），未命中/不可复用返回 null。
     *
     * @param question    用户原始问题
     * @param userId      当前用户（null 直接不查）
     * @param promptStamp 当前生效的 prompt 版本签名（{@link PromptStamp#of}）；空表示不校验版本
     */
    AiAnswerVo lookup(String question, Integer userId, String promptStamp);

    /**
     * 落缓存：仅缓存成功且来源非空的答案；按用户条数上限淘汰最旧。
     *
     * @param question    用户原始问题（与 lookup 使用同一向量口径）
     * @param sources     本次回答引用的来源（用于后续存活校验）
     * @param promptStamp 生成本答案所用的 prompt 版本签名；空表示不做版本绑定
     */
    void store(String question, Integer userId, String answer, List<AiSourceVo> sources,
               String promptStamp);
}
