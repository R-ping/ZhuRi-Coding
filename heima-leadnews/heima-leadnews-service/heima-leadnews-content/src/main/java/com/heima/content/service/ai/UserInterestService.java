package com.heima.content.service.ai;

/**
 * 用户兴趣画像（轻量版，供 AI 问答个性化参考）
 *
 * <p>画像来源：用户近 30 天收藏的文章 → 聚合文章标签频次取 TopN。
 * 画像仅作"参考"注入 Prompt，提示模型与问题无关时忽略，避免诱导模型编造。
 */
public interface UserInterestService {

    /** 兴趣标签上限 */
    int MAX_TAGS = 5;

    /**
     * 构建用户兴趣标签列表（无足够行为数据返回空列表，调用方跳过注入）
     */
    java.util.List<String> buildInterestTags(Integer userId);
}
