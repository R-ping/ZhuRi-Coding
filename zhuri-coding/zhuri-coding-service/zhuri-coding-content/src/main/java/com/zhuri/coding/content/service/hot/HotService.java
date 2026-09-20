package com.heima.content.service.hot;

import com.heima.model.article.vos.HotArticleVo;
import com.heima.model.article.vos.HotAuthorVo;

import java.util.List;
import java.util.Map;

public interface HotService {

    List<HotArticleVo> getHotArticles(String category, Integer limit);

    List<HotArticleVo> getCollectedArticles(Integer limit);

    List<HotAuthorVo> getHotAuthors(String period, Integer limit);

    Map<String, Object> getHotMeta(String tab, String category, String period);
}