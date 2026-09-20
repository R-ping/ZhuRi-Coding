package com.zhuri.coding.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.ai.CreatorReportService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 作者 AI 复盘报告实现
 */
@Slf4j
@Service
public class CreatorReportServiceImpl implements CreatorReportService {

    private static final String CACHE_KEY_PREFIX = "ai:creator:report:";
    private static final long CACHE_TTL_HOURS = 6L;
    private static final int MAX_DAYS = 30;
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("MM-dd");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private com.zhuri.coding.content.service.ai.router.AiModelRouter aiModelRouter;

    /** 统一 LLM 出口（安全横切 + token 计量；模型仍由内部按 feature 路由） */
    @Autowired
    private com.zhuri.coding.content.service.ai.AiLlmGateway llmGateway;

    @Override
    public ResponseResult buildReport(Integer userId, int days) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (days <= 0) {
            days = 7;
        }
        days = Math.min(days, MAX_DAYS);
        String cacheKey = CACHE_KEY_PREFIX + userId + ":" + days;
        try {
            String cached = cacheService.get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("cached", true);
                data.put("report", objectMapper.readValue(cached, Map.class));
                return ResponseResult.okResult(data);
            }
        } catch (Exception e) {
            log.warn("[CreatorReport] 读缓存失败, userId={}", userId, e);
        }

        Map<String, Object> stats = aggregateStats(userId, days);
        boolean hasData = (Boolean) stats.getOrDefault("hasData", false);
        if (!hasData) {
            return ResponseResult.errorResult(500, "该窗口内暂无已发布文章，先发布一篇再来生成复盘");
        }
        String reportText = generateReport(stats);
        if (reportText == null) {
            return ResponseResult.errorResult(500, "AI 复盘生成失败，请稍后再试");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("text", reportText);
        report.put("stats", stats);
        report.put("generatedAt", System.currentTimeMillis());
        try {
            cacheService.set(cacheKey, objectMapper.writeValueAsString(report));
            cacheService.expire(cacheKey, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[CreatorReport] 写缓存失败", e);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cached", false);
        data.put("report", report);
        return ResponseResult.okResult(data);
    }

    /** 聚合：本期窗口 vs 上期窗口（发布量/总阅读/总点赞/均阅读环比） + 单篇 top3/bottom3 */
    private Map<String, Object> aggregateStats(Integer userId, int days) {
        Map<String, Object> out = new HashMap<>();
        Calendar end = Calendar.getInstance();
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59);
        end.set(Calendar.MILLISECOND, 999);
        Date endTime = end.getTime();
        Calendar curStart = (Calendar) end.clone();
        curStart.add(Calendar.DAY_OF_YEAR, -(days - 1));
        curStart.set(Calendar.HOUR_OF_DAY, 0);
        curStart.set(Calendar.MINUTE, 0);
        curStart.set(Calendar.SECOND, 0);
        curStart.set(Calendar.MILLISECOND, 0);
        Date curStartTime = curStart.getTime();
        Calendar prevEnd = (Calendar) curStart.clone();
        prevEnd.add(Calendar.SECOND, -1);
        Calendar prevStart = (Calendar) curStart.clone();
        prevStart.add(Calendar.DAY_OF_YEAR, -days);
        Date prevStartTime = prevStart.getTime();

        List<ApArticle> curArticles = queryArticles(userId, curStartTime, endTime);
        List<ApArticle> prevArticles = queryArticles(userId, prevStartTime, prevEnd.getTime());

        out.put("days", days);
        out.put("range", DTF.format(curStartTime.toInstant().atZone(ZoneId.of("Asia/Shanghai")))
            + " ~ " + DTF.format(endTime.toInstant().atZone(ZoneId.of("Asia/Shanghai"))));
        out.put("hasData", curArticles != null && !curArticles.isEmpty());

        Summary cur = summarize(curArticles);
        Summary prev = summarize(prevArticles);
        out.put("current", cur.toMap());
        out.put("previous", prev.toMap());
        // 环比（上期 0 时置 null）
        out.put("moM", Map.of(
            "publish", prev.count == 0 ? null : Math.round((cur.count - prev.count) * 100.0 / prev.count),
            "avgViews", prev.avgViews == 0 ? null : Math.round((cur.avgViews - prev.avgViews) * 100.0 / prev.avgViews),
            "totalViews", prev.totalViews == 0 ? null : Math.round((cur.totalViews - prev.totalViews) * 100.0 / prev.totalViews)));

        // 单篇 top3 / bottom3（按 views）
        List<Map<String, Object>> top = articleBriefs(curArticles, true, 3);
        List<Map<String, Object>> bottom = articleBriefs(curArticles, false, 2);
        out.put("topArticles", top);
        out.put("bottomArticles", bottom);

        // 标签分布 Top5
        out.put("tagTop", tagTop(curArticles));
        return out;
    }

    private List<ApArticle> queryArticles(Integer userId, Date from, Date to) {
        return apArticleMapper.selectList(new LambdaQueryWrapper<ApArticle>()
            .eq(ApArticle::getAuthorId, userId.longValue())
            .eq(ApArticle::getStatus, Status.PUBLISHED.getCode())
            .ge(ApArticle::getPublishTime, from)
            .le(ApArticle::getPublishTime, to)
            .orderByDesc(ApArticle::getViews));
    }

    private Summary summarize(List<ApArticle> articles) {
        Summary s = new Summary();
        if (articles == null || articles.isEmpty()) {
            return s;
        }
        s.count = articles.size();
        long views = 0, likes = 0, collections = 0;
        for (ApArticle a : articles) {
            views += a.getViews() != null ? a.getViews() : 0;
            likes += a.getLikes() != null ? a.getLikes() : 0;
            collections += a.getCollection() != null ? a.getCollection() : 0;
        }
        s.totalViews = views;
        s.totalLikes = likes;
        s.totalCollections = collections;
        s.avgViews = Math.round(views * 10.0 / s.count) / 10.0;
        return s;
    }

    private List<Map<String, Object>> articleBriefs(List<ApArticle> articles, boolean top, int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (articles == null || articles.isEmpty()) {
            return out;
        }
        List<ApArticle> sorted = new ArrayList<>(articles);
        sorted.sort((a1, a2) -> Integer.compare(
            a2.getViews() != null ? a2.getViews() : 0,
            a1.getViews() != null ? a1.getViews() : 0)); // views 降序
        int size = sorted.size();
        List<ApArticle> pick = top
            ? sorted.subList(0, Math.min(n, size))
            : sorted.subList(Math.max(0, size - Math.min(n, size)), size); // 最差 n 条
        for (ApArticle a : pick) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("title", truncate(a.getTitle(), 30));
            m.put("views", a.getViews() != null ? a.getViews() : 0);
            m.put("likes", a.getLikes() != null ? a.getLikes() : 0);
            out.add(m);
        }
        return out;
    }

    private List<String> tagTop(List<ApArticle> articles) {
        Map<String, Integer> cnt = new HashMap<>();
        if (articles != null) {
            for (ApArticle a : articles) {
                List<String> tags = a.getTags();
                if (tags == null) {
                    continue;
                }
                for (String t : tags) {
                    if (t != null && !t.isBlank()) {
                        cnt.merge(t.trim(), 1, Integer::sum);
                    }
                }
            }
        }
        List<String> out = new ArrayList<>();
        cnt.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()))
            .limit(5)
            .forEach(e -> out.add(e.getKey()));
        return out;
    }

    /** LLM 生成复盘文本（结构化 Markdown） */
    private String generateReport(Map<String, Object> stats) {
        try {
            String json = objectMapper.writeValueAsString(stats);
            String sys = "你是创作者数据顾问。根据作者的创作数据 JSON 生成一份中文复盘（Markdown），要求："
                + "1. 开头一行总览：窗口内发布 X 篇、累计阅读/点赞/收藏、环比涨跌（无上期数据就只报绝对值）；\n"
                + "2. 亮点解读：Top 文章为何可能更好（结合标题），1~2 句；\n"
                + "3. 改进建议：针对表现较差文章与整体趋势，给 2 条可执行建议；\n"
                + "4. 下周方向：结合高频标签与亮点主题给 1 个选题建议。\n"
                + "语气务实不吹捧，全文 250 字内，用短句与列表。";
            String user = "【创作数据】\n" + json;
            // 模型仍由路由层按 feature=creator_report 解析（gateway 内部 resolve，语义不变）
            String raw = llmGateway.generateOrNull(
                com.zhuri.coding.content.service.ai.AiFeatures.CREATOR_REPORT, sys, user, null, null);
            return raw == null || raw.isBlank() ? null : raw.trim();
        } catch (Exception e) {
            log.error("[CreatorReport] 报告生成异常", e);
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }

    /** 单窗口聚合摘要 */
    private static class Summary {
        int count;
        long totalViews;
        long totalLikes;
        long totalCollections;
        double avgViews;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", count);
            m.put("totalViews", totalViews);
            m.put("totalLikes", totalLikes);
            m.put("totalCollections", totalCollections);
            m.put("avgViews", avgViews);
            return m;
        }
    }
}
