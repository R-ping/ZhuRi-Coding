package com.heima.content.service.article.impl;

import com.heima.apis.search.ISearchClient;
import com.heima.content.event.ArticleBuildCompleteEvent;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.service.article.ArticleFreemarkerService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.search.vos.SearchArticleVo;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.heima.common.constants.ArticleConstants;

/**
 * 文章 ES 同步服务（单延迟方案）
 *
 * <p>职责收敛：文章到点发布时，将文章基础字段同步到 ES（正文由 search 服务端反向拉取），
 * 更新本地消息表 es_status，并发布构建完成事件驱动后续"置 DB/ES 发布态 + 消费任务"。
 *
 * <p>历史说明：类名保留 ArticleFreemarker 前缀系历史命名（原方案将文章静态化为 HTML 上传 MinIO），
 * MinIO/FreeMarker 静态化已移除，本文仅承担 ES 同步职责；改名将联动调用方与测试，暂缓。
 */
@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class ArticleFreemarkerServiceImpl implements ArticleFreemarkerService {

    @Autowired
    private ISearchClient searchClient;
    @Autowired
    private ApArticleEventMapper apArticleEventMapper;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 同步文章到 ES：copyProperties 基础字段 → Feign 同步（search 服务自拉正文）→ 更新 es_status → 发布完成事件。
     * 单延迟方案：本方法在延迟任务到点时被 @Async 触发，成功后由事件监听器置 DB/ES 发布态并消费任务。
     */
    @Async
    @Override
    public void buildHTMLAndSend(ApArticle apArticle, Long taskId) {
        if (apArticle == null || apArticle.getId() == null) {
            log.error("同步文章到ES失败，文章参数为空");
            return;
        }
        SearchArticleVo vo = new SearchArticleVo();
        BeanUtils.copyProperties(apArticle, vo);
        try {
            // 同步文章到ES（正文由 search 服务 syncArticle 内 Feign 反向拉取，无需在此读取/加工内容）
            searchClient.syncArticle(vo);
            log.info("文章同步到ES成功, articleId={}", apArticle.getId());
            updateArticleEventStatus(apArticle.getId(), (byte) 2);
        } catch (Exception e) {
            log.error("文章同步到ES失败, articleId={}", apArticle.getId(), e);
            updateArticleEventStatus(apArticle.getId(), (byte) 1);
        }
        // 发布事件，由监听器统一置 DB/ES 发布态并消费任务（单延迟：同步完成即发布）
        eventPublisher.publishEvent(new ArticleBuildCompleteEvent(apArticle.getId(), taskId));
    }

    /**
     * 更新本地消息表中指定操作的状态
     *
     * @param articleId 文章ID
     * @param status 状态值：0=初始化 1=待重试 2=成功
     */
    private void updateArticleEventStatus(Long articleId, byte status) {
        try {
            ArticleEvent event = apArticleEventMapper.selectOne(
                Wrappers.<ArticleEvent>lambdaQuery().eq(ArticleEvent::getArticleId, articleId));
            if (event != null) {
                event.setEsStatus(status);
                if (status == 1) { // 待重试
                    event.setRetryTime(new Date(System.currentTimeMillis() + ArticleConstants.RETRY_INTERVAL_MS));
                }
                apArticleEventMapper.updateArticleEvent(event);
            }
        } catch (Exception e) {
            log.error("更新本地消息表状态失败, articleId={}", articleId, e);
        }
    }
}
