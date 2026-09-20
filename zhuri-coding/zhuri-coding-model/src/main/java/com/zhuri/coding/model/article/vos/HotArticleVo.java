package com.heima.model.article.vos;

import lombok.Data;

import java.io.Serializable;

@Data
public class HotArticleVo implements Serializable {

    private Integer rank;

    private Long id;

    private String title;

    private Long authorId;

    private String authorName;

    private String authorImage;

    private Integer score;

    private Integer views;

    private Integer likes;

    private Integer comment;

    private Integer collection;

    private Boolean isCollected;
}