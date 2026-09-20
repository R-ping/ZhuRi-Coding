package com.zhuri.coding.content.service.ai.agent;

import java.util.Map;

/**
 * Agent 可调用工具（Function Calling / Tool Use 抽象）。
 *
 * <p>工具 = 本地确定性能力（查库/规则引擎），由 LLM 在 ReAct 循环中自主决定何时调用；
 * 入参/返回均为 JSON 字符串，便于与模型文本协议交互。
 */
public interface AgentTool {

    /** 工具名（模型调用时使用，如 search_similar_article） */
    String name();

    /** 工具说明（注入 system prompt，指导模型何时调用与传参） */
    String description();

    /**
     * 执行工具。
     *
     * @param args 模型给出的 JSON 参数
     * @return 工具结果（JSON 字符串）
     */
    String execute(String args);
}
