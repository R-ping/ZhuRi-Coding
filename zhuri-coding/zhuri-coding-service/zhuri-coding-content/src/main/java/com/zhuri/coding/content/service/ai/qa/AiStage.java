package com.zhuri.coding.content.service.ai.qa;

/** SSE stage 事件阶段，前端只展示名称与进行态。 */
public enum AiStage {
    REWRITE,
    RECALL,
    RERANK,
    GENERATE,
    READ_ARTICLE,
    LOCATE
}
