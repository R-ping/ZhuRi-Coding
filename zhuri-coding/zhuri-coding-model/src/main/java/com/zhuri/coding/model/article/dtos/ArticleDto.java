package com.zhuri.coding.model.article.dtos;

import com.zhuri.coding.model.article.pojos.ApArticle;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor       // 新增 — 保证 new ArticleDto() 能用
@AllArgsConstructor      // 新增
public class ArticleDto  extends ApArticle {

    /**
     * 文章内容
     */
    private String content;
}
