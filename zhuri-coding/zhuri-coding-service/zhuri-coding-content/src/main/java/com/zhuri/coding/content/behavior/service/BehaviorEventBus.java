package com.heima.content.behavior.service;

import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 行为事件总线 - 统一处理所有用户行为
 *
 * 职责：
 * 1. 根据行为类型路由到对应的 Handler
 * 2. 执行后置处理器链（等级、通知、统计等）
 * 3. 统一异常处理
 */
@Slf4j
@Component
public class BehaviorEventBus {

    @Autowired
    private List<BehaviorHandler> handlerList;

    @Autowired(required = false)
    private List<BehaviorPostProcessor> postProcessorList;

    private final Map<BehaviorType, BehaviorHandler> handlerMap = new EnumMap<>(BehaviorType.class);

    private List<BehaviorPostProcessor> sortedPostProcessors;

    @PostConstruct
    public void init() {
        // 注册所有 Handler
        for (BehaviorHandler handler : handlerList) {
            handlerMap.put(handler.getType(), handler);
            log.info("BehaviorHandler registered: type={}, handler={}",
                handler.getType().getCode(), handler.getClass().getSimpleName());
        }

        // 排序后置处理器
        if (postProcessorList != null) {
            sortedPostProcessors = postProcessorList.stream()
                .sorted(Comparator.comparingInt(BehaviorPostProcessor::getOrder))
                .collect(Collectors.toList());
            for (BehaviorPostProcessor processor : sortedPostProcessors) {
                log.info("BehaviorPostProcessor registered: order={}, processor={}",
                    processor.getOrder(), processor.getClass().getSimpleName());
            }
        }
    }

    /**
     * 执行行为处理
     * @param context 行为上下文
     * @return ResponseResult 统一响应格式
     */
    public ResponseResult execute(BehaviorContext context) {
        if (context == null || context.getBehaviorType() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "行为类型不能为空");
        }

        BehaviorHandler handler = handlerMap.get(context.getBehaviorType());
        if (handler == null) {
            log.warn("No handler found for behavior type: {}", context.getBehaviorType().getCode());
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR,
                "不支持的行为类型: " + context.getBehaviorType().getCode());
        }

        try {
            // 1. 执行行为处理
            BehaviorResult result = handler.execute(context);

            if (!result.isSuccess()) {
                log.warn("Behavior execution failed: type={}, userId={}, message={}",
                    context.getBehaviorType().getCode(), context.getUserId(), result.getMessage());
                return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, result.getMessage());
            }

            // 2. 执行后置处理器链（仅新记录才触发）
            if (result.isNewRecord() && sortedPostProcessors != null) {
                executePostProcessors(context, result);
            }

            // 3. 构建返回结果
            return ResponseResult.okResult(result.getData());

        } catch (Exception e) {
            log.error("Behavior execution error: type={}, userId={}, targetId={}",
                context.getBehaviorType().getCode(), context.getUserId(), context.getTargetId(), e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "行为处理异常: " + e.getMessage());
        }
    }

    /**
     * 执行撤销行为
     */
    public ResponseResult rollback(BehaviorContext context) {
        if (context == null || context.getBehaviorType() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "行为类型不能为空");
        }

        BehaviorHandler handler = handlerMap.get(context.getBehaviorType());
        if (handler == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR,
                "不支持的行为类型: " + context.getBehaviorType().getCode());
        }

        try {
            BehaviorResult result = handler.rollback(context);
            if (!result.isSuccess()) {
                return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, result.getMessage());
            }
            return ResponseResult.okResult(result.getData());
        } catch (Exception e) {
            log.error("Behavior rollback error: type={}, userId={}, targetId={}",
                context.getBehaviorType().getCode(), context.getUserId(), context.getTargetId(), e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "撤销行为异常: " + e.getMessage());
        }
    }

    /**
     * 执行后置处理器链
     */
    private void executePostProcessors(BehaviorContext context, BehaviorResult result) {
        for (BehaviorPostProcessor processor : sortedPostProcessors) {
            try {
                processor.postProcess(context, result);
            } catch (Exception e) {
                log.error("PostProcessor error: processor={}, type={}, userId={}",
                    processor.getClass().getSimpleName(),
                    context.getBehaviorType().getCode(), context.getUserId(), e);
            }
        }
    }
}