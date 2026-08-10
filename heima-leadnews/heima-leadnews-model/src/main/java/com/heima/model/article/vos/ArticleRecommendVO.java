package com.heima.model.article.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ArticleRecommendVO implements Serializable {

    private String articleId;
    private String title;
    private String briefContent;
    private String coverImage;
    private String authorName;
    private String authorAvatar;
    private String publishTime;
    private Integer viewCount;
    private Integer collectCount;
    private Integer diggCount;
    private Integer commentCount;
    private String readTime;
    private String categoryName;
    private List<TagVO> tags;
}