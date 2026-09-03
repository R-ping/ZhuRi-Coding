package com.heima.model.user.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class UserProfileVO {

    private Long userId;

    private String username;

    private String avatarUrl;

    private Date careerStartDate;

    private String careerDirection;

    private String position;

    private String company;

    private String website;

    private String bio;

    /** 地区 */
    private String region;

    /** 学历/学校 */
    private String education;

    /** 技术技能标签（对齐掘金 skills） */
    private List<String> skills = new ArrayList<>();

    /** 等级快照 */
    private String level;

    /** 已发布文章数（查询聚合） */
    private Integer articleCount = 0;

    /** 已发布沸点数（查询聚合） */
    private Integer pinCount = 0;

    /** 获赞数（查询聚合） */
    private Integer diggCount = 0;

    /** 获阅读数（查询聚合） */
    private Integer viewCount = 0;

    /** 粉丝数（查询聚合） */
    private Integer followerCount = 0;

    /** 关注数（查询聚合） */
    private Integer followCount = 0;

    private List<Integer> selectedTagIds = new ArrayList<>();

    private List<TagGroupVO> tagGroups = new ArrayList<>();
}