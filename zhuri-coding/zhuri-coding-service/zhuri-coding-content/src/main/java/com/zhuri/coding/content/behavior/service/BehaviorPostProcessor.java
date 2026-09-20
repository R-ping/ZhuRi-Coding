package com.heima.content.behavior.service;

import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;

/**
 * 行为后置处理器接口
 * 在行为执行完成后，按顺序执行后置处理
 */
public interface BehaviorPostProcessor {

    /**
     * 执行后置处理
     * @param context 行为上下文
     * @param result 行为执行结果
     */
    void postProcess(BehaviorContext context, BehaviorResult result);

    /**
     * 获取执行顺序（数值越小越先执行）
     */
    int getOrder();
}