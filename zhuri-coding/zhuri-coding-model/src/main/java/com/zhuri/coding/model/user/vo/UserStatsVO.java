package com.heima.model.user.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户/作者内容统计（对齐掘金个人主页 `got_*` 统计口径）。
 * <p>全部字段为查询时实时聚合，不做冗余快照。数值缺省统一为 0。</p>
 */
@Data
public class UserStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 已发布文章数 */
    private Integer articleCount = 0;

    /** 已发布沸点数 */
    private Integer pinCount = 0;

    /** 获赞数（文章+沸点点赞合计，对齐 got_digg_count） */
    private Integer diggCount = 0;

    /** 获阅读数（文章+沸点浏览合计，对齐 got_view_count） */
    private Integer viewCount = 0;

    /** 粉丝数（对齐 got_follower_count） */
    private Integer followerCount = 0;

    /** 关注数（对齐 got_follow_count） */
    private Integer followCount = 0;
}