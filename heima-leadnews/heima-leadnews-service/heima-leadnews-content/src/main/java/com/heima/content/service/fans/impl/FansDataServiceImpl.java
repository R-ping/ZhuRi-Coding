package com.heima.content.service.fans.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.service.fans.FansDataService;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FansDataServiceImpl implements FansDataService {

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public ResponseResult getFansStatistics(String startDate, String endDate) {
        Integer userId = getCurrentUserId();
        if (userId == null) {
            return ResponseResult.okResult(new HashMap<>());
        }

        Map<String, Object> current = calcFansMetrics(userId, startDate, endDate);
        long periodDays = ChronoUnit.DAYS.between(LocalDate.parse(startDate, DATE_FORMATTER), LocalDate.parse(endDate, DATE_FORMATTER)) + 1;
        String prevStart = LocalDate.parse(startDate, DATE_FORMATTER).minusDays(periodDays).format(DATE_FORMATTER);
        String prevEnd = LocalDate.parse(startDate, DATE_FORMATTER).minusDays(1).format(DATE_FORMATTER);
        Map<String, Object> previous = calcFansMetrics(userId, prevStart, prevEnd);

        Map<String, Object> data = new HashMap<>();
        data.put("totalFans", current.get("totalFans"));
        data.put("totalTrend", ((Number) current.get("totalFans")).intValue() - ((Number) previous.get("totalFans")).intValue());
        data.put("interactiveFans", current.get("interactiveFans"));
        data.put("interactiveTrend", ((Number) current.get("interactiveFans")).intValue() - ((Number) previous.get("interactiveFans")).intValue());
        data.put("newFans", current.get("newFans"));
        data.put("newFansTrend", ((Number) current.get("newFans")).intValue() - ((Number) previous.get("newFans")).intValue());
        data.put("unfollowCount", 0);
        data.put("unfollowTrend", 0);
        int netGrowth = ((Number) current.get("newFans")).intValue();
        int netGrowthPrev = ((Number) previous.get("newFans")).intValue();
        data.put("netGrowth", netGrowth);
        data.put("netGrowthTrend", netGrowth - netGrowthPrev);

        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getFansTrend(String startDate, String endDate, Integer days) {
        Integer userId = getCurrentUserId();
        if (userId == null) {
            return ResponseResult.okResult(new ArrayList<>());
        }

        List<Map<String, Object>> trendData = new ArrayList<>();
        LocalDate start = LocalDate.parse(startDate, DATE_FORMATTER);
        LocalDate end = LocalDate.parse(endDate, DATE_FORMATTER);

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String dateStr = date.format(DATE_FORMATTER);
            Map<String, Object> point = new HashMap<>();
            point.put("date", dateStr);

            // cumulative total fans up to this date
            int totalFans = countFollowersUpTo(userId, dateStr);
            point.put("totalFans", totalFans);

            // interactive fans up to this date (same as total for now)
            point.put("interactiveFans", totalFans);

            // new fans on this specific date
            int newFans = countFollowersOnDate(userId, dateStr);
            point.put("newFans", newFans);

            point.put("unfollowCount", 0);
            point.put("netGrowth", newFans);

            trendData.add(point);
        }

        return ResponseResult.okResult(trendData);
    }

    @Override
    public ResponseResult getFansList(Integer page, Integer size) {
        Integer userId = getCurrentUserId();
        if (userId == null) {
            return ResponseResult.okResult(new HashMap<>());
        }

        Page<ApFollow> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getFollowUserId, userId);
        wrapper.orderByDesc(ApFollow::getCreatedTime);

        IPage<ApFollow> result = apFollowMapper.selectPage(pageParam, wrapper);

        List<Map<String, Object>> list = result.getRecords().stream().map(follow -> {
            Map<String, Object> m = new HashMap<>();
            m.put("userId", follow.getUserId());

            // Query user info from ap_user table
            ApUser fanUser = queryUserById(follow.getUserId());
            if (fanUser != null) {
                m.put("nickName", fanUser.getNickname() != null ? fanUser.getNickname() : "");
                m.put("avatar", fanUser.getImage() != null ? fanUser.getImage() : "");
            } else {
                m.put("nickName", "");
                m.put("avatar", "");
            }

            // Check if current user follows back
            boolean isFollowed = checkFollowBack(userId, follow.getUserId());
            m.put("isFollowed", isFollowed);

            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult followFans(Integer targetUserId) {
        Integer userId = getCurrentUserId();
        if (userId == null) {
            return ResponseResult.okResult(null);
        }
        if (targetUserId == null) {
            return ResponseResult.okResult(null);
        }
        if (userId.equals(targetUserId)) {
            return ResponseResult.okResult(null);
        }

        // Check if already following
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getUserId, userId);
        wrapper.eq(ApFollow::getFollowUserId, targetUserId);
        ApFollow existing = apFollowMapper.selectOne(wrapper);
        if (existing != null) {
            return ResponseResult.okResult(null);
        }

        try {
            ApFollow follow = new ApFollow();
            follow.setUserId(userId);
            follow.setFollowUserId(targetUserId);
            follow.setCreatedTime(new Date());
            apFollowMapper.insert(follow);
        } catch (DuplicateKeyException e) {
            // 并发下已关注，幂等静默处理
            log.info("并发关注冲突，幂等跳过, userId={}, targetUserId={}", userId, targetUserId);
        }

        return ResponseResult.okResult(null);
    }

    @Override
    public ResponseResult getFansPortrait() {
        Map<String, Object> data = new HashMap<>();
        data.put("genderDistribution", new ArrayList<>());
        data.put("ageDistribution", new ArrayList<>());
        data.put("regionDistribution", new ArrayList<>());
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getFansAvatars(Integer page, Integer size) {
        Integer userId = getCurrentUserId();
        if (userId == null) {
            return ResponseResult.okResult(new HashMap<>());
        }

        Page<ApFollow> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getFollowUserId, userId);
        wrapper.orderByDesc(ApFollow::getCreatedTime);

        IPage<ApFollow> result = apFollowMapper.selectPage(pageParam, wrapper);

        List<Map<String, Object>> list = result.getRecords().stream().map(follow -> {
            Map<String, Object> m = new HashMap<>();
            ApUser fanUser = queryUserById(follow.getUserId());
            if (fanUser != null) {
                m.put("userId", fanUser.getId());
                m.put("avatar", fanUser.getImage() != null ? fanUser.getImage() : "");
                m.put("nickName", fanUser.getNickname() != null ? fanUser.getNickname() : "");
            } else {
                m.put("userId", follow.getUserId());
                m.put("avatar", "");
                m.put("nickName", "");
            }
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    // ==================== Private Helpers ====================

    private Map<String, Object> calcFansMetrics(Integer userId, String startDate, String endDate) {
        Date start = parseDate(startDate);
        Date end = parseDateEnd(endDate);

        // Total fans: all followers up to endDate
        LambdaQueryWrapper<ApFollow> totalWrapper = new LambdaQueryWrapper<>();
        totalWrapper.eq(ApFollow::getFollowUserId, userId);
        totalWrapper.le(ApFollow::getCreatedTime, end);
        int totalFans = apFollowMapper.selectCount(totalWrapper).intValue();

        // New fans: followers created within the date range
        LambdaQueryWrapper<ApFollow> newWrapper = new LambdaQueryWrapper<>();
        newWrapper.eq(ApFollow::getFollowUserId, userId);
        newWrapper.between(ApFollow::getCreatedTime, start, end);
        int newFans = apFollowMapper.selectCount(newWrapper).intValue();

        // Interactive fans: same as total for now (no separate interaction tracking)
        int interactiveFans = totalFans;

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalFans", totalFans);
        metrics.put("interactiveFans", interactiveFans);
        metrics.put("newFans", newFans);
        return metrics;
    }

    private int countFollowersUpTo(Integer userId, String dateStr) {
        Date end = parseDateEnd(dateStr);
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getFollowUserId, userId);
        wrapper.le(ApFollow::getCreatedTime, end);
        return apFollowMapper.selectCount(wrapper).intValue();
    }

    private int countFollowersOnDate(Integer userId, String dateStr) {
        Date start = parseDate(dateStr);
        Date end = parseDateEnd(dateStr);
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getFollowUserId, userId);
        wrapper.between(ApFollow::getCreatedTime, start, end);
        return apFollowMapper.selectCount(wrapper).intValue();
    }

    private boolean checkFollowBack(Integer userId, Integer followUserId) {
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getUserId, userId);
        wrapper.eq(ApFollow::getFollowUserId, followUserId);
        return apFollowMapper.selectCount(wrapper) > 0;
    }

    private ApUser queryUserById(Integer userId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, nickname, image FROM ap_user WHERE id = ?", userId);
            if (rows != null && !rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                ApUser user = new ApUser();
                user.setId((Integer) row.get("id"));
                user.setNickname((String) row.get("nickname"));
                user.setImage((String) row.get("image"));
                return user;
            }
        } catch (Exception e) {
            log.warn("queryUserById error for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    private Integer getCurrentUserId() {
        ApUser user = AppThreadLocalUtil.getUser();
        return user != null ? user.getId() : null;
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