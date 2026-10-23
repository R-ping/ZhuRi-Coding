package com.zhuri.coding.content.service.ai.qa;

/**
 * 问答流式输出回调。
 *
 * <p>服务层只负责产生阶段与文本增量，SSE 事件拼装、取消与错误收尾由控制器侧实现。
 */
public interface AiStreamSink {

    /** 阶段事件（可忽略）。 */
    default void stage(AiStage stage, AiStageStatus status) {
    }

    /** 文本增量。 */
    void delta(String text);
}
