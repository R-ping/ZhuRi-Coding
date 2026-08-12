package com.heima.content.service.article.impl;

import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ApArticleRecommendService;
import com.heima.model.article.dtos.ArticleRecommendDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ApArticleRecommendServiceImpl implements ApArticleRecommendService {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;
    private static final String UNTAGGED = "__untagged__";

    /** 候选池上限（性能兜底） */
    @Value("${recommend.max-candidates:2000}")
    private int maxCandidates;

    /** 候选时间窗口（天），仅取最近 windowDays 天发布的文章 */
    @Value("${recommend.window-days:7}")
    private int windowDays;

    /** 多样性配额：全局推荐序列中同一标签最多出现的篇数 */
    @Value("${recommend.max-per-tag:2}")
    private int maxPerTag;

    /** 作者上限：全局推荐序列中同一作者最多出现的篇数，0 表示不限制 */
    @Value("${recommend.max-per-author:3}")
    private int maxPerAuthor;

    /** 无标签文章归入的桶 */
    @Value("${recommend.untagged-bucket:__untagged__}")
    private String untaggedBucket;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Override
    public ResponseResult recommend(ArticleRecommendDto dto) {
        int size = (dto.getSize() == null || dto.getSize() <= 0) ? DEFAULT_SIZE : Math.min(dto.getSize(), MAX_SIZE);
        int page = (dto.getPage() == null || dto.getPage() < 0) ? 0 : dto.getPage();
        String channel = (dto.getChannel() == null || dto.getChannel().isEmpty()) ? "__all__" : dto.getChannel();

        // seed 作为会话/分页锚点。配额算法为确定性输出，seed 仅用于保持前端分页协议一致
        long seed = (dto.getSeed() != null) ? dto.getSeed() : System.currentTimeMillis();

        // 1. 查询候选池（时间窗口内，评分优先）
        Integer channelId = null;
        if (!"__all__".equals(channel)) {
            try { channelId = Integer.parseInt(channel); } catch (NumberFormatException ignored) {}
        }
        List<ApArticle> candidates = apArticleMapper.selectRecommendCandidates(channelId, maxCandidates, dto.getTagName(), windowDays);
        if (candidates == null || candidates.isEmpty()) {
            log.info("Recommend: no candidates for channel={}, windowDays={}", channel, windowDays);
            return ResponseResult.okResult(buildEmptyResponse(seed, page, size));
        }

        // 2. 计算各项指标的最大值（用于对数归一化）—— 单次遍历
        long now = System.currentTimeMillis();
        int maxViews = 0, maxLikes = 0, maxComments = 0, maxCollections = 0;
        for (ApArticle a : candidates) {
            maxViews = Math.max(maxViews, a.getViews() != null ? a.getViews() : 0);
            maxLikes = Math.max(maxLikes, a.getLikes() != null ? a.getLikes() : 0);
            maxComments = Math.max(maxComments, a.getComment() != null ? a.getComment() : 0);
            maxCollections = Math.max(maxCollections, a.getCollection() != null ? a.getCollection() : 0);
        }

        // 3. 预计算加权分数（对数归一化，弱化爆款压迫），按分数降序
        Map<Long, Double> scoreCache = new HashMap<>();
        for (ApArticle article : candidates) {
            scoreCache.put(article.getId(), computeBaseScore(article, now, maxViews, maxLikes, maxComments, maxCollections));
        }
        candidates.sort((a, b) -> Double.compare(scoreCache.get(b.getId()), scoreCache.get(a.getId())));

        // 4. 全局配额贪心：标签配额 + 作者上限，产出横向覆盖的全局序列（跨页稳定）
        List<ApArticle> globalSequence = buildGlobalSequence(candidates);

        // 5. 分页截取
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, globalSequence.size());
        boolean hasMore = toIndex < globalSequence.size();
        List<ApArticle> pageResult = fromIndex < globalSequence.size()
                ? globalSequence.subList(fromIndex, toIndex)
                : Collections.emptyList();

        // 6. 构建响应 - null-safe 处理
        List<Map<String, Object>> safeList = pageResult.stream()
                .map(ApArticle::nullSafeToMap).collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("list", safeList);
        result.put("seed", seed);
        result.put("page", page);
        result.put("size", size);
        result.put("hasMore", hasMore);
        result.put("total", globalSequence.size());

        log.info("Recommend: candidates={}, seq={}, returned {} articles, hasMore={}, seed={}, channel={}, page={}",
                candidates.size(), globalSequence.size(), pageResult.size(), hasMore, seed, channel, page);
        return ResponseResult.okResult(result);
    }

    /**
     * 全局配额贪心：对「评分降序」的候选做一次遍历，
     * 同一标签累计不超过 maxPerTag、同一作者累计不超过 maxPerAuthor，
     * 产出跨页稳定的全局推荐序列。
     * 配额计数全局累计（跨页），保证横向覆盖，避免「本页与下页同标签」。
     * 若严格配额后序列不足候选总数（标签过于集中），放开配额按评分追加补齐，保证列表可填满。
     */
    private List<ApArticle> buildGlobalSequence(List<ApArticle> candidates) {
        Map<String, Integer> tagCount = new HashMap<>();
        Map<Long, Integer> authorCount = new HashMap<>();
        List<ApArticle> seq = new ArrayList<>(candidates.size());

        for (ApArticle art : candidates) {
            String mainTag = resolveMainTag(art);
            if (tagCount.getOrDefault(mainTag, 0) >= maxPerTag) {
                continue; // 标签配额已满 → 跳过
            }
            if (maxPerAuthor > 0 && art.getAuthorId() != null
                    && authorCount.getOrDefault(art.getAuthorId(), 0) >= maxPerAuthor) {
                continue; // 作者上限已满 → 跳过
            }
            seq.add(art);
            tagCount.put(mainTag, tagCount.getOrDefault(mainTag, 0) + 1);
            if (art.getAuthorId() != null) {
                authorCount.put(art.getAuthorId(), authorCount.getOrDefault(art.getAuthorId(), 0) + 1);
            }
        }

        // 配额不足降级：候选标签集中导致序列过短，按评分追加剩余候选补齐
        if (seq.size() < candidates.size()) {
            Set<Long> seen = seq.stream().map(ApArticle::getId).collect(Collectors.toSet());
            for (ApArticle art : candidates) {
                if (!seen.contains(art.getId())) {
                    seq.add(art);
                }
            }
        }
        return seq;
    }

    /**
     * 取文章主标签（第一个），无标签文章归入 untaggedBucket。
     */
    private String resolveMainTag(ApArticle article) {
        if (article.getTags() != null && !article.getTags().isEmpty()
                && article.getTags().get(0) != null && !article.getTags().get(0).trim().isEmpty()) {
            return article.getTags().get(0).trim();
        }
        return (untaggedBucket == null || untaggedBucket.isEmpty()) ? UNTAGGED : untaggedBucket;
    }

    /**
     * 计算基础加权推荐分数。
     * 权重分配：
     *   score          × 0.25  — 编辑/系统设置的热度分
     *   recencyFactor  × 0.20  — 发布时间越近分越高（7天内线性衰减）
     *   logViews       × 0.15  — 阅读量（对数归一化）
     *   logLikes       × 0.15  — 点赞数（对数归一化）
     *   logComments    × 0.10  — 评论数（对数归一化）
     *   logCollections × 0.10  — 收藏数（对数归一化）
     * 互动指标采用对数归一化 log(1+x)/log(1+max)，弱化爆款数值的线性压制，让长尾内容有生存空间。
     */
    private double computeBaseScore(ApArticle article, long now,
                                    int maxViews, int maxLikes, int maxComments, int maxCollections) {
        // 热度分（归一化到0-1，假设最高分10000）
        int score = article.getScore() != null ? article.getScore() : 0;
        double normalizedScore = Math.min(score / 10000.0, 1.0);

        // 时效性因子：7天内线性衰减，0=最旧/超过7天，1=刚刚发布
        double recencyFactor = 0;
        if (article.getPublishTime() != null) {
            long daysSincePublished = (now - article.getPublishTime().getTime()) / (1000L * 60 * 60 * 24);
            recencyFactor = Math.max(0, 1.0 - daysSincePublished / 7.0);
        }

        // 用户互动指标（对数归一化到0-1）
        int views = article.getViews() != null ? article.getViews() : 0;
        int likes = article.getLikes() != null ? article.getLikes() : 0;
        int comments = article.getComment() != null ? article.getComment() : 0;
        int collections = article.getCollection() != null ? article.getCollection() : 0;

        double logViews = logNorm(views, maxViews);
        double logLikes = logNorm(likes, maxLikes);
        double logComments = logNorm(comments, maxComments);
        double logCollections = logNorm(collections, maxCollections);

        return normalizedScore * 0.25
                + recencyFactor * 0.20
                + logViews * 0.15
                + logLikes * 0.15
                + logComments * 0.10
                + logCollections * 0.10;
    }

    /**
     * 对数归一化：log(1+x)/log(1+max)，max<=0 时返回 0。
     */
    private double logNorm(int value, int max) {
        if (max <= 0) return 0;
        return Math.log(1 + value) / Math.log(1 + max);
    }

    private Map<String, Object> buildEmptyResponse(long seed, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        result.put("list", Collections.emptyList());
        result.put("seed", seed);
        result.put("page", page);
        result.put("size", size);
        result.put("hasMore", false);
        result.put("total", 0);
        return result;
    }
}