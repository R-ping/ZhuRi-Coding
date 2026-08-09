package com.heima.content.service.article.impl;

import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.schedule.service.TaskService;
import com.heima.content.service.article.ArticleTaskService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.schedule.dtos.Task;
import com.heima.utils.common.ProtostuffUtil;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class ArticleTaskServiceImpl implements ArticleTaskService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Override
    @Async
    public void addArticleToTask(Long articleId, Date publishTime) {
        log.info("添加文章到延迟发布队列, articleId={}, publishTime={}", articleId, publishTime);

        Date now = new Date();
        long executeTimeInterval = publishTime.getTime() - now.getTime();

        long firstTimeInterval;
        if (executeTimeInterval <= 0) {
            executeTimeInterval = 0;
            firstTimeInterval = 0;
        } else if (executeTimeInterval <= ArticleConstants.DELAY_5_MIN_MS) {
            firstTimeInterval = 0;
        } else if (executeTimeInterval <= ArticleConstants.DELAY_15_MIN_MS) {
            firstTimeInterval = executeTimeInterval - ArticleConstants.DELAY_2_MIN_MS;
        } else {
            long delay = (long) (Math.random() * ArticleConstants.RANDOM_DELAY_RANGE_MIN + ArticleConstants.RANDOM_DELAY_BASE_MIN);
            delay = delay * 60 * 1000;
            firstTimeInterval = executeTimeInterval - delay;
        }

        Task task = new Task();
        task.setFirstExecInterval(Math.max(0, firstTimeInterval));
        task.setObjExecInterval(executeTimeInterval);
        task.setExecuteTime(publishTime);
        // 序列化 ApArticle（仅包含ID，用于调度任务反序列化）
        ApArticle apArticle = new ApArticle();
        apArticle.setId(articleId);
        task.setParameters(ProtostuffUtil.serialize(apArticle));

        taskService.addTask(task);
        log.info("文章延迟发布任务已添加, articleId={},", articleId);
    }

    @Override
    public void publishArticle(Long articleId) {
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null) {
            log.error("发布文章失败，文章不存在, articleId={}", articleId);
            return;
        }
        article.setStatus(Status.PUBLISHED.getCode());
        apArticleMapper.updateById(article);
        log.info("文章已发布, articleId={}", articleId);
    }
}