package com.heima.apis.article.fallback;

import com.heima.apis.article.IArticleClient;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class IArticleClientFallback implements IArticleClient {

    @Override
    public void eventUpdate(ArticleEvent event) {
        log.error("远程更新article操作事件失败, eventId={}", event != null ? event.getId() : null);
    }

    @Override
    public ResponseResult getContent(Long articleId) {
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR,"获取文章内容异常");
    }

    @Override
    public ApArticle getArticleInfo(Long articleId) {
        log.error("远程获取文章信息异常, articleId={}", articleId);
        throw new RuntimeException("获取文章信息异常, articleId=" + articleId);
    }

    @Override
    public ResponseResult publishArticle(Long articleId) {
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR,"文章发布异常");
    }

    @Override
    public List<Map<String, Object>> listByAuthorId(ArticleDto dto) {
        return Collections.emptyList();
    }

    @Override
    public ResponseResult getStatisticsFeign(Long userId) {
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "获取用户统计数据异常");
    }

}
