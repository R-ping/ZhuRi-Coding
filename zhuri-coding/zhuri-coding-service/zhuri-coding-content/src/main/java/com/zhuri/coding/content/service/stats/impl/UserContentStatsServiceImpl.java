package com.zhuri.coding.content.service.stats.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.service.stats.UserContentStatsService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.follow.pojos.ApFollow;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.user.vo.UserStatsVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户/作者内容统计聚合（查询时实时 COUNT/SUM，不做冗余快照）。
 * <p>对齐掘金个人主页统计口径：文章数、沸点数、获赞数、获阅读数、粉丝数、关注数。</p>
 * 所有源数据都在内容库（leadnews_article）：ap_article / ap_pins / ap_user_follow。
 */
@Slf4j
@Service
public class UserContentStatsServiceImpl implements UserContentStatsService {

    private static final byte PUBLISHED = 9;

    @Autowired
    private ApArticleMapper articleMapper;

    @Autowired
    private ApPinsMapper pinsMapper;

    @Autowired
    private ApFollowMapper followMapper;

    @Override
    public UserStatsVO stats(Long userId) {
        UserStatsVO vo = new UserStatsVO();
        if (userId == null) {
            return vo;
        }
        vo.setUserId(userId);
        vo.setArticleCount(countPublishedArticles(userId));
        vo.setPinCount(countPublishedPins(userId));
        vo.setDiggCount(sumArticles(userId, "likes") + sumPins(userId, "like_count"));
        vo.setViewCount(sumArticles(userId, "views") + sumPins(userId, "view_count"));
        vo.setFollowerCount(Math.toIntExact(followMapper.selectCount(
                new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getFollowUserId, userId))));
        vo.setFollowCount(Math.toIntExact(followMapper.selectCount(
                new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getUserId, userId))));
        return vo;
    }

    private int countPublishedArticles(Long userId) {
        return Math.toIntExact(articleMapper.selectCount(
                new LambdaQueryWrapper<ApArticle>()
                        .eq(ApArticle::getAuthorId, userId)
                        .eq(ApArticle::getStatus, PUBLISHED)));
    }

    private int countPublishedPins(Long userId) {
        return Math.toIntExact(pinsMapper.selectCount(
                new LambdaQueryWrapper<ApPins>()
                        .eq(ApPins::getAuthorId, userId)
                        .eq(ApPins::getStatus, PUBLISHED)));
    }

    private int sumArticles(Long userId, String column) {
        return firstOrZero(articleMapper.selectObjs(new QueryWrapper<ApArticle>()
                .select("IFNULL(SUM(" + column + "),0)")
                .eq("author_id", userId)
                .eq("status", PUBLISHED)));
    }

    private int sumPins(Long userId, String column) {
        return firstOrZero(pinsMapper.selectObjs(new QueryWrapper<ApPins>()
                .select("IFNULL(SUM(" + column + "),0)")
                .eq("author_id", userId)
                .eq("status", PUBLISHED)));
    }

    /** 从 selectObjs 返回的 List 中取首元素并安全转 int */
    private int firstOrZero(List<Object> src) {
        if (src == null || src.isEmpty()) {
            return 0;
        }
        return toInt(src.get(0));
    }

    /** 兼容 selectObjs 返回 BigDecimal/String/Integer 等不同映射类型 */
    private int toInt(Object val) {
        if (val == null) {
            return 0;
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return new BigDecimal(val.toString()).intValue();
    }
}