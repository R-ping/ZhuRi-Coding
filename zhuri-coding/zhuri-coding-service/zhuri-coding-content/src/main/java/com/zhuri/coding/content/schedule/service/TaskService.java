package com.zhuri.coding.content.schedule.service;

import com.zhuri.coding.model.schedule.dtos.Task;

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