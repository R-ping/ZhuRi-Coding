package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ArticleAutoScanService;
import com.heima.content.service.article.ArticleTaskService;
import com.heima.content.service.article.processor.AIViolationProcessor;
import com.heima.content.service.article.processor.AuditFailProcessor;
import com.heima.content.service.article.processor.AuditProcessorContext;
import com.heima.content.service.article.processor.BehaviorEventProcessor;
import com.heima.content.service.article.processor.ImageScanProcessor;
import com.heima.content.service.article.processor.PowerBonusProcessor;
import com.heima.content.service.article.processor.SimilarityProcessor;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 文章自动审核服务实现
 *
 * 采用责任链模式编排审核流程，每个审核环节由独立的 Processor 处理：
 * 1. AIViolationProcessor - AI违规内容检测 + 内容质量分析
 * 2. ImageScanProcessor - 图片审核
 * 3. SimilarityProcessor - RAG相似度检验 + 推荐状态更新
 * 4. PowerBonusProcessor - 逐力值加成 + 自动推荐
 * 5. BehaviorEventProcessor - 发布行为事件
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleAutoScanServiceImpl implements ArticleAutoScanService {

    private final ApArticleMapper apArticleMapper;
    private final ApArticleContentMapper apArticleContentMapper;
    private final AIViolationProcessor aiViolationProcessor;
    private final ImageScanProcessor imageScanProcessor;
    private final SimilarityProcessor similarityProcessor;
    private final PowerBonusProcessor powerBonusProcessor;
    private final BehaviorEventProcessor behaviorEventProcessor;
    private final AuditFailProcessor auditFailProcessor;
    private final ArticleTaskService articleTaskService;

    @Override
    @Async
    public CompletableFuture<Boolean> autoScanArticle(Long articleId) {
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null) {
            log.error("ArticleAutoScanServiceImpl-文章不存在, articleId={}", articleId);
            return CompletableFuture.completedFuture(false);
        }

        if (!article.getStatus().equals(Status.SUBMIT.getCode())) {
            log.info("文章状态非审核中，跳过审核, articleId={}, status={}", articleId, article.getStatus());
            return CompletableFuture.completedFuture(true);
        }

        // 获取文章内容
        QueryWrapper<ApArticleContent> contentQuery = new QueryWrapper<>();
        contentQuery.eq("article_id", articleId);
        ApArticleContent articleContent = apArticleContentMapper.selectOne(contentQuery);
        String content = articleContent != null ? articleContent.getContent() : "";

        // 初始化审核上下文
        AuditProcessorContext context = new AuditProcessorContext();

        // 1. AI违规内容检测 + 内容质量分析
        if (!aiViolationProcessor.process(article, content, context)) {
            String failReason = context.getExtra("failReason");
            auditFailProcessor.handleFail(article, failReason);
            log.info("AI违规检测未通过, articleId={}, reason={}", articleId, failReason);
            return CompletableFuture.completedFuture(false);
        }

        // 2. 图片审核
        if (!imageScanProcessor.process(article, content, context)) {
            String failReason = context.getExtra("failReason");
            auditFailProcessor.handleFail(article, failReason);
            log.info("图片审核未通过, articleId={}, reason={}", articleId, failReason);
            return CompletableFuture.completedFuture(false);
        }

        // 3. RAG相似度检验 + 推荐状态更新
        similarityProcessor.process(article, content, context);

        // 4. 逐力值加成 + 自动推荐通知
        powerBonusProcessor.process(article, content, context);

        log.info("文章审核完成, articleId={}, isHighSimilarity={}", articleId, context.isHighSimilarity());

        // 5. 发布行为事件
        behaviorEventProcessor.process(article, content, context);

        // 6. 添加到调度任务
        articleTaskService.addArticleToTask(article.getId(), article.getPublishTime());
        return CompletableFuture.completedFuture(true);
    }
}