package com.heima.content.service.level.impl;

import com.heima.common.redis.CacheService;
import com.heima.content.mapper.level.ApBehaviorConfigMapper;
import com.heima.content.mapper.level.ApUserDailyProgressMapper;
import com.heima.content.mapper.user.UserScoreDetailsMapper;
import com.heima.content.mapper.user.UserScoreSummaryMapper;
import com.heima.content.service.level.JScoreService;
import com.heima.model.level.dtos.JScoreDetailVO;
import com.heima.model.level.dtos.JScoreDetailVO.JScoreDetailItem;
import com.heima.model.level.dtos.JScoreOverviewVO;
import com.heima.model.level.dtos.JScoreOverviewVO.ChartData;
import com.heima.model.level.dtos.JScoreOverviewVO.TodayTotalVO;
import com.heima.model.level.pojos.ApBehaviorConfig;
import com.heima.model.level.pojos.ApUserDailyProgress;
import com.heima.model.user.pojos.UserScoreDetails;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class JScoreServiceImpl implements JScoreService {

    @Autowired
    private UserScoreDetailsMapper userScoreDetailsMapper;

    @Autowired
    private UserScoreSummaryMapper userScoreSummaryMapper;

    @Autowired
    private ApBehaviorConfigMapper behaviorConfigMapper;

    @Autowired
    private ApUserDailyProgressMapper userDailyProgressMapper;

    @Autowired
    private CacheService cacheService;

    private static final String REDIS_KEY_PREFIX = "jscore:today:";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * category 映射表
     */
    private static final Map<String, Integer> CATEGORY_MAP = new LinkedHashMap<>();
    static {
        CATEGORY_MAP.put("all", null);
        CATEGORY_MAP.put("effect", 4);
        CATEGORY_MAP.put("active", 2);
        CATEGORY_MAP.put("learn", 3);
        CATEGORY_MAP.put("basic", 1);
        CATEGORY_MAP.put("spec", 5);
    }

    /**
     * category 数字 -> 名称映射
     */
    private static final Map<Integer, String> CATEGORY_NAME_MAP = new LinkedHashMap<>();
    static {
        CATEGORY_NAME_MAP.put(1, "basic");
        CATEGORY_NAME_MAP.put(2, "active");
        CATEGORY_NAME_MAP.put(3, "learn");
        CATEGORY_NAME_MAP.put(4, "effect");
        CATEGORY_NAME_MAP.put(5, "spec");
    }

    /**
     * category 数字 -> 汇总表字段名映射
     */
    private static final Map<Integer, String> CATEGORY_FIELD_MAP = new LinkedHashMap<>();
    static {
        CATEGORY_FIELD_MAP.put(1, "basic_score");
        CATEGORY_FIELD_MAP.put(2, "active_score");
        CATEGORY_FIELD_MAP.put(3, "learn_score");
        CATEGORY_FIELD_MAP.put(4, "effect_score");
        CATEGORY_FIELD_MAP.put(5, "spec_score");
    }

    @Override
    public JScoreOverviewVO getOverview(Long userId) {
        String today = LocalDate.now().format(DATE_FMT);
        JScoreOverviewVO vo = new JScoreOverviewVO();
        vo.setStatDate(today);

        // 1. 从 Redis 读取今日增量
        String redisKey = REDIS_KEY_PREFIX + userId + ":" + today;
        Map<Object, Object> redisData = cacheService.hGetAll(redisKey);
        Map<String, TodayTotalVO> summary = new LinkedHashMap<>();
        ChartData chart = new ChartData();
        List<String> dimensions = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();

        // 2. 遍历 5 个分类
        for (int cat = 1; cat <= 5; cat++) {
            String catName = CATEGORY_NAME_MAP.get(cat);
            String fieldName = CATEGORY_FIELD_MAP.get(cat);

        // 今日数据：优先 Redis，降级查询数据库，再兜底每日进度表（ap_user_daily_progress）
        Map<Integer, BigDecimal> dailyProgress = null;
        BigDecimal todayScore;
        if (redisData != null && !redisData.isEmpty()) {
            Object val = redisData.get(catName);
            todayScore = val != null ? new BigDecimal(val.toString()) : BigDecimal.ZERO;
        } else {
            String todayStart = today + " 00:00:00";
            BigDecimal dbToday = userScoreDetailsMapper.sumTodayScoreByCategory(userId, cat, todayStart);
            todayScore = dbToday != null ? dbToday : BigDecimal.ZERO;
        }
        if (todayScore.compareTo(BigDecimal.ZERO) == 0) {
            if (dailyProgress == null) {
                dailyProgress = computeTodayFromDailyProgress(userId);
            }
            BigDecimal dp = dailyProgress.getOrDefault(cat, BigDecimal.ZERO);
            if (dp.compareTo(BigDecimal.ZERO) > 0) {
                todayScore = dp;
            }
        }

            // 总计数据：从汇总表查询
            BigDecimal total = userScoreSummaryMapper.sumFieldScore(userId, fieldName);
            if (total == null) {
                total = BigDecimal.ZERO;
            }

            TodayTotalVO tt = new TodayTotalVO();
            tt.setToday(todayScore);
            tt.setTotal(total);
            summary.put(catName, tt);

            dimensions.add(catName);
            values.add(total);
        }

        chart.setDimensions(dimensions);
        chart.setValues(values);
        vo.setSummary(summary);
        vo.setChart(chart);

        return vo;
    }

    @Override
    public JScoreDetailVO getDetail(Long userId, String category, String cursor, Integer size) {
        // 1. 解析 category 参数
        Integer catNum = CATEGORY_MAP.get(category);
        if (catNum == null && !"all".equals(category)) {
            catNum = null; // 默认 all
        }

        // 2. 解析游标
        String cursorCreatedAt = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isEmpty()) {
            String[] parts = cursor.split("_");
            if (parts.length == 2) {
                cursorCreatedAt = parts[0];
                cursorId = Long.parseLong(parts[1]);
            }
        }

        // 3. 多查 1 条判断 has_more
        int querySize = size + 1;

        // 4. 调用 Mapper 游标分页查询
        List<UserScoreDetails> records = userScoreDetailsMapper.selectByCursor(
            userId, catNum, cursorCreatedAt, cursorId, querySize);

        // 5. 判断是否有更多数据
        boolean hasMore = records.size() > size;
        if (hasMore) {
            records = records.subList(0, size);
        }

        // 6. 组装返回
        JScoreDetailVO vo = new JScoreDetailVO();
        List<JScoreDetailItem> items = new ArrayList<>();
        String nextCursor = "";

        // 行为 code → 展示名映射（一次性查全部配置，避免逐条查库）
        Map<String, String> actionNameMap = loadActionNameMap();

        for (int i = 0; i < records.size(); i++) {
            UserScoreDetails record = records.get(i);
            JScoreDetailItem item = new JScoreDetailItem();
            item.setId(String.valueOf(record.getId()));
            item.setCreatedAt(record.getCreatedAt() != null
                ? SDF.format(record.getCreatedAt()) : "");
            item.setActionCode(record.getActionCode() != null ? record.getActionCode() : "");
            item.setActionDesc(record.getActionDesc() != null ? record.getActionDesc() : "");
            // 行为展示名：优先 ap_behavior_config.action_name（如"点赞一篇文章"），
            // 未配置时回退 action_code 本身（避免行为名列空白）
            item.setActionName(actionNameMap.getOrDefault(
                record.getActionCode(), record.getActionCode() != null ? record.getActionCode() : ""));
            item.setScore(record.getScore() != null ? record.getScore() : BigDecimal.ZERO);
            String catName = CATEGORY_NAME_MAP.get(record.getCategory());
            item.setCategory(catName != null ? catName : "");
            items.add(item);

            // 最后一个记录的游标
            if (i == records.size() - 1) {
                String createdAt = record.getCreatedAt() != null
                    ? SDF.format(record.getCreatedAt()) : "";
                nextCursor = createdAt + "_" + record.getId();
            }
        }

        vo.setList(items);
        vo.setNextCursor(nextCursor);
        vo.setHasMore(hasMore);

        return vo;
    }

    /**
     * 加载行为配置 code → 展示名映射（全部启用配置一次查库）
     */
    private Map<String, String> loadActionNameMap() {
        Map<String, String> map = new HashMap<>();
        try {
            List<ApBehaviorConfig> configs = behaviorConfigMapper.selectList(
                new LambdaQueryWrapper<ApBehaviorConfig>().eq(ApBehaviorConfig::getIsActive, 1));
            if (configs != null) {
                for (ApBehaviorConfig config : configs) {
                    if (config.getActionCode() != null && config.getActionName() != null) {
                        map.put(config.getActionCode(), config.getActionName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载行为配置名映射失败，明细行为名回退 action_code", e);
        }
        return map;
    }

    /**
     * 按每日进度表 ap_user_daily_progress + 行为配置表 ap_behavior_config
     * 计算用户今日各分类的掘友分（明细页"今日"数据兜底来源）。
     * 返回 Map<分类编号, 今日得分>，分类编号与 user_score_details.category 一致
     * （1社区基础 2社区活跃 3社区学习 4社区影响力）。
     */
    private Map<Integer, BigDecimal> computeTodayFromDailyProgress(Long userId) {
        Map<Integer, BigDecimal> result = new HashMap<>();
        if (userId == null) {
            return result;
        }
        try {
            String today = LocalDate.now().format(DATE_FMT);
            List<ApBehaviorConfig> configs = behaviorConfigMapper.selectList(
                new LambdaQueryWrapper<ApBehaviorConfig>().eq(ApBehaviorConfig::getIsActive, 1));
            Map<String, ApBehaviorConfig> configMap = new HashMap<>();
            for (ApBehaviorConfig c : configs) {
                if (c.getActionCode() != null) {
                    configMap.put(c.getActionCode(), c);
                }
            }

            List<ApUserDailyProgress> todayRows = userDailyProgressMapper.selectList(
                new LambdaQueryWrapper<ApUserDailyProgress>()
                    .eq(ApUserDailyProgress::getUserId, userId)
                    .apply("DATE(stat_date) = {0}", today));

            for (ApUserDailyProgress row : todayRows) {
                ApBehaviorConfig config = configMap.get(row.getActionCode());
                if (config == null || config.getScore() == null || row.getCount() == null) {
                    continue;
                }
                Integer category = groupToCategory(config.getGroupType());
                if (category == null) {
                    continue;
                }
                BigDecimal gained = config.getScore().multiply(BigDecimal.valueOf(row.getCount()));
                result.merge(category, gained, BigDecimal::add);
            }
        } catch (Exception e) {
            log.warn("从每日进度表计算今日得分失败, userId={}, error={}", userId, e.getMessage());
        }
        return result;
    }

    /**
     * 行为分组名称 → 明细分类编号
     */
    private Integer groupToCategory(String groupType) {
        if (groupType == null) {
            return null;
        }
        switch (groupType) {
            case "社区基础":
                return 1;
            case "社区活跃":
                return 2;
            case "社区学习":
                return 3;
            case "社区影响力":
                return 4;
            // 内容创作加分归入"专项/创作"分类（编号5，明细页 spec）
            case "内容创作":
                return 5;
            default:
                return null;
        }
    }
}