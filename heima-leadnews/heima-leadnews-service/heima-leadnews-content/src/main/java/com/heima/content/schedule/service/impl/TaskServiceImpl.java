package com.heima.content.schedule.service.impl;

import com.alibaba.fastjson.JSON;
import com.heima.common.constants.ScheduleConstants;
import com.heima.common.redis.CacheService;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.schedule.listener.RedissonDelayQueue;
import com.heima.content.schedule.mapper.TaskinfoLogsMapper;
import com.heima.content.schedule.service.TaskService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.schedule.dtos.Task;
import com.heima.model.schedule.pojos.TaskinfoLogs;
import com.heima.utils.common.ProtostuffUtil;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TaskServiceImpl implements TaskService {

    public static void main(String[] args) {
        TaskinfoLogs taskinfoLogs = new TaskinfoLogs();
        System.out.println(taskinfoLogs);
    }

    @Autowired
    CacheService cacheService;

    @Autowired
    private TaskinfoLogsMapper taskinfoLogsMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;
    @Autowired
    private RedissonDelayQueue redissonDelayQueue;

    /**
     * 添加延迟任务
     */
    @Override
    public void addTask(Task task) {
        //1.添加任务到数据库中
        boolean success = addTaskToDb(task);
        if (success) {
            // 发送延迟消息
            sendTaskDelayMsg(task);
        }
    }

    private void sendTaskDelayMsg(Task task) {
        long objExecInterval = task.getObjExecInterval();
        long delay = 0;
        if (objExecInterval <= 0) {
        } else if (objExecInterval <= 60 * 60 * 1000) {
            delay = task.getFirstExecInterval();
        }
        String taskJson = JSON.toJSONString(task);
        redissonDelayQueue.addTask("TASK_FIRST_EXECUTE_DELAY_QUEUE", taskJson, delay);
        log.info("Redisson延迟消息已发送，taskId={}, delay={}ms", task.getTaskId(), delay);
    }
    // 每过30分钟执行一次
    @Scheduled(cron = "0 0/30 * * * ?")
    public void refreshTaskToRedis() {
        // 当前时间
        long nowTime = System.currentTimeMillis();
        Date nextHour = new Date(nowTime + 60 * 60 * 1000);

        taskinfoLogsMapper.selectGoal(nextHour).forEach(taskinfoLogs -> {
            Task task = new Task();
            BeanUtils.copyProperties(taskinfoLogs, task);
            task.setExecuteTime(taskinfoLogs.getExecuteTime());
            task.setFirstExecInterval(taskinfoLogs.getFirstExecInterval());
            sendTaskDelayMsg(task);
        });
    }

    /**
     * 添加任务到数据库中
     */
    private boolean addTaskToDb(Task task) {
        boolean flag = false;
        ApArticle article = ProtostuffUtil.deserialize(task.getParameters(), ApArticle.class);
        try {
            //保存任务日志数据
            TaskinfoLogs taskinfoLogs = new TaskinfoLogs();
            BeanUtils.copyProperties(task, taskinfoLogs);
            if (task.getObjExecInterval() <= ArticleConstants.DELAY_1_HOUR_MS) {
                taskinfoLogs.setInOneHour(true);
            }
            taskinfoLogs.setStatus(ScheduleConstants.EXECUTED);
            ApArticle apArticle = apArticleMapper.selectById(article.getId());
            if (apArticle == null) {
                log.error(
                    "article is not exist可能有由于审核逻辑出问题，导致文章回滚掉了，task延迟任务也不应该继续 taskId={}, articleId={}",
                    task.getTaskId(), article.getId());
                return false;
            }
            taskinfoLogsMapper.insert(taskinfoLogs);
            //设置taskID
            task.setTaskId(taskinfoLogs.getTaskId());
            flag = true;
            log.info("task add success taskId={}", task.getTaskId());
        } catch (Exception e) {

            log.error("task延迟任务 add exception，articleId={}", article.getId(), e);
        }
        return flag;
    }

    /**
     * 更新任务日志
     */
    private void updateDb(long taskId, int status) {
        try {
            //更新任务日志
            TaskinfoLogs taskinfoLogs = taskinfoLogsMapper.selectById(taskId);
            taskinfoLogs.setStatus(status);
            taskinfoLogsMapper.updateById(taskinfoLogs);
            log.info("taskInfoLogs update success taskId={}, status={}", taskId, status);
        } catch (Exception e) {
            log.error("task cancel exception taskId={}", taskId);
        }
    }

    /**
     * 消费任务
     */
    public void consumerTask(Long taskId) {
        try {
            updateDb(taskId, ScheduleConstants.EXECUTED);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void failTask(Long taskId) {
        try {
            updateDb(taskId, ScheduleConstants.FAIL);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}