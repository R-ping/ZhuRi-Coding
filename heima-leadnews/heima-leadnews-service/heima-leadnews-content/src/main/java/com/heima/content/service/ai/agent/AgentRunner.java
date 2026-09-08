package com.heima.content.service.ai.agent;

import com.heima.common.bailian.DashScopeClient;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自研 ReAct Agent 编排器（模型无关的文本协议循环）。
 *
 * <p>协议：模型在回答中输出
 * <pre>
 * ACTION: &lt;toolName&gt;
 * ARGS: &lt;JSON 参数&gt;
 * </pre>
 * 表示要调用工具；或输出
 * <pre>
 * FINAL: &lt;JSON 最终答案&gt;
 * </pre>
 * 表示完成。编排器解析 → 执行工具 → 将结果回合制回填上下文 → 再次询问，直到 FINAL 或达到步数上限。
 *
 * <p>选择理由：目标模型经由 DashScope 专属网关暴露，原生 function-calling 协议兼容不可控；
 * 文本协议与模型无关、执行侧完全可控，工具真实副作用与原生 Tool Use 等价。
 */
@Slf4j
@Component
public class AgentRunner {

    private static final Pattern ACTION_PATTERN = Pattern.compile("ACTION:\\s*([A-Za-z0-9_]+)");
    private static final Pattern ARGS_PATTERN = Pattern.compile("ARGS:\\s*(\\{.*\\})", Pattern.DOTALL);
    private static final Pattern FINAL_PATTERN = Pattern.compile("FINAL:\\s*(\\{.*\\})", Pattern.DOTALL);

    private final DashScopeClient dashScopeClient;

    public AgentRunner(DashScopeClient dashScopeClient) {
        this.dashScopeClient = dashScopeClient;
    }

    /**
     * 运行 Agent。
     *
     * @param systemPrompt system（应包含工具清单与格式约束）
     * @param userInput    用户任务
     * @param tools        可用工具
     * @param maxSteps     最大工具调用步数（护栏，防失控）
     * @return 结果；循环异常/超步时 completed=false（调用方应降级）
     */
    public AgentResult run(String systemPrompt, String userInput, List<AgentTool> tools, int maxSteps) {
        StringBuilder history = new StringBuilder();
        int steps = 0;
        for (int i = 0; i <= maxSteps; i++) {
            // 拼接上下文：原任务 + 历史（assistant 的 ACTION 与工具结果）
            String prompt = userInput + "\n" + history;
            String response = safeCall(systemPrompt, prompt);
            if (response == null) {
                return new AgentResult(null, steps, false);
            }
            String trimmed = response.trim();

            Matcher fm = FINAL_PATTERN.matcher(trimmed);
            if (fm.find()) {
                return new AgentResult(fm.group(1).trim(), steps, true);
            }

            Matcher am = ACTION_PATTERN.matcher(trimmed);
            if (!am.find()) {
                history.append("assistant: ").append(trim(response, 300)).append("\n");
                history.append("提示：请按要求输出 ACTION 或 FINAL。\n");
                continue;
            }
            String toolName = am.group(1);
            Matcher argsM = ARGS_PATTERN.matcher(trimmed);
            String args = argsM.find() ? argsM.group(1) : "{}";

            AgentTool tool = tools.stream().filter(t -> t.name().equalsIgnoreCase(toolName)).findFirst().orElse(null);
            String resultText;
            if (tool == null) {
                resultText = "{\"error\":\"未知工具 " + toolName + "\"}";
            } else {
                try {
                    resultText = tool.execute(args);
                    steps++;
                } catch (Exception e) {
                    log.warn("[AgentRunner] 工具执行异常 tool={}", toolName, e);
                    resultText = "{\"error\":\"" + (e.getMessage() == null ? "执行异常" : e.getMessage()) + "\"}";
                }
            }
            history.append("assistant: ACTION ").append(toolName).append("\n");
            history.append("tool_result: ").append(trim(resultText, 800)).append("\n");
        }
        return new AgentResult(null, steps, false);
    }

    private String safeCall(String system, String user) {
        try {
            return dashScopeClient.callGeneration(system, user);
        } catch (Exception e) {
            log.error("[AgentRunner] 模型调用失败", e);
            return null;
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
