package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.level.ApBehaviorConfigMapper;
import com.heima.content.mapper.level.ApUserDailyProgressMapper;
import com.heima.model.level.pojos.ApBehaviorConfig;
import com.heima.model.level.pojos.ApUserDailyProgress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成长任务进度构建器
 * 基于 ap_behavior_config 行为配置与 ap_user_daily_progress 用户每日进度组装成长任务数据
 */
@Component
public class LevelTaskProgressBuilder {

    @Autowired
    private ApBehaviorConfigMapper behaviorConfigMapper;

    @Autowired
    private ApUserDailyProgressMapper userDailyProgressMapper;

    /**
     * 构建成长任务进度
     * 返回结构：{"growth_tasks": {groupSort: [task...]}, "today_jscore": 今日掘友分}
     */
    public Map<String, Object> buildTaskProgress(Long userId) {
        Map<String, Object> result = new HashMap<>();

        // 1. 查询全部启用的行为配置，按分组排序、行为排序升序
        LambdaQueryWrapper<ApBehaviorConfig> configQuery = new LambdaQueryWrapper<>();
        configQuery.eq(ApBehaviorConfig::getIsActive, 1);
        configQuery.orderByAsc(ApBehaviorConfig::getGroupSort);
        configQuery.orderByAsc(ApBehaviorConfig::getSortOrder);
        List<ApBehaviorConfig> configList = behaviorConfigMapper.selectList(configQuery);

        // 2. 一次查询该用户全部每日进度，本地按 actionCode 聚合今日计数与累计计数
        LambdaQueryWrapper<ApUserDailyProgress> progressQuery = new LambdaQueryWrapper<>();
        progressQuery.eq(ApUserDailyProgress::getUserId, userId);
        List<ApUserDailyProgress> progressList = userDailyProgressMapper.selectList(progressQuery);

        LocalDate today = LocalDate.now();
        Map<String, Integer> todayCountMap = new HashMap<>();
        Map<String, Integer> totalCountMap = new HashMap<>();
        for (ApUserDailyProgress progress : progressList) {
            String actionCode = progress.getActionCode();
            if (actionCode == null) {
                continue;
            }
            int count = progress.getCount() == null ? 0 : progress.getCount();
            totalCountMap.merge(actionCode, count, Integer::sum);
            if (today.equals(toLocalDate(progress.getStatDate()))) {
                todayCountMap.merge(actionCode, count, Integer::sum);
            }
        }

        // 3. 组装 growth_tasks（按 group_sort 升序）
        Map<String, List<Map<String, Object>>> growthTasks = new LinkedHashMap<>();
        BigDecimal todayJScore = BigDecimal.ZERO;

        for (ApBehaviorConfig config : configList) {
            Integer groupSort = config.getGroupSort();
            String groupKey = String.valueOf(groupSort);
            List<Map<String, Object>> taskList = growthTasks.computeIfAbsent(groupKey, k -> new ArrayList<>());

            String actionCode = config.getActionCode();
            int todayCount = todayCountMap.getOrDefault(actionCode, 0);
            int totalCount = totalCountMap.getOrDefault(actionCode, 0);

            // 进度语义：社区基础(1)/社区影响力(4)按累计，社区学习(3)/社区活跃(5)按当日
            int done;
            Integer limit = config.getDailyLimit();
            boolean completed;
            if (groupSort != null && groupSort == 4) {
                // 社区影响力：无限制
                done = totalCount;
                limit = -1;
                completed = false;
            } else if (groupSort != null && groupSort == 1) {
                // 社区基础：一次性任务，按累计
                done = totalCount;
                int taskLimit = (limit == null || limit < 1) ? 1 : limit;
                completed = done >= taskLimit;
            } else {
                // 社区学习/社区活跃：每日重置
                done = todayCount;
                completed = limit != null && limit > 0 && done >= limit;
            }

            Map<String, Object> task = new LinkedHashMap<>();
            task.put("task_id", config.getId() == null ? 0 : config.getId().intValue());
            task.put("action_code", actionCode == null ? "" : actionCode);
            task.put("task_type", config.getGroupType() == null ? "" : config.getGroupType());
            task.put("icon", config.getIconName() == null ? "" : config.getIconName());
            task.put("btn_name", config.getBtnName() == null ? "" : config.getBtnName());
            task.put("service_id", 1);
            task.put("title", config.getActionName() == null ? "" : config.getActionName());
            task.put("score", config.getScore() == null ? BigDecimal.ZERO : config.getScore());
            task.put("limit", limit == null ? -1 : limit);
            task.put("done", done);
            task.put("completed", completed);
            task.put("web_jump_url", config.getWebJumpUrl() == null ? "" : config.getWebJumpUrl());
            task.put("app_jump_url", "");

            taskList.add(task);

            // 4. 累计今日掘友分（今日计数 × 配置积分）
            if (config.getScore() != null && todayCount > 0) {
                todayJScore = todayJScore.add(config.getScore().multiply(BigDecimal.valueOf(todayCount)));
            }
        }

        result.put("growth_tasks", growthTasks);
        result.put("today_jscore", todayJScore);
        return result;
    }

    /**
     * 将 Date 转为 LocalDate（兼容 java.sql.Date / java.util.Date）
     */
    private LocalDate toLocalDate(Date date) {
        if (date == null) {
            return null;
        }
        return new java.sql.Date(date.getTime()).toLocalDate();
    }
}
