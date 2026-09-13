package com.heima.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.content.service.ai.UserInterestService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.pojos.ApCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户兴趣画像实现：近 30 天收藏文章 → 标签频次 TopN
 */
@Slf4j
@Service
public class UserInterestServiceImpl implements UserInterestService {

    /** 画像采样窗口（天） */
    private static final int WINDOW_DAYS = 30;
    /** 采样收藏条数 */
    private static final int SAMPLE = 30;

    @Autowired
    private ApCollectionMapper collectionMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Override
    public List<String> buildInterestTags(Integer userId) {
        List<String> result = new ArrayList<>();
        if (userId == null) {
            return result;
        }
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
            List<ApArticle> articles = apArticleMapper.selectBatchIds(ids);
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
            tagCount.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()))
                .limit(MAX_TAGS)
                .forEach(e -> result.add(e.getKey()));
            log.info("[UserInterest] userId={}, 兴趣标签={}", userId, result);
        } catch (Exception e) {
            log.warn("[UserInterest] 构建画像失败, userId={}", userId, e);
            // fail-open：画像失败不影响问答主流程
        }
        return result;
    }
}
