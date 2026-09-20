package com.heima.model.article.vos;

import lombok.Data;

import java.io.Serializable;

@Data
public class HotAuthorVo implements Serializable {

    private Integer rank;

    private Integer userId;

    private String userName;

    private String userImage;

    private Integer level;

    private Long hotScore;

    private Integer qualityArticles;

    private Long totalLikes;

    private Long totalCollections;

    private Integer fans;

    private Boolean isFollowed;
}