package com.zhuri.coding.content.service.article;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface ArticleManageService extends IService<ApArticle> {

    ResponseResult list(Long authorId, Integer page, Integer size, String status, String title);

    ResponseResult statistics(Long authorId);

    ResponseResult deleteArticle(Long id);

    ResponseResult getArticleById(Long id);
}