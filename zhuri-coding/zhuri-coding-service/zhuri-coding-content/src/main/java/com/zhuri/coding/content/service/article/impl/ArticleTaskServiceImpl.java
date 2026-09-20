package com.zhuri.coding.content.service.article.impl;

import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.schedule.service.TaskService;
import com.zhuri.coding.content.service.article.ArticleTaskService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import com.zhuri.coding.model.schedule.dtos.Task;
import com.zhuri.coding.utils.common.ProtostuffUtil;
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
        long executeTimeInterval = Math.max(0, publishTime.getTime() - now.getTime());

        // 单延迟：任务在 publishTime 才触发（不再提前执行复杂业务 + 二次延迟置可见的"双延迟"方案）。
        // 原因：消费端动作已精简（本地消息表入库 + ES 同步 + 置发布状态），单次消费耗时可控；
        // 一次到点消费即可完成"ES 可见 + DB 可见"，无需把耗时动作提前到发布前。
        Task task = new Task();
        task.setFirstExecInterval(executeTimeInterval);
        task.setObjExecInterval(executeTimeInterval);
        task.setExecuteTime(publishTime);
        // 序列化 ApArticle（仅包含ID，用于调度任务反序列化）
        ApArticle apArticle = new ApArticle();
        apArticle.setId(articleId);
        task.setParameters(ProtostuffUtil.serialize(apArticle));

        taskService.addTask(task);
        log.info("文章延迟发布任务已添加, articleId={}, publishTime={}, delay={}ms", articleId, publishTime,
            executeTimeInterval);
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