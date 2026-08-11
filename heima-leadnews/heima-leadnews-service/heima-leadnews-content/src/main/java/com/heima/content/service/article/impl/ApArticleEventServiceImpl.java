package com.heima.content.service.article.impl;

import cn.hutool.json.JSONUtil;
import com.heima.apis.search.ISearchClient;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.service.article.ApArticleEventService;
import com.heima.content.service.article.ApArticleService;
// import com.heima.file.utils.MinioUtil; // MinIO 已移除，文章详情页改为 MVC（FreeMarker）服务端渲染
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.search.vos.SearchArticleVo;
import java.util.ArrayList;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ApArticleEventServiceImpl implements ApArticleEventService {

    @Autowired
    private ApArticleEventMapper apArticleEventMapper;

    @Autowired
    private ISearchClient searchClient;

    @Autowired
    private ApArticleService apArticleService;
    // @Autowired
    // private MinioUtil minioUtil; // MinIO 已移除，文章详情页改为 MVC（FreeMarker）服务端渲染

    @Override
    public void updateEvent(ArticleEvent event) {
        apArticleEventMapper.updateArticleEvent(event);
    }

    @Scheduled(fixedRate = 20000)
    public void processEvent() {
        ArrayList<Long> success_list = new ArrayList<>();
        for (ArticleEvent event : apArticleEventMapper.loadArticleEvent()) {
            SearchArticleVo searchArticleVo = JSONUtil.toBean(event.getParameter(), SearchArticleVo.class);
            // 检查是否超过最大重试次数
            if (event.getRetryCount() != null && event.getMaxRetryCount() != null
                && event.getRetryCount() >= event.getMaxRetryCount()) {
                log.error("文章事件超过最大重试次数，标记为死信, articleId={}, retryCount={}, maxRetryCount={}",
                    event.getArticleId(), event.getRetryCount(), event.getMaxRetryCount());
//                success_list.add(event.getArticleId());
                continue;
            }

            // 所有状态都成功（MinIO 已移除，仅保留 ES 与发布状态判定），从本地消息表删除
            if (event.getEsStatus() != null && event.getEsStatus() == 2
                && event.getPubStatus() != null && event.getPubStatus() == 2) {
                success_list.add(event.getArticleId());
                continue;
            }

            // 重试时间检查（距离上次重试至少 5 秒）
            long retry_time = event.getRetryTime() != null ? event.getRetryTime().getTime() : 0;
            long expect_time = System.currentTimeMillis() - 5000;
            boolean isBackward = retry_time <= expect_time;

            // ES 同步重试
            if (event.getEsStatus() != null && event.getEsStatus() == 1 && isBackward) {
                try {
                    if (searchArticleVo != null) {
                        searchClient.syncArticle(searchArticleVo);
                        event.setEsStatus((byte) 2);
                        event.setRetryCount((byte) (event.getRetryCount() != null ? event.getRetryCount() + 1 : 1));
                        event.setUpdateTime(new Date());
                        apArticleEventMapper.updateArticleEvent(event);
                        log.info("ES同步重试成功, articleId={}", event.getArticleId());
                    }
                } catch (Exception e) {
                    log.error("ES同步重试失败, articleId={}", event.getArticleId(), e);
                }
            }

            // MinIO 上传重试（已移除，文章详情页改为 MVC-FreeMarker 服务端渲染，不再依赖 MinIO 静态 HTML）
            // if (event.getMinioStatus() != null && event.getMinioStatus() == 1 && isBackward) {
                // try {
                    // String objectName = minioUtil.builderFilePath("articles", String.valueOf(searchArticleVo.getId()));
                    // minioUtil.uploadString(searchArticleVo.getHtmlContent(), objectName, "text/html");
                    // event.setMinioStatus((byte) 2);
                    // event.setRetryCount((byte) (event.getRetryCount() != null ? event.getRetryCount() + 1 : 1));
                    // event.setUpdateTime(new Date());
                    // apArticleEventMapper.updateArticleEvent(event);
                    // log.info("MinIO重试处理成功, articleId={}", event.getArticleId());
                // } catch (Exception e) {
                    // event.setMinioStatus((byte) 2);
                    // event.setRetryCount((byte) (event.getRetryCount() != null ? event.getRetryCount() + 1 : 1));
                    // event.setUpdateTime(new Date());
                    // apArticleEventMapper.updateArticleEvent(event);
                    // log.error("MinIO重试处理异常, articleId={}", event.getArticleId(), e);
                // }
            // }

            // 发布状态重试：调用 apArticleService.updateArticleStatus()
            // 该方法会更新 DB 文章状态为 PUBLISHED，并 Feign 调用 ES 更新状态
            if (event.getPubStatus() != null && event.getPubStatus() == 1 && isBackward) {
                try {
                    apArticleService.updateArticleStatus(event.getArticleId());
                    event.setPubStatus((byte) 2);
                    event.setRetryCount((byte) (event.getRetryCount() != null ? event.getRetryCount() + 1 : 1));
                    event.setUpdateTime(new Date());
                    apArticleEventMapper.updateArticleEvent(event);
                    log.info("发布状态重试成功, articleId={}", event.getArticleId());
                } catch (Exception e) {
                    log.error("发布状态重试失败, articleId={}", event.getArticleId(), e);
                }
            }
        }
        // 删除所有成功的记录（包括死信）
        if (!success_list.isEmpty()) {
            apArticleEventMapper.deleteArticleEvent(success_list);
            log.info("清理本地消息表成功记录, count={}", success_list.size());
        }
    }
}