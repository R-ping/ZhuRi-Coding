package com.heima.content.service.ai;

/**
 * 用户兴趣画像（轻量版，供 AI 问答个性化参考）
 *
 * <p>画像来源分层（P2-3 冷启动改造，从上到下逐层兜底）：
 * <ol>
 *   <li>L1 收藏画像：近 30 天收藏文章 → 标签频次 TopN（老用户精准）；</li>
 *   <li>L2 即时兴趣：最近提问召回文章的标签沉淀（Redis，24h TTL）——
 *       新用户首问后第二问起即有画像，解决「冷启动期间完全无个性化」；</li>
 *   <li>L3 全站热门：PUBLISHED 文章按点赞排序取标签 TopN（纯新用户兜底）。</li>
 * </ol>
 * 画像仅作"参考"注入 Prompt，提示模型与问题无关时忽略，避免诱导模型编造。
 */
public interface UserInterestService {

    /** 兴趣标签上限 */
    int MAX_TAGS = 5;

    /**
     * 构建用户兴趣标签列表（三层兜底，均无数据时返回空列表，调用方跳过注入）
     */
    java.util.List<String> buildInterestTags(Integer userId);

    /**
     * 首问即时学习（冷启动）：把本次提问召回文章的标签沉淀为即时兴趣（Redis 24h）。
     * fail-open：任何异常静默跳过，不影响问答主链路。
     *
     * @param articleIds 本次检索召回的文章 id（空/null 直接跳过）
     */
    void learnFromQuery(Integer userId, java.util.List<Long> articleIds);
}
