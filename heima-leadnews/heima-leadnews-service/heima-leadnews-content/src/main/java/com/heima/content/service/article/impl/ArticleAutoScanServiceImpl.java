package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.apis.notification.INotificationClient;
import com.heima.common.aliyun.GreenImageScanPlus;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.mapper.article.ApArticleAuditRecordMapper;
import com.heima.content.mapper.article.ApArticleConfigMapper;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ArticleAutoScanService;
import com.heima.content.service.article.ArticleSimilarityService;
import com.heima.content.service.article.ArticleTaskService;
import com.heima.content.service.article.BailianAiService;
import com.heima.content.service.level.LevelService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleAuditRecord;
import com.heima.model.article.pojos.ApArticleConfig;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.article.pojos.ApArticleDraft.ContPic;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ArticleAutoScanServiceImpl implements ArticleAutoScanService {

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleContentMapper apArticleContentMapper;

    @Autowired
    private ApArticleConfigMapper apArticleConfigMapper;

    @Autowired
    private GreenImageScanPlus greenImageScan;

    @Autowired
    private BailianAiService bailianAiService;

    @Autowired
    private ArticleSimilarityService articleSimilarityService;

    @Autowired
    private LevelService levelService;
    @Autowired
    private ArticleTaskService articleTaskService;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Autowired
    private ApArticleAuditRecordMapper apArticleAuditRecordMapper;

    @Autowired(required = false)
    private BehaviorEventBus behaviorEventBus;

    private static final ObjectMapper objectMapper = new ObjectMapper();
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

        // 从文章内容表获取内容
        QueryWrapper<ApArticleContent> contentQuery = new QueryWrapper<>();
        contentQuery.eq("article_id", articleId);
        ApArticleContent articleContent = apArticleContentMapper.selectOne(contentQuery);
        String content = articleContent != null ? articleContent.getContent() : "";

        // 1. AI违规内容检测（替代原有文本审核，解决greeTextScan 600字符限制问题）
        log.info("开始AI违规内容检测, articleId={}", articleId);
        try {
            Map<String, Object> violationResult = bailianAiService.checkViolation(article, content);
            if (violationResult != null && Boolean.TRUE.equals(violationResult.get("is_violation"))) {
                String violationType = (String) violationResult.getOrDefault("violation_type", "违规内容");
                String violationReason = (String) violationResult.getOrDefault("violation_reason", "文章内容违反社区规范");
                String reason = violationType + ": " + violationReason;
                updateArticle(article, Status.FAIL.getCode(), reason);
                log.info("AI违规检测未通过, articleId={}, type={}, reason={}", articleId, violationType, violationReason);
                return CompletableFuture.completedFuture(false);
            }
            log.info("AI违规检测通过, articleId={}", articleId);
        } catch (Exception e) {
            log.error("AI违规检测异常, articleId={}, 降级通过", articleId, e);
        }
        // 2. 图片审核
        if (content != null && !content.isEmpty()) {
            List<ContPic> contPics = article.getContPics();
            if (contPics == null) {
                contPics = new ArrayList<>();
            }
            String coverImage = article.getCoverImage();
            if (coverImage != null && !coverImage.isEmpty()) {
                ContPic contPic = new ContPic();
                contPic.setPicUrl(coverImage);
                contPics.add(contPic);
            }
            boolean isImageScan = handleImageMsg(contPics, article);
            if (!isImageScan) {
                log.info("文章图片审核未通过, articleId={}", articleId);
                return CompletableFuture.completedFuture(false);
            }
        }
        log.info("文章图片审核通过, articleId={}", articleId);
        // 3. AI内容分析（百炼平台）
        log.info("开始AI内容分析, articleId={}", articleId);
        Map<String, Object> aiResult = null;
        try {
            aiResult = bailianAiService.analyzeArticle(article, content);
        } catch (Exception e) {
            log.error("AI内容分析异常, articleId={}, 将降级为仅通过内容安全审核", articleId, e);
        }

        // 4. RAG相似度检验
        boolean isHighSimilarity = false;
        try {
            if (content != null && !content.isEmpty()) {
                Map<String, Object> similarityResult = articleSimilarityService.checkSimilarity(article, content);
                if (similarityResult != null && Boolean.TRUE.equals(similarityResult.get("isSimilar"))) {
                    isHighSimilarity = true;
                    log.info("检测到高相似度文章, articleId={}, similarity={}",
                        articleId, similarityResult.get("maxSimilarity"));
                }
            }
        } catch (Exception e) {
            log.error("RAG相似度检验异常, articleId={}", articleId, e);
        }

        // 5. 更新文章配置（is_recommend字段）
        updateArticleConfig(articleId, !isHighSimilarity);

        // 6. 计算逐力值加成（根据AI质量评分）
        if (aiResult != null && Boolean.TRUE.equals(aiResult.get("success"))) {
            Integer qualityScore = (Integer) aiResult.get("qualityScore");
            if (qualityScore != null) {
                calculatePowerBonus(article, qualityScore);
            }
        }

        // 审核全部通过
        log.info("文章审核完成, articleId={}, isHighSimilarity={}", articleId, isHighSimilarity);

        // 7. 通过事件总线触发发布行为（等级积分、统计等）
        try {
            if (article.getAuthorId() != null && behaviorEventBus != null) {
                BehaviorContext behaviorContext = new BehaviorContext(BehaviorType.PUBLISH_ARTICLE, article.getAuthorId().intValue());
                behaviorContext.withTarget(1, article.getId())
                    .withUserInfo(article.getAuthorName(), article.getAuthorImage());
                behaviorEventBus.execute(behaviorContext);
                log.info("文章发布行为已通过事件总线处理, articleId={}, authorId={}", articleId, article.getAuthorId());
            }
        } catch (Exception e) {
            log.error("文章发布行为事件处理异常, articleId={}, 不影响审核流程", articleId, e);
        }

        // 不管是不是延迟发布，都添加到调度任务，只不过不延迟时interval：0，多经过了Task任务类流转
        articleTaskService.addArticleToTask(article.getId(), article.getPublishTime());
        return CompletableFuture.completedFuture(true);
    }

    /**
     * 更新文章配置的推荐状态
     */
    @Transactional(rollbackFor = Exception.class)
    protected void updateArticleConfig(Long articleId, boolean isRecommend) {
        try {
            QueryWrapper<ApArticleConfig> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("article_id", articleId);
            ApArticleConfig config = apArticleConfigMapper.selectOne(queryWrapper);
            if (config == null) {
                config = new ApArticleConfig(articleId);
                config.setIsRecommend(isRecommend);
                apArticleConfigMapper.insert(config);
            } else {
                config.setIsRecommend(isRecommend);
                apArticleConfigMapper.updateById(config);
            }
            log.info("更新文章推荐状态, articleId={}, isRecommend={}", articleId, isRecommend);
        } catch (Exception e) {
            log.error("更新文章配置失败, articleId={}", articleId, e);
        }
    }

    /**
     * 根据AI质量评分计算逐力值加成
     * 优秀（≥80分）+3，合格（60-79分）+1，不合格（<60分）+0
     */
    private void calculatePowerBonus(ApArticle article, Integer qualityScore) {
        try {
            int powerBonus = 0;
            if (qualityScore >= ArticleConstants.QUALITY_SCORE_EXCELLENT) {
                powerBonus = ArticleConstants.POWER_BONUS_EXCELLENT;
            } else if (qualityScore >= ArticleConstants.QUALITY_SCORE_PASS) {
                powerBonus = ArticleConstants.POWER_BONUS_PASS;
            }

            if (powerBonus > 0 && article.getAuthorId() != null) {
                Map<String, Object> powerResult = levelService.calculatePowerWithLimit(
                    article.getAuthorId(), article.getId(), "publish_article", powerBonus);

                if (powerResult != null && Boolean.TRUE.equals(powerResult.get("success"))) {
                    log.info("AI质量评分逐力值加成, articleId={}, authorId={}, qualityScore={}, powerBonus={}",
                        article.getId(), article.getAuthorId(), qualityScore, powerBonus);

                    // 质量优秀，发送首页推荐通知
                    if (qualityScore >= ArticleConstants.QUALITY_SCORE_EXCELLENT) {
                        sendQualityRecommendNotification(article, qualityScore);
                    }

                    // 检查是否达到自动推荐等级
                    Integer newLevel = (Integer) powerResult.get("newLevel");
                    if (newLevel != null && newLevel >= ArticleConstants.POWER_LEVEL_AUTO_RECOMMEND) {
                        updateArticleConfig(article.getId(), true);
                        log.info("作者逐力值达到{}级，文章自动推荐到首页, articleId={}, authorId={}, level={}",
                            ArticleConstants.POWER_LEVEL_AUTO_RECOMMEND,
                            article.getId(), article.getAuthorId(), newLevel);
                    }
                }
            }
        } catch (Exception e) {
            log.error("逐力值计算异常, articleId={}", article.getId(), e);
        }
    }

    /**
     * 从 Markdown 内容中提取图片 URL
     */
    private List<String> extractImageUrls(String content) {
        List<String> images = new ArrayList<>();
        Pattern imgPattern = Pattern.compile("!\\[.*?\\]\\((.*?)\\)");
        Matcher imgMatcher = imgPattern.matcher(content);
        while (imgMatcher.find()) {
            images.add(imgMatcher.group(1));
        }
        // 也提取 HTML img 标签
        Pattern htmlImgPattern = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']");
        Matcher htmlImgMatcher = htmlImgPattern.matcher(content);
        while (htmlImgMatcher.find()) {
            images.add(htmlImgMatcher.group(1));
        }
        return images;
    }

    /**
     * 审核图片
     */
    private boolean handleImageMsg(List<ContPic> imageUrls, ApArticle article) {
        boolean flag = true;
        if (imageUrls == null || imageUrls.isEmpty()) {
            return flag;
        }
        try {
            for (ContPic contPic : imageUrls) {
                Map map = greenImageScan.imageScan(contPic.getPicUrl());
                if (map != null) {
                    if ("high".equals(map.get("level"))) {
                        flag = false;
                        updateArticle(article, Status.FAIL.getCode(), "当前文章中的图片存在违规内容");
                        return flag;
                    }
                    if ("medium".equals(map.get("level"))) {
                        flag = false;
                        updateArticle(article, Status.FAIL.getCode(), "当前文章中的图片存在不确定内容");
                        return flag;
                    }
                }
            }
        } catch (Exception e) {
            flag = false;
            log.error("图片审核异常", e);
        }
        return flag;
    }

    /**
     * 更新文章状态
     * FAIL状态：设置reason，写审计记录，发送通知（不软删除，保留用于申诉）
     * PUBLISHED状态：仅设置status
     */
    @Transactional(rollbackFor = Exception.class)
    protected void updateArticle(ApArticle article, byte status, String reason) {
        article.setStatus(status);
        if (status == Status.FAIL.getCode()) {
            article.setReason(reason);
            // 不再设置 isDeleted=true，保留文章数据供申诉使用

            // 写入审计记录
            saveAuditRecord(article, reason);

            // 发送系统通知
            sendModerationFailNotification(article, reason);
        }
        apArticleMapper.updateById(article);
    }

    /**
     * 保存审核失败记录到审计表
     */
    @Transactional(rollbackFor = Exception.class)
    protected void saveAuditRecord(ApArticle article, String reason) {
        try {
            ApArticleAuditRecord record = new ApArticleAuditRecord();
            record.setArticleId(article.getId());
            record.setAuthorId(article.getAuthorId());
            record.setTitle(article.getTitle() != null ? article.getTitle() : "");
            record.setReason(reason != null ? reason : "");
            record.setAuditType("text"); // 默认文本审核，图片审核时可在调用方指定
            record.setStatus(ArticleConstants.AUDIT_STATUS_FAIL);
            record.setCreatedAt(java.time.LocalDateTime.now());

            // 获取文章内容
            QueryWrapper<ApArticleContent> contentQuery = new QueryWrapper<>();
            contentQuery.eq("article_id", article.getId());
            ApArticleContent articleContent = apArticleContentMapper.selectOne(contentQuery);
            record.setContent(articleContent != null ? articleContent.getContent() : "");

            apArticleAuditRecordMapper.insert(record);
            log.info("审核失败记录已写入审计表, articleId={}, reason={}", article.getId(), reason);
        } catch (Exception e) {
            log.error("写入审计记录失败, articleId={}", article.getId(), e);
        }
    }

    /**
     * 发送审核失败系统通知
     * 通知模板："你的文章《{article_title}》因违反社区规范已被删除。详细规则请见《社区规范》。文章内容: {violation_details}"
     */
    private void sendModerationFailNotification(ApArticle article, String reason) {
        try {
            if (notificationClient == null) {
                log.warn("通知服务不可用，跳过发送审核失败通知, articleId={}", article.getId());
                return;
            }

            String articleTitle = article.getTitle() != null ? article.getTitle() : "无标题";
            String violationDetails = reason != null ? reason : "违反社区规范";

            String message = String.format(
                "你的文章《%s》因违反社区规范已被删除。详细规则请见《社区规范》。文章内容: %s",
                articleTitle, violationDetails
            );

            // 构建通知内容JSON
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("articleId", String.valueOf(article.getId()));
            contentMap.put("title", articleTitle);
            contentMap.put("reason", violationDetails);
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");

            String contentJson = objectMapper.writeValueAsString(contentMap);

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("userId", article.getAuthorId());
            params.put("type", ArticleConstants.NOTIFICATION_TYPE_SYSTEM);
            params.put("sourceId", String.valueOf(article.getId()));
            params.put("content", contentJson);

            ResponseResult result = notificationClient.createNotification(params);
            if (result != null && result.getCode() == 200) {
                log.info("审核失败通知已发送, articleId={}, authorId={}", article.getId(), article.getAuthorId());
            } else {
                log.warn("审核失败通知发送失败, articleId={}, result={}", article.getId(), result);
            }
        } catch (Exception e) {
            log.error("发送审核失败通知异常, articleId={}, 不影响审核流程", article.getId(), e);
        }
    }

    /**
     * 发送质量优秀推荐通知
     */
    private void sendQualityRecommendNotification(ApArticle article, Integer qualityScore) {
        try {
            if (notificationClient == null) {
                log.warn("通知服务不可用，跳过发送质量推荐通知, articleId={}", article.getId());
                return;
            }

            if (article.getAuthorId() == null) {
                log.warn("作者ID为空，跳过发送质量推荐通知, articleId={}", article.getId());
                return;
            }

            String articleTitle = article.getTitle() != null ? article.getTitle() : "无标题";

            String message = String.format(
                "恭喜！你的文章《%s》因内容质量优秀，已被推荐至首页。",
                articleTitle
            );

            // 构建通知内容JSON
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("articleId", String.valueOf(article.getId()));
            contentMap.put("title", articleTitle);
            contentMap.put("qualityScore", qualityScore);
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");

            String contentJson = objectMapper.writeValueAsString(contentMap);

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("userId", article.getAuthorId());
            params.put("type", ArticleConstants.NOTIFICATION_TYPE_SYSTEM);
            params.put("sourceId", String.valueOf(article.getId()));
            params.put("content", contentJson);

            ResponseResult result = notificationClient.createNotification(params);
            if (result != null && result.getCode() == 200) {
                log.info("质量推荐通知已发送, articleId={}, authorId={}, qualityScore={}",
                    article.getId(), article.getAuthorId(), qualityScore);
            } else {
                log.warn("质量推荐通知发送失败, articleId={}, result={}", article.getId(), result);
            }
        } catch (Exception e) {
            log.error("发送质量推荐通知异常, articleId={}, 不影响审核流程", article.getId(), e);
        }
    }
}