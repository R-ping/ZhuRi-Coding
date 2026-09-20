package com.zhuri.coding.content.service.article;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhuri.coding.model.article.pojos.ApArticleConfig;

import java.util.Map;

public interface ApArticleConfigService extends IService<ApArticleConfig> {
    /**
     * 修改文章
     * @param map
     */
    void updateByMap(Map map);
}