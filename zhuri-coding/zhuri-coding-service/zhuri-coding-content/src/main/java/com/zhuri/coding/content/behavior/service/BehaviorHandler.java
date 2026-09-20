package com.heima.content.behavior.service;

import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;

/**
 * 行为处理器接口
 * 每种行为类型对应一个 Handler 实现
 */
public interface BehaviorHandler {

    /**
     * 获取该处理器支持的行为类型
     */
    BehaviorType getType();

    /**
     * 执行行为
     * @param context 行为上下文
     * @return 处理结果
     */
    BehaviorResult execute(BehaviorContext context);

    /**
     * 撤销行为
     * @param context 行为上下文
     * @return 处理结果
     */
    BehaviorResult rollback(BehaviorContext context);
}