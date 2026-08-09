package com.heima.content.service.article.impl;

import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.apis.search.ISearchClient;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ApArticleService;
import com.heima.content.service.article.ArticleFreemarkerService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.article.dtos.ArticleHomeDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.mess.ArticleVisitStreamMess;
import com.heima.model.mess.UpdateArticleMess;
import com.heima.model.search.vos.SearchArticleVo;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class ApArticleServiceImpl extends ServiceImpl<ApArticleMapper, ApArticle> implements ApArticleService {

    @Autowired
    private ApArticleMapper apArticleMapper;

    private final static short MAX_PAGE_SIZE = 50;
    @Autowired
    private ArticleFreemarkerService articleFreemarkerService;
    @Autowired
    private ApArticleEventMapper apArticleEventMapper;
    @Autowired
    private ISearchClient searchClient;

    /**
     * 加载文章列表
     *
     * @param type 1 加载更多   2 加载最新
     */
    @Override
    public ResponseResult load(ArticleHomeDto dto, Short type) {
        //1.检验参数
        //分页条数的校验
        Integer size = dto.getSize();
        if (size == null || size == 0) {
            size = 10;
        }
        //分页的值不超过50
        size = Math.min(size, MAX_PAGE_SIZE);
        dto.setSize(size);
        //校验参数  -->type
        if (!type.equals(ArticleConstants.LOADTYPE_LOAD_MORE) && !type.equals(ArticleConstants.LOADTYPE_LOAD_NEW)) {
            type = ArticleConstants.LOADTYPE_LOAD_MORE;
        }
        //频道参数校验
        if (StringUtils.isBlank(dto.getTag())) {
            dto.setTag(ArticleConstants.DEFAULT_TAG);
        }
        //时间校验
        if (dto.getMaxBehotTime() == null) {
            dto.setMaxBehotTime(new Date());
        }
        if (dto.getMinBehotTime() == null) {
            dto.setMinBehotTime(new Date());
        }
        //2.查询
        List<ApArticle> articleList = apArticleMapper.loadArticleList(dto, type);
        //3.结果返回 - null-safe 处理
        List<Map<String, Object>> resultList = articleList.stream().map(ApArticle::nullSafeToMap).collect(Collectors.toList());
        return ResponseResult.okResult(resultList);
    }

    /**
     * 根据文章id生成文章事件,后续进行mq异步处理
     */
    @Override
    public boolean generateArticleEvent(ApArticle article, Long taskId, long lastExecuteInterval) {
        //1.检查参数
        if (article == null) {
            log.error("文章保存失败，参数为空");
            return false;
        }
        if(getById(article.getId())==null){
            log.error("文章不存在，可能有由于审核逻辑出问题，导致文章回滚掉了，文章id：{}", article.getId());
            return false;
        }
        try {
            // 本地消息表入库（事务内）
            ArticleEvent event = buildArticleEvent();
            event.setArticleId(article.getId());
            SearchArticleVo searchArticleVo = new SearchArticleVo();
            searchArticleVo.setId(article.getId());
            event.setParameter(JSONUtil.toJsonStr(searchArticleVo));
            apArticleEventMapper.insertArticleEvent(event);
            log.info("文章本地消息表保存成功，文章id：{}", article.getId());
        } catch (Exception e) {
            log.error("文章保存失败", e);
            return false;
        }
        // 异步操作移到事务提交后，避免事务边界问题
        articleFreemarkerService.buildHTMLAndSend(article, "", taskId, lastExecuteInterval);
        return true;
    }


    /**
     * 构建文章事件
     */
    private static ArticleEvent buildArticleEvent() {
        ArticleEvent event = new ArticleEvent();
        event.setRetryCount((byte) 0);
        event.setCreateTime(new Date());
        event.setUpdateTime(new Date());
        return event;
    }

    @Override
    public void updateScore(ArticleVisitStreamMess message) {
        ApArticle apArticle1 = new ApArticle();
        apArticle1.setCollection(message.getCollect());
        apArticle1.setComment(message.getComment());
        apArticle1.setLikes(message.getLike());
        apArticle1.setViews(message.getView());
        int newScore = computeScore(apArticle1) * ArticleConstants.HOT_ARTICLE_SCORE_MULTIPLIER;
        ApArticle apArticle2 = getById(message.getArticleId());
        if (apArticle2 == null) {
            log.warn("updateScore: article not found, id={}", message.getArticleId());
            return;
        }
        //1.更新文章的阅读、点赞、收藏、评论的数量
        int oldScore = computeScore(apArticle2);
        updateArticle(message);
        //2.计算文章的分值并持久化
        int resultScore = newScore + oldScore;
        ApArticle updateScore = new ApArticle();
        updateScore.setId(message.getArticleId());
        updateScore.setScore(resultScore);
        updateById(updateScore);
    }

    @Override
    public void updateScoreByBehavior(Long articleId, UpdateArticleMess.UpdateArticleType type, Integer add) {
        ApArticle apArticle = getById(articleId);
        if (apArticle == null) {
            log.warn("updateScoreByBehavior: article not found, id={}", articleId);
            return;
        }
        int score = computeScore(apArticle);
        ApArticle updateScore = new ApArticle();
        updateScore.setId(articleId);
        updateScore.setScore(score);
        updateById(updateScore);
        log.info("文章:{} 热度分数更新为:{}", articleId, score);
    }

    @Override
    public List<Map<String, Object>> listByAuthorId(ArticleDto dto) {
        // 构建查询条件
        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();

        // 固定条件：作者ID（如果必填）
        wrapper.eq(dto.getAuthorId() != null, ApArticle::getAuthorId, dto.getAuthorId());

        // 可选条件：频道ID
        wrapper.eq(dto.getChannelId() != null, ApArticle::getChannelId, dto.getChannelId());

        // 可选条件：JSON 标签重叠查询（修复点：将 List 转为 JSON 字符串）
        if (dto.getTags() != null && !dto.getTags().isEmpty()) {
            String tagsJson = JSON.toJSONString(dto.getTags()); // 得到 ["44","45"]
            wrapper.apply("JSON_OVERLAPS(tags, {0})", tagsJson);
        }

        // 可选条件：删除状态
        wrapper.eq(dto.getIsDeleted() != null, ApArticle::getIsDeleted, dto.getIsDeleted());

        List<ApArticle> articles = list(wrapper);
        return articles.stream().map(ApArticle::nullSafeToMap).collect(Collectors.toList());
    }

    /**
     * 更新文章行为数量
     */
    private void updateArticle(ArticleVisitStreamMess mess) {
        ApArticle apArticle = getById(mess.getArticleId());
        apArticle.setCollection(apArticle.getCollection() == null ? 0 : apArticle.getCollection() + mess.getCollect());
        apArticle.setComment(apArticle.getComment() == null ? 0 : apArticle.getComment() + mess.getComment());
        apArticle.setLikes(apArticle.getLikes() == null ? 0 : apArticle.getLikes() + mess.getLike());
        apArticle.setViews(apArticle.getViews() == null ? 0 : apArticle.getViews() + mess.getView());
        updateById(apArticle);

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateArticleStatus(Long articleId) {
        log.info("更新文章发布状态, articleId={}", articleId);
        boolean dbSuccess = false;
        boolean esSuccess = false;

        try {
            // 1. 更新 DB 文章状态
            boolean updated = update(Wrappers.<ApArticle>lambdaUpdate()
                .eq(ApArticle::getId, articleId)
                .set(ApArticle::getStatus, Status.PUBLISHED.getCode()));
            if (updated) {
                dbSuccess = true;
                log.info("DB文章状态更新成功, articleId={}", articleId);
            } else {
                log.warn("DB文章状态更新可能未生效, articleId={}", articleId);
            }
        } catch (Exception e) {
            log.error("DB文章状态更新失败, articleId={}", articleId, e);
        }

        try {
            // 2. Feign 调用 ES 更新状态
            ResponseResult result = searchClient.updateArticleStatus(articleId);
            if (result != null && result.getCode() == 200) {
                esSuccess = true;
                log.info("ES文章状态更新成功, articleId={}", articleId);
            } else {
                log.warn("ES文章状态更新返回异常, articleId={}, result={}", articleId,
                    result != null ? result.getCode() : "null");
            }
        } catch (Exception e) {
            log.error("ES文章状态更新Feign调用失败, articleId={}", articleId, e);
        }

        // 3. 更新本地消息表 pub_status
        try {
            ArticleEvent event = apArticleEventMapper.selectOne(
                Wrappers.<ArticleEvent>lambdaQuery().eq(ArticleEvent::getArticleId, articleId));
            if (event != null) {
                if (dbSuccess && esSuccess) {
                    event.setPubStatus((byte) 2); // 成功
                } else {
                    event.setPubStatus((byte) 1); // 待重试
                    event.setRetryTime(new Date(System.currentTimeMillis() + ArticleConstants.RETRY_INTERVAL_MS));
                }
                event.setUpdateTime(new Date());
                apArticleEventMapper.updateById(event);
                log.info("本地消息表pub_status更新成功, articleId={}, status={}",
                    articleId, dbSuccess && esSuccess ? 2 : 1);
            } else {
                log.warn("未找到本地消息表记录, articleId={}", articleId);
            }
        } catch (Exception e) {
            log.error("更新本地消息表pub_status失败, articleId={}", articleId, e);
        }
    }

    /**
     * 计算文章的具体分值
     */
    private Integer computeScore(ApArticle apArticle) {
        int score = 0;
        if (apArticle.getLikes() != null) {
            score += apArticle.getLikes() * ArticleConstants.HOT_ARTICLE_LIKE_WEIGHT;
        }
        if (apArticle.getViews() != null) {
            score += apArticle.getViews();
        }
        if (apArticle.getComment() != null) {
            score += apArticle.getComment() * ArticleConstants.HOT_ARTICLE_COMMENT_WEIGHT;
        }
        if (apArticle.getCollection() != null) {
            score += apArticle.getCollection() * ArticleConstants.HOT_ARTICLE_COLLECTION_WEIGHT;
        }

        return score;
    }
}