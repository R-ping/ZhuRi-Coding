package com.heima.content.service.ai.agent;

import java.util.List;

/**
 * Agent 执行结果：最终答案（FINAL）或步骤输出。
 */
public class AgentResult {

    /** 模型最终输出（去掉 FINAL: 前缀） */
    private String finalAnswer;

    /** 消耗的工具调用步数 */
    private int steps;

    /** 是否在达到步数上限前结束 */
    private boolean completed;

    public AgentResult(String finalAnswer, int steps, boolean completed) {
        this.finalAnswer = finalAnswer;
        this.steps = steps;
        this.completed = completed;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public int getSteps() {
        return steps;
    }

    public boolean isCompleted() {
        return completed;
    }
}
