package com.heima.content.service.article;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.common.dtos.ResponseResult;

public interface ArticleManageService extends IService<ApArticle> {

    ResponseResult list(Long authorId, Integer page, Integer size, String status, String title);

    ResponseResult statistics(Long authorId);

    ResponseResult deleteArticle(Long id);

    ResponseResult getArticleById(Long id);
}