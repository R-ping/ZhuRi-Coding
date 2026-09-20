package com.heima.content.service.contentdata.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.column.ApColumnMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.contentdata.ContentDataService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.column.pojos.ApColumn;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ContentDataServiceImpl implements ContentDataService {

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApColumnMapper apColumnMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    // ==================== Article ====================

    @Override
    public ResponseResult getArticleStatistics(Long userId, String startDate, String endDate) {
        String prevDate = getPreviousDay(startDate);
        // 一次查询覆盖当前区间和前一天
        Date rangeStart = parseDate(prevDate);
        Date rangeEnd = parseDateEnd(endDate);

        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticle::getAuthorId, userId);
        wrapper.eq(ApArticle::getIsDeleted, false);
        wrapper.between(ApArticle::getPublishTime, rangeStart, rangeEnd);
        List<ApArticle> articles = apArticleMapper.selectList(wrapper);

        // 按日期分组：当前区间 vs 前一天
        Date currentStart = parseDate(startDate);
        Map<String, Object> current = aggregateArticleMetrics(articles, currentStart, rangeEnd);
        Map<String, Object> previous = aggregateArticleMetrics(articles, rangeStart, parseDateEnd(prevDate));

        Map<String, Object> data = new HashMap<>();
        data.put("totalCount", current.get("totalCount"));
        data.put("totalTrend", ((Number) current.get("totalCount")).intValue() - ((Number) previous.get("totalCount")).intValue());
        data.put("showCount", current.get("showCount"));
        data.put("showTrend", ((Number) current.get("showCount")).intValue() - ((Number) previous.get("showCount")).intValue());
        data.put("readCount", current.get("readCount"));
        data.put("readTrend", ((Number) current.get("readCount")).intValue() - ((Number) previous.get("readCount")).intValue());
        data.put("likeCount", current.get("likeCount"));
        data.put("likeTrend", ((Number) current.get("likeCount")).intValue() - ((Number) previous.get("likeCount")).intValue());
        data.put("commentCount", current.get("commentCount"));
        data.put("commentTrend", ((Number) current.get("commentCount")).intValue() - ((Number) previous.get("commentCount")).intValue());
        data.put("collectCount", current.get("collectCount"));
        data.put("collectTrend", ((Number) current.get("collectCount")).intValue() - ((Number) previous.get("collectCount")).intValue());

        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getArticleTrend(Long userId, String startDate, String endDate, Integer days) {
        // 一次查询获取整个日期范围的所有文章
        Date rangeStart = parseDate(startDate);
        Date rangeEnd = parseDateEnd(endDate);
        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticle::getAuthorId, userId);
        wrapper.eq(ApArticle::getIsDeleted, false);
        wrapper.between(ApArticle::getPublishTime, rangeStart, rangeEnd);
        List<ApArticle> articles = apArticleMapper.selectList(wrapper);

        // 按日期分组
        Map<String, List<ApArticle>> grouped = articles.stream()
                .collect(Collectors.groupingBy(a -> dateFormat.format(a.getPublishTime())));

        List<Map<String, Object>> trendData = new ArrayList<>();
        LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String dateStr = date.format(DATE_FORMATTER);
            List<ApArticle> dayArticles = grouped.getOrDefault(dateStr, Collections.emptyList());
            Map<String, Object> metrics = aggregateArticleMetrics(dayArticles);
            Map<String, Object> point = new HashMap<>();
            point.put("date", dateStr);
            point.put("showCount", metrics.get("showCount"));
            point.put("readCount", metrics.get("readCount"));
            point.put("likeCount", metrics.get("likeCount"));
            point.put("commentCount", metrics.get("commentCount"));
            point.put("collectCount", metrics.get("collectCount"));
            trendData.add(point);
        }

        return ResponseResult.okResult(trendData);
    }

    @Override
    public ResponseResult getArticleDetail(Long userId, String startDate, String endDate, Integer page, Integer size) {
        Page<ApArticle> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticle::getAuthorId, userId);
        wrapper.eq(ApArticle::getIsDeleted, false);
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(ApArticle::getPublishTime, parseDate(startDate));
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(ApArticle::getPublishTime, parseDateEnd(endDate));
        }
        wrapper.orderByDesc(ApArticle::getPublishTime);

        IPage<ApArticle> result = apArticleMapper.selectPage(pageParam, wrapper);
        List<Map<String, Object>> list = result.getRecords().stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("title", a.getTitle());
            m.put("publishTime", a.getPublishTime());
            m.put("readCount", a.getViews() != null ? a.getViews() : 0);
            m.put("likeCount", a.getLikes() != null ? a.getLikes() : 0);
            m.put("commentCount", a.getComment() != null ? a.getComment() : 0);
            m.put("collectCount", a.getCollection() != null ? a.getCollection() : 0);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    // ==================== Column ====================

    @Override
    public ResponseResult getColumnStatistics(Long userId, String startDate, String endDate) {
        String prevDate = getPreviousDay(startDate);
        Date rangeStart = parseDate(prevDate);
        Date rangeEnd = parseDateEnd(endDate);

        LambdaQueryWrapper<ApColumn> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApColumn::getAuthorId, userId);
        wrapper.eq(ApColumn::getIsDeleted, false);
        wrapper.between(ApColumn::getCreatedTime, rangeStart, rangeEnd);
        List<ApColumn> columns = apColumnMapper.selectList(wrapper);

        Date currentStart = parseDate(startDate);
        Map<String, Object> current = aggregateColumnMetrics(columns, currentStart, rangeEnd);
        Map<String, Object> previous = aggregateColumnMetrics(columns, rangeStart, parseDateEnd(prevDate));

        Map<String, Object> data = new HashMap<>();
        data.put("totalCount", current.get("totalCount"));
        data.put("totalTrend", ((Number) current.get("totalCount")).intValue() - ((Number) previous.get("totalCount")).intValue());
        data.put("subscribeCount", current.get("subscribeCount"));
        data.put("subscribeTrend", ((Number) current.get("subscribeCount")).intValue() - ((Number) previous.get("subscribeCount")).intValue());

        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getColumnTrend(Long userId, String startDate, String endDate, Integer days) {
        Date rangeStart = parseDate(startDate);
        Date rangeEnd = parseDateEnd(endDate);
        LambdaQueryWrapper<ApColumn> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApColumn::getAuthorId, userId);
        wrapper.eq(ApColumn::getIsDeleted, false);
        wrapper.between(ApColumn::getCreatedTime, rangeStart, rangeEnd);
        List<ApColumn> columns = apColumnMapper.selectList(wrapper);

        Map<String, List<ApColumn>> grouped = columns.stream()
                .collect(Collectors.groupingBy(c -> dateFormat.format(c.getCreatedTime())));

        List<Map<String, Object>> trendData = new ArrayList<>();
        LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String dateStr = date.format(DATE_FORMATTER);
            List<ApColumn> dayColumns = grouped.getOrDefault(dateStr, Collections.emptyList());
            Map<String, Object> metrics = aggregateColumnMetrics(dayColumns);
            Map<String, Object> point = new HashMap<>();
            point.put("date", dateStr);
            point.put("subscribeCount", metrics.get("subscribeCount"));
            trendData.add(point);
        }

        return ResponseResult.okResult(trendData);
    }

    @Override
    public ResponseResult getColumnDetail(Long userId, String startDate, String endDate, Integer page, Integer size) {
        Page<ApColumn> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApColumn> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApColumn::getAuthorId, userId);
        wrapper.eq(ApColumn::getIsDeleted, false);
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(ApColumn::getCreatedTime, parseDate(startDate));
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(ApColumn::getCreatedTime, parseDateEnd(endDate));
        }
        wrapper.orderByDesc(ApColumn::getCreatedTime);

        IPage<ApColumn> result = apColumnMapper.selectPage(pageParam, wrapper);
        List<Map<String, Object>> list = result.getRecords().stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("title", c.getTitle());
            m.put("publishTime", c.getCreatedTime());
            m.put("subscribeCount", c.getSubscribeCount() != null ? c.getSubscribeCount() : 0);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    // ==================== Pin ====================

    @Override
    public ResponseResult getPinStatistics(Long userId, String startDate, String endDate) {
        String prevDate = getPreviousDay(startDate);
        Date rangeStart = parseDate(prevDate);
        Date rangeEnd = parseDateEnd(endDate);

        LambdaQueryWrapper<ApPins> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPins::getAuthorId, userId);
        wrapper.eq(ApPins::getIsDeleted, false);
        wrapper.between(ApPins::getPublishTime, rangeStart, rangeEnd);
        List<ApPins> pins = apPinsMapper.selectList(wrapper);

        Date currentStart = parseDate(startDate);
        Map<String, Object> current = aggregatePinMetrics(pins, currentStart, rangeEnd);
        Map<String, Object> previous = aggregatePinMetrics(pins, rangeStart, parseDateEnd(prevDate));

        Map<String, Object> data = new HashMap<>();
        data.put("totalCount", current.get("totalCount"));
        data.put("totalTrend", ((Number) current.get("totalCount")).intValue() - ((Number) previous.get("totalCount")).intValue());
        data.put("likeCount", current.get("likeCount"));
        data.put("likeTrend", ((Number) current.get("likeCount")).intValue() - ((Number) previous.get("likeCount")).intValue());
        data.put("commentCount", current.get("commentCount"));
        data.put("commentTrend", ((Number) current.get("commentCount")).intValue() - ((Number) previous.get("commentCount")).intValue());

        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getPinTrend(Long userId, String startDate, String endDate, Integer days) {
        Date rangeStart = parseDate(startDate);
        Date rangeEnd = parseDateEnd(endDate);
        LambdaQueryWrapper<ApPins> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPins::getAuthorId, userId);
        wrapper.eq(ApPins::getIsDeleted, false);
        wrapper.between(ApPins::getPublishTime, rangeStart, rangeEnd);
        List<ApPins> pins = apPinsMapper.selectList(wrapper);

        Map<String, List<ApPins>> grouped = pins.stream()
                .collect(Collectors.groupingBy(p -> dateFormat.format(p.getPublishTime())));

        List<Map<String, Object>> trendData = new ArrayList<>();
        LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String dateStr = date.format(DATE_FORMATTER);
            List<ApPins> dayPins = grouped.getOrDefault(dateStr, Collections.emptyList());
            Map<String, Object> metrics = aggregatePinMetrics(dayPins);
            Map<String, Object> point = new HashMap<>();
            point.put("date", dateStr);
            point.put("likeCount", metrics.get("likeCount"));
            point.put("commentCount", metrics.get("commentCount"));
            trendData.add(point);
        }

        return ResponseResult.okResult(trendData);
    }

    @Override
    public ResponseResult getPinDetail(Long userId, String startDate, String endDate, Integer page, Integer size) {
        Page<ApPins> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApPins> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPins::getAuthorId, userId);
        wrapper.eq(ApPins::getIsDeleted, false);
        if (startDate != null && !startDate.isEmpty()) {
            wrapper.ge(ApPins::getPublishTime, parseDate(startDate));
        }
        if (endDate != null && !endDate.isEmpty()) {
            wrapper.le(ApPins::getPublishTime, parseDateEnd(endDate));
        }
        wrapper.orderByDesc(ApPins::getPublishTime);

        IPage<ApPins> result = apPinsMapper.selectPage(pageParam, wrapper);
        List<Map<String, Object>> list = result.getRecords().stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("content", p.getContent());
            m.put("publishTime", p.getPublishTime());
            m.put("likeCount", p.getLikes() != null ? p.getLikes() : 0);
            m.put("commentCount", p.getComment() != null ? p.getComment() : 0);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    // ==================== Private Helpers ====================

    /**
     * 聚合已过滤的文章列表中的指标（按时间范围过滤）
     */
    private Map<String, Object> aggregateArticleMetrics(List<ApArticle> articles, Date startTime, Date endTime) {
        List<ApArticle> filtered = articles.stream()
                .filter(a -> a.getPublishTime() != null
                        && !a.getPublishTime().before(startTime)
                        && !a.getPublishTime().after(endTime))
                .collect(Collectors.toList());
        return aggregateArticleMetrics(filtered);
    }

    /**
     * 聚合文章列表中的指标
     */
    private Map<String, Object> aggregateArticleMetrics(List<ApArticle> articles) {
        int totalCount = articles.size();
        int readCount = articles.stream().mapToInt(a -> a.getViews() != null ? a.getViews() : 0).sum();
        int likeCount = articles.stream().mapToInt(a -> a.getLikes() != null ? a.getLikes() : 0).sum();
        int commentCount = articles.stream().mapToInt(a -> a.getComment() != null ? a.getComment() : 0).sum();
        int collectCount = articles.stream().mapToInt(a -> a.getCollection() != null ? a.getCollection() : 0).sum();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalCount", totalCount);
        metrics.put("showCount", totalCount);
        metrics.put("readCount", readCount);
        metrics.put("likeCount", likeCount);
        metrics.put("commentCount", commentCount);
        metrics.put("collectCount", collectCount);
        return metrics;
    }

    private Map<String, Object> aggregateColumnMetrics(List<ApColumn> columns, Date startTime, Date endTime) {
        List<ApColumn> filtered = columns.stream()
                .filter(c -> c.getCreatedTime() != null
                        && !c.getCreatedTime().before(startTime)
                        && !c.getCreatedTime().after(endTime))
                .collect(Collectors.toList());
        return aggregateColumnMetrics(filtered);
    }

    private Map<String, Object> aggregateColumnMetrics(List<ApColumn> columns) {
        int totalCount = columns.size();
        int subscribeCount = columns.stream().mapToInt(c -> c.getSubscribeCount() != null ? c.getSubscribeCount() : 0).sum();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalCount", totalCount);
        metrics.put("subscribeCount", subscribeCount);
        return metrics;
    }

    private Map<String, Object> aggregatePinMetrics(List<ApPins> pins, Date startTime, Date endTime) {
        List<ApPins> filtered = pins.stream()
                .filter(p -> p.getPublishTime() != null
                        && !p.getPublishTime().before(startTime)
                        && !p.getPublishTime().after(endTime))
                .collect(Collectors.toList());
        return aggregatePinMetrics(filtered);
    }

    private Map<String, Object> aggregatePinMetrics(List<ApPins> pins) {
        int totalCount = pins.size();
        int likeCount = pins.stream().mapToInt(p -> p.getLikes() != null ? p.getLikes() : 0).sum();
        int commentCount = pins.stream().mapToInt(p -> p.getComment() != null ? p.getComment() : 0).sum();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalCount", totalCount);
        metrics.put("likeCount", likeCount);
        metrics.put("commentCount", commentCount);
        return metrics;
    }

    private String getPreviousDay(String dateStr) {
        LocalDate date = LocalDate.parse(dateStr, DATE_FORMATTER);
        return date.minusDays(1).format(DATE_FORMATTER);
    }

    private Date parseDate(String dateStr) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            return sdf.parse(dateStr);
        } catch (Exception e) {
            log.error("parseDate error: {}", dateStr, e);
            return new Date();
        }
    }

    private Date parseDateEnd(String dateStr) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.parse(dateStr + " 23:59:59");
        } catch (Exception e) {
            log.error("parseDateEnd error: {}", dateStr, e);
            return new Date();
        }
    }
}