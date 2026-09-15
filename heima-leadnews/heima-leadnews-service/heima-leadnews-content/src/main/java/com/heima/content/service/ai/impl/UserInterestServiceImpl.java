package com.heima.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.common.redis.CacheService;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.content.service.ai.UserInterestService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.behavior.pojos.ApCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 用户兴趣画像实现（P2-3 冷启动改造）：三层来源逐层兜底
 *
 * <ol>
 *   <li>L1 收藏画像：近 30 天收藏文章 → 标签频次 TopN；</li>
 *   <li>L2 即时兴趣：{@link #learnFromQuery} 沉淀的最近提问相关标签（Redis 24h TTL）；</li>
 *   <li>L3 全站热门：PUBLISHED 文章按点赞排序的标签 TopN（新用户兜底）。</li>
 * </ol>
 * 全链路 fail-open：任何一层失败降级到下一层，最坏返回空列表（调用方跳过注入）。
 */
@Slf4j
@Service
public class UserInterestServiceImpl implements UserInterestService {

    /** 画像采样窗口（天） */
    private static final int WINDOW_DAYS = 30;
    /** 采样收藏条数 */
    private static final int SAMPLE = 30;

    /** 即时兴趣 Redis Key 前缀 */
    private static final String INSTANT_KEY_PREFIX = "ai:interest:instant:";
    /** 即时兴趣 TTL */
    private static final java.time.Duration INSTANT_TTL = java.time.Duration.ofHours(24);
    /** 即时兴趣标签容量（learnFromQuery 累积上限，读取时仍截 MAX_TAGS） */
    private static final int INSTANT_MAX_TAGS = 10;
    /** 热门兜底采样文章数 */
    private static final int HOT_SAMPLE = 30;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private ApCollectionMapper collectionMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private CacheService cacheService;

    @Override
    public List<String> buildInterestTags(Integer userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        // L1 收藏画像（老用户精准）
        List<String> result = fromCollections(userId);
        if (!result.isEmpty()) {
            return result;
        }
        // L2 即时兴趣（首问沉淀，24h 有效）
        result = fromInstantInterest(userId);
        if (!result.isEmpty()) {
            return result;
        }
        // L3 全站热门兜底（纯新用户）
        return fromHotArticles();
    }

    @Override
    public void learnFromQuery(Integer userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return;
        }
        try {
            List<ApArticle> articles = apArticleMapper.selectBatchIds(articleIds);
            List<String> newTags = collectTags(articles);
            if (newTags.isEmpty()) {
                return;
            }
            // 与已有即时兴趣合并去重，容量封顶（新的在前，保证最近提问的主题优先保留）
            LinkedHashSet<String> merged = new LinkedHashSet<>(newTags);
            List<String> existing = fromInstantInterest(userId);
            for (String t : existing) {
                if (merged.size() >= INSTANT_MAX_TAGS) {
                    break;
                }
                merged.add(t);
            }
            String json = OBJECT_MAPPER.writeValueAsString(new ArrayList<>(merged));
            cacheService.getstringRedisTemplate().opsForValue()
                .set(INSTANT_KEY_PREFIX + userId, json, INSTANT_TTL);
            log.info("[UserInterest] 即时兴趣沉淀, userId={}, tags={}", userId, merged);
        } catch (Exception e) {
            log.debug("[UserInterest] 即时兴趣沉淀失败（fail-open）, userId={}", userId, e);
        }
    }

    /** L1：近 30 天收藏 → 标签频次 TopN */
    private List<String> fromCollections(Integer userId) {
        List<String> result = new ArrayList<>();
        try {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, -WINDOW_DAYS);
            Date since = c.getTime();
            List<ApCollection> collections = collectionMapper.selectList(
                new LambdaQueryWrapper<ApCollection>()
                    .eq(ApCollection::getUserId, userId)
                    .isNotNull(ApCollection::getArticleId)
                    .ge(ApCollection::getCreatedTime, since)
                    .orderByDesc(ApCollection::getCreatedTime)
                    .last("LIMIT " + SAMPLE));
            if (collections == null || collections.isEmpty()) {
                return result;
            }
            // 去重文章
            Map<Long, Boolean> seen = new LinkedHashMap<>();
            for (ApCollection col : collections) {
                if (col.getArticleId() != null) {
                    seen.put(col.getArticleId(), Boolean.TRUE);
                }
            }
            List<Long> ids = new ArrayList<>(seen.keySet());
            result = topTagsFromArticles(apArticleMapper.selectBatchIds(ids));
            if (!result.isEmpty()) {
                log.info("[UserInterest] 画像来源=collection, userId={}, tags={}", userId, result);
            }
        } catch (Exception e) {
            log.warn("[UserInterest] 收藏画像构建失败，降级下一层, userId={}", userId, e);
        }
        return result;
    }

    /** L2：即时兴趣（Redis JSON 数组） */
    private List<String> fromInstantInterest(Integer userId) {
        List<String> result = new ArrayList<>();
        try {
            String json = cacheService.getstringRedisTemplate().opsForValue().get(INSTANT_KEY_PREFIX + userId);
            if (json == null || json.isBlank()) {
                return result;
            }
            List<String> tags = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() { });
            if (tags != null) {
                for (String t : tags) {
                    if (t != null && !t.isBlank() && result.size() < MAX_TAGS) {
                        result.add(t.trim());
                    }
                }
            }
            if (!result.isEmpty()) {
                log.info("[UserInterest] 画像来源=instant, userId={}, tags={}", userId, result);
            }
        } catch (Exception e) {
            log.debug("[UserInterest] 即时兴趣读取失败，降级下一层, userId={}", userId, e);
        }
        return result;
    }

    /** L3：全站热门（PUBLISHED 按点赞）→ 标签频次 TopN */
    private List<String> fromHotArticles() {
        List<String> result = new ArrayList<>();
        try {
            List<ApArticle> hot = apArticleMapper.selectList(
                new LambdaQueryWrapper<ApArticle>()
                    .eq(ApArticle::getStatus, Status.PUBLISHED.getCode())
                    .orderByDesc(ApArticle::getLikes)
                    .orderByDesc(ApArticle::getId)
                    .last("LIMIT " + HOT_SAMPLE));
            result = topTagsFromArticles(hot);
            if (!result.isEmpty()) {
                log.info("[UserInterest] 画像来源=hot(冷启动兜底), tags={}", result);
            }
        } catch (Exception e) {
            log.warn("[UserInterest] 热门兜底构建失败，返回空画像", e);
        }
        return result;
    }

    /** 文章列表 → 标签频次 TopN（公共聚合逻辑） */
    private List<String> topTagsFromArticles(List<ApArticle> articles) {
        if (articles == null || articles.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Integer> tagCount = new HashMap<>();
        for (ApArticle a : articles) {
            List<String> tags = a.getTags();
            if (tags == null) {
                continue;
            }
            for (String t : tags) {
                if (t == null || t.isBlank()) {
                    continue;
                }
                tagCount.merge(t.trim(), 1, Integer::sum);
            }
        }
        List<String> result = new ArrayList<>();
        tagCount.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()))
            .limit(MAX_TAGS)
            .forEach(e -> result.add(e.getKey()));
        return result;
    }

    /** 提取文章列表的全部去重标签（即时兴趣沉淀用） */
    private List<String> collectTags(List<ApArticle> articles) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (articles != null) {
            for (ApArticle a : articles) {
                List<String> tags = a.getTags();
                if (tags == null) {
                    continue;
                }
                for (String t : tags) {
                    if (t != null && !t.isBlank()) {
                        set.add(t.trim());
                    }
                }
            }
        }
        return new ArrayList<>(set);
    }
}
