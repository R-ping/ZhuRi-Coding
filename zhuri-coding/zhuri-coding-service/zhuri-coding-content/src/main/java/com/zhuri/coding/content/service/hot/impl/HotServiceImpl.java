package com.zhuri.coding.content.service.hot.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.service.hot.HotService;
import com.zhuri.coding.model.behavior.pojos.ApCollection;
import com.zhuri.coding.model.follow.pojos.ApFollow;
import com.zhuri.coding.model.article.vos.HotArticleVo;
import com.zhuri.coding.model.article.vos.HotAuthorVo;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class HotServiceImpl implements HotService {

    @Autowired
    private ApCollectionMapper apCollectionMapper;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Map<String, String> CATEGORY_CHANNEL_MAP = new LinkedHashMap<>();

    static {
        CATEGORY_CHANNEL_MAP.put("comprehensive", "综合");
        CATEGORY_CHANNEL_MAP.put("backend", "后端");
        CATEGORY_CHANNEL_MAP.put("frontend", "前端");
        CATEGORY_CHANNEL_MAP.put("android", "Android");
        CATEGORY_CHANNEL_MAP.put("ios", "iOS");
        CATEGORY_CHANNEL_MAP.put("ai", "人工智能");
        CATEGORY_CHANNEL_MAP.put("devtools", "开发工具");
        CATEGORY_CHANNEL_MAP.put("coderslife", "代码人生");
        CATEGORY_CHANNEL_MAP.put("reading", "阅读");
    }

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    @Override
    public List<HotArticleVo> getHotArticles(String category, Integer limit) {
        int limitSize = normalizeLimit(limit);

        String channelName = CATEGORY_CHANNEL_MAP.get(category);
        boolean isComprehensive = "comprehensive".equals(category);
        // 放宽时间窗口，避免陈旧优质内容被硬过滤（分数排序本身已含时效权重）
        int days = 90;

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT a.id, a.title, a.author_id, a.author_name, a.author_image, ")
           .append("a.score, a.views, a.likes, a.comment, a.collection ")
           .append("FROM ap_article a ")
           .append("WHERE a.status = 9 AND a.is_deleted = 0 ")
           .append("AND a.created_time >= NOW() - INTERVAL ? DAY ");

        List<Object> params = new ArrayList<>();
        params.add(days);

        if (!isComprehensive && channelName != null) {
            sql.append("AND a.channel_name = ? ");
            params.add(channelName);
        }

        sql.append("ORDER BY a.score DESC LIMIT ?");
        params.add(limitSize);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());

        Integer currentUserId = getCurrentUserId();
        List<HotArticleVo> result = new ArrayList<>();
        int rank = 1;

        for (Map<String, Object> row : rows) {
            HotArticleVo vo = new HotArticleVo();
            vo.setRank(rank++);
            vo.setId(((Number) row.get("id")).longValue());
            vo.setTitle((String) row.get("title"));
            vo.setAuthorId(((Number) row.get("author_id")).longValue());
            vo.setAuthorName((String) row.get("author_name"));
            vo.setAuthorImage((String) row.get("author_image"));
            vo.setScore(toInteger(row.get("score")));
            vo.setViews(toInteger(row.get("views")));
            vo.setLikes(toInteger(row.get("likes")));
            vo.setComment(toInteger(row.get("comment")));
            vo.setCollection(toInteger(row.get("collection")));
            vo.setIsCollected(checkCollected(currentUserId, vo.getId()));
            result.add(vo);
        }

        return result;
    }

    @Override
    public List<HotArticleVo> getCollectedArticles(Integer limit) {
        int limitSize = normalizeLimit(limit);

        String sql = "SELECT a.id, a.title, a.author_id, a.author_name, a.author_image, " +
                     "a.score, a.views, a.likes, a.comment, a.collection " +
                     "FROM ap_article a " +
                     "WHERE a.status = 9 AND a.is_deleted = 0 " +
                     "AND a.created_time >= NOW() - INTERVAL 3 MONTH " +
                     "ORDER BY a.collection DESC LIMIT ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, limitSize);

        Integer currentUserId = getCurrentUserId();
        List<HotArticleVo> result = new ArrayList<>();
        int rank = 1;

        for (Map<String, Object> row : rows) {
            HotArticleVo vo = new HotArticleVo();
            vo.setRank(rank++);
            vo.setId(((Number) row.get("id")).longValue());
            vo.setTitle((String) row.get("title"));
            vo.setAuthorId(((Number) row.get("author_id")).longValue());
            vo.setAuthorName((String) row.get("author_name"));
            vo.setAuthorImage((String) row.get("author_image"));
            vo.setScore(toInteger(row.get("score")));
            vo.setViews(toInteger(row.get("views")));
            vo.setLikes(toInteger(row.get("likes")));
            vo.setComment(toInteger(row.get("comment")));
            vo.setCollection(toInteger(row.get("collection")));
            vo.setIsCollected(checkCollected(currentUserId, vo.getId()));
            result.add(vo);
        }

        return result;
    }

    @Override
    public List<HotAuthorVo> getHotAuthors(String period, Integer limit) {
        int limitSize = normalizeLimit(limit);
        int days = "monthly".equals(period) ? 30 : 7;

        String sql = "SELECT u.id AS user_id, u.nickname AS user_name, u.image AS user_image, " +
                     "u.flag AS level, " +
                     "SUM(d.increment_collection) * 8 + SUM(d.increment_likes) * 3 + SUM(d.increment_fans) * 5 AS hot_score, " +
                     "SUM(d.increment_likes) AS total_likes, " +
                     "SUM(d.increment_collection) AS total_collections " +
                     "FROM user_daily_stats d " +
                     "INNER JOIN leadnews_user.ap_user u ON d.user_id = u.id " +
                     "WHERE d.stat_date >= NOW() - INTERVAL ? DAY " +
                     "GROUP BY d.user_id, u.id, u.nickname, u.image, u.flag " +
                     "ORDER BY hot_score DESC LIMIT ?";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, days, limitSize);

        Integer currentUserId = getCurrentUserId();
        List<HotAuthorVo> result = new ArrayList<>();
        int rank = 1;

        for (Map<String, Object> row : rows) {
            Integer userId = ((Number) row.get("user_id")).intValue();
            HotAuthorVo vo = new HotAuthorVo();
            vo.setRank(rank++);
            vo.setUserId(userId);
            vo.setUserName((String) row.get("user_name"));
            vo.setUserImage((String) row.get("user_image"));
            vo.setLevel(toInteger(row.get("level")));
            vo.setHotScore(toLong(row.get("hot_score")));
            vo.setTotalLikes(toLong(row.get("total_likes")));
            vo.setTotalCollections(toLong(row.get("total_collections")));

            vo.setQualityArticles(countQualityArticles(userId, days));
            vo.setFans(countFans(userId));
            vo.setIsFollowed(checkFollowed(currentUserId, userId));

            result.add(vo);
        }

        return result;
    }

    @Override
    public Map<String, Object> getHotMeta(String tab, String category, String period) {
        Map<String, Object> meta = new HashMap<>();
        String rule = buildRule(tab, category, period);
        meta.put("rule", rule);
        return meta;
    }

    private String buildRule(String tab, String category, String period) {
        if (tab == null) {
            return "";
        }
        switch (tab) {
            case "article":
                if ("comprehensive".equals(category)) {
                    return "最近3日内发布文章，基于阅读、点赞、评论、收藏数据计算热度";
                }
                return "最近7日内发布文章，基于阅读、点赞、评论、收藏数据计算热度";
            case "collected":
                return "最近3个月内发布文章，基于收藏数计算热度";
            case "authors":
                if ("monthly".equals(period)) {
                    return "最近30日内活跃作者，按掘力值增量计算热度";
                }
                return "最近7日内活跃作者，按掘力值增量计算热度";
            default:
                return "";
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private Integer getCurrentUserId() {
        ApUser user = AppThreadLocalUtil.getUser();
        return user != null ? user.getId() : null;
    }

    private boolean checkCollected(Integer userId, Long articleId) {
        if (userId == null || articleId == null) {
            return false;
        }
        LambdaQueryWrapper<ApCollection> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApCollection::getUserId, userId);
        wrapper.eq(ApCollection::getArticleId, articleId);
        return apCollectionMapper.selectCount(wrapper) > 0;
    }

    private boolean checkFollowed(Integer currentUserId, Integer targetUserId) {
        if (currentUserId == null || targetUserId == null) {
            return false;
        }
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getUserId, currentUserId);
        wrapper.eq(ApFollow::getFollowUserId, targetUserId);
        return apFollowMapper.selectCount(wrapper) > 0;
    }

    private int countQualityArticles(Integer userId, int days) {
        if (userId == null) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM ap_article a " +
                     "WHERE a.author_id = ? AND a.status = 9 AND a.is_deleted = 0 " +
                     "AND a.created_time >= NOW() - INTERVAL ? DAY";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId, days);
        return count != null ? count : 0;
    }

    private int countFans(Integer userId) {
        if (userId == null) {
            return 0;
        }
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getFollowUserId, userId);
        return apFollowMapper.selectCount(wrapper).intValue();
    }

    private Integer toInteger(Object val) {
        if (val == null) {
            return 0;
        }
        return ((Number) val).intValue();
    }

    private Long toLong(Object val) {
        if (val == null) {
            return 0L;
        }
        return ((Number) val).longValue();
    }
}