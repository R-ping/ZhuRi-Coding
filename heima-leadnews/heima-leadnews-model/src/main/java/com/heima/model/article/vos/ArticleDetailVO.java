package com.heima.model.article.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ArticleDetailVO implements Serializable {

    private String articleId;
    private String title;
    private String briefContent;
    private String coverImage;
    private Integer viewCount;
    private Integer collectCount;
    private Integer diggCount;
    private Integer commentCount;
    private String readTime;
    private Integer status;
    private Integer isOriginal;
    private String authorId;
    private String authorName;
    private String authorAvatar;
    private String authorCompany;
    private String authorJobTitle;
    private Integer authorLevel;
    private Integer followerCount;
    private Integer postArticleCount;
    private String categoryId;
    private String categoryName;
    private List<TagVO> tags;
    private Boolean isDigg;
    private Boolean isFollow;
    private Boolean isCollect;
    private String articleContent;
    private String publishTime;
    private List<TocItemVO> tocList;
}