package com.heima.content.schedule.service;

import com.heima.model.schedule.dtos.Task;

public interface TaskService {


    /**
     * 添加延迟任务
     * @param task
     * @return
     */
    void addTask(Task task);


    /**
     * 消费任务
     */
    void consumerTask(Long taskId);
}