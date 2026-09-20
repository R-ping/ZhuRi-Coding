package com.heima.model.user.vo;

import lombok.Data;

@Data
public class TagDiscoverVO {
    private Integer id;
    private String tagName;
    private Integer followCount;  // 关注数
    private Integer articleCount; // 文章数（暂用 sortOrder 代替）
    private Boolean isFollowing;  // 当前用户是否已关注
}