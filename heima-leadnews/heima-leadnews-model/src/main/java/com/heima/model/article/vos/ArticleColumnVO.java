package com.heima.model.article.vos;

import lombok.Data;

import java.io.Serializable;

@Data
public class ArticleColumnVO implements Serializable {

    private Long columnId;
    private String columnTitle;
    private String columnCover;
    private String columnDescription;
    private Integer followCnt;
    private Integer articleCnt;
    private Boolean isFollow;
    private String prevArticleId;
    private String prevArticleTitle;
    private String nextArticleId;
    private String nextArticleTitle;
}