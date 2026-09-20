package com.zhuri.coding.apis.article.fallback;

import com.zhuri.coding.apis.article.IArticleClient;
import com.zhuri.coding.model.article.dtos.ArticleDto;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ArticleEvent;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
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
