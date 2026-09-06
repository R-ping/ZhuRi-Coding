package com.heima.content.service.article.impl;

import com.heima.apis.search.ISearchClient;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.service.article.ArticleFreemarkerService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.search.vos.SearchArticleVo;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * 文章 ES 同步服务（单延迟方案，本地消息表单 status 状态机重构后）
 *
 * <p>职责：文章到点发布时，将文章基础字段同步到 ES（正文由 search 服务端反向拉取），
 * 并按结果更新本地消息表 status（成功=4/DONE，失败=3/ES_SYNC_FAIL 交由 20s 扫描补偿）。
 * DB 可见态置位（PUBLISHED）已由 generateArticleEvent 先于本方法完成。
 *
 * <p>历史说明：类名保留 ArticleFreemarker 前缀系历史命名（原方案将文章静态化为 HTML 上传 MinIO），
 * MinIO/FreeMarker 静态化已移除，本文仅承担 ES 同步职责；改名将联动调用方与测试，暂缓。
 */
@Service
@Slf4j
public class ArticleFreemarkerServiceImpl implements ArticleFreemarkerService {

    @Autowired
    private ISearchClient searchClient;
    @Autowired
    private ApArticleEventMapper apArticleEventMapper;

    /**
     * 同步文章到 ES（同步执行）：copyProperties 基础字段 → Feign 同步（search 服务自拉正文）→
     * 更新本地消息表 status。
     * <p>单延迟方案下该方法在延迟任务消费线程内被顺序调用（ES 调用秒级、可接受），
     * 失败不再依赖事件链，由 20s 定时扫描按 status=3 补偿。
     */
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
            updateArticleEventStatus(apArticle.getId(), ArticleConstants.EVENT_STATUS_DONE, null);
        } catch (Exception e) {
            log.error("文章同步到ES失败, articleId={}", apArticle.getId(), e);
            updateArticleEventStatus(apArticle.getId(), ArticleConstants.EVENT_STATUS_ES_SYNC_FAIL,
                new Date(System.currentTimeMillis() + ArticleConstants.RETRY_INTERVAL_MS));
        }
    }

    /**
     * 更新本地消息表 status（单状态机）
     *
     * @param articleId  文章ID
     * @param status     目标状态：1=INIT 2=DB_SET_FAIL 3=ES_SYNC_FAIL 4=DONE
     * @param retryTime  下次重试时间；仅失败态(3)需要顺延，成功/终态传 null
     */
    private void updateArticleEventStatus(Long articleId, byte status, Date retryTime) {
        try {
            ArticleEvent event = apArticleEventMapper.selectOne(
                Wrappers.<ArticleEvent>lambdaQuery().eq(ArticleEvent::getArticleId, articleId));
            if (event != null) {
                event.setStatus(status);
                event.setRetryTime(retryTime);
                event.setUpdateTime(new Date());
                apArticleEventMapper.updateArticleEvent(event);
            }
        } catch (Exception e) {
            log.error("更新本地消息表状态失败, articleId={}", articleId, e);
        }
    }
}
