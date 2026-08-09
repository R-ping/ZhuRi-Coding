package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.heima.apis.search.ISearchClient;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.event.ArticleBuildCompleteEvent;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.service.article.ArticleFreemarkerService;
import com.heima.content.utils.MarkdownUtils;
import com.heima.file.config.MinIOConfig;
import com.heima.file.utils.MinioUtil;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.search.vos.SearchArticleVo;
import com.heima.model.search.vos.TocItem;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class ArticleFreemarkerServiceImpl implements ArticleFreemarkerService {

    @Autowired
    private MinioUtil minioUtil;
    @Autowired
    private MinIOConfig prop;
    @Autowired
    private ApArticleContentMapper apArticleContentMapper;
    @Autowired
    private ISearchClient searchClient;
    @Autowired
    private ApArticleEventMapper apArticleEventMapper;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 生成静态文件上传到minIO中
     * 构建完成后发布 ArticleBuildCompleteEvent，由监听器处理后续的发布或延迟任务逻辑
     */
    @Async
    @Override
    public void buildHTMLAndSend(ApArticle apArticle, String content, Long taskId, long lastExecuteInterval) {
        //已知文章的id
        SearchArticleVo vo = new SearchArticleVo();
        BeanUtils.copyProperties(apArticle, vo);
        String markdown = resolveContent(apArticle.getId(), content);
        vo.setContent(markdown);
        buildHtmlContent(vo, markdown);
        buildFileNameAndPath(apArticle, vo);
        try {
            // 同步文章到ES
            searchClient.syncArticle(vo);
            log.info("文章同步到ES成功, articleId={}", apArticle.getId());
            updateArticleEventStatus(apArticle.getId(), "es", (byte) 2);
        } catch (Exception e) {
            log.error("文章同步到ES失败, articleId={}", apArticle.getId(), e);
            updateArticleEventStatus(apArticle.getId(), "es", (byte) 1);
        }
        // 上传 HTML 到 MinIO
        try {
            String htmlContent = vo.getHtmlContent();
            if (StringUtils.isNotBlank(htmlContent)) {
                minioUtil.uploadString(htmlContent, vo.getFileName(), "text/html");
                log.info("文章HTML上传MinIO成功, articleId={}", apArticle.getId());
                updateArticleEventStatus(apArticle.getId(), "minio", (byte) 2);
            }
        } catch (Exception e) {
            log.error("文章HTML上传MinIO失败, articleId={}", apArticle.getId(), e);
            updateArticleEventStatus(apArticle.getId(), "minio", (byte) 1);
        }
        // 发布事件，由监听器处理后续逻辑（立即发布或添加延迟任务）
        eventPublisher.publishEvent(new ArticleBuildCompleteEvent(
            apArticle.getId(), taskId, lastExecuteInterval));
    }

    /**
     * 解析文章内容：优先使用传入的内容，为空则从数据库读取
     */
    private String resolveContent(Long articleId, String content) {
        if (StringUtils.isNotBlank(content)) {
            return MarkdownUtils.normalizeContent(content);
        }
        ApArticleContent articleContent = apArticleContentMapper.selectOne(
            Wrappers.<ApArticleContent>lambdaQuery().eq(ApArticleContent::getArticleId, articleId));
        return articleContent != null ? MarkdownUtils.normalizeContent(articleContent.getContent()) : "";
    }

    /**
     * 将 Markdown 渲染为 HTML 并提取目录
     */
    private void buildHtmlContent(SearchArticleVo vo, String markdown) {
        String rawHtml = MarkdownUtils.toHtml(markdown);
        List<TocItem> tocList = MarkdownUtils.extractToc(rawHtml);
        String htmlContent = MarkdownUtils.injectHeadingAnchors(rawHtml);
        vo.setHtmlContent(htmlContent);
        vo.setTocList(tocList);
    }

    private void buildFileNameAndPath(ApArticle apArticle, SearchArticleVo vo) {
        // "yyyy/MM/dd/articleId"
        String objectName = minioUtil.builderFilePath("articles", String.valueOf(apArticle.getId()));
        vo.setFileName(objectName);
        // path http://xx:9000/bucketName/2020/08/05/articleId
        String path = prop.getReadPath() + "/" + prop.getBucket() + "/" + objectName;
        vo.setStaticUrl(path);
    }

    /**
     * 更新本地消息表中指定操作的状态
     *
     * @param articleId 文章ID
     * @param type 操作类型：minio/es
     * @param status 状态值：0=初始化 1=待重试 2=成功
     */
    private void updateArticleEventStatus(Long articleId, String type, byte status) {
        try {
            ArticleEvent event = apArticleEventMapper.selectOne(
                Wrappers.<ArticleEvent>lambdaQuery().eq(ArticleEvent::getArticleId, articleId));
            if (event != null) {
                if ("minio".equals(type)) {
                    event.setMinioStatus(status);
                } else if ("es".equals(type)) {
                    event.setEsStatus(status);
                }
                if (status == 1) { // 待重试
                    event.setRetryTime(new Date(System.currentTimeMillis() + ArticleConstants.RETRY_INTERVAL_MS));
                }
                apArticleEventMapper.updateArticleEvent(event);
            }
        } catch (Exception e) {
            log.error("更新本地消息表状态失败, articleId={}, type={}", articleId, type, e);
        }
    }

}