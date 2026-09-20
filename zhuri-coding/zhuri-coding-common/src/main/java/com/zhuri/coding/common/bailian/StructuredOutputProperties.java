package com.zhuri.coding.common.bailian;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 结构化输出保障配置
 *
 * 读取 app.ai.structured-* 配置项，统一控制 "调用 -> 解析 -> 修复 -> 带失败原因重试" 的兜底策略。
 * 默认值即可开箱使用；如需调整重试次数或关闭指标/修复开关，可在服务端 application.yml 覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class StructuredOutputProperties {

    /**
     * 最大尝试次数（含首次）。每次重试都是一次完整 LLM 调用，成本/延迟线性增长，默认 2
     */
    private int structuredMaxAttempts = 2;

    /**
     * 重试时是否携带上一次的失败原因，帮助模型修正输出
     */
    private boolean structuredIncludeLastError = true;

    /**
     * 重试时是否追加 "上次输出解析失败，请仅返回合法 JSON" 提示
     */
    private boolean structuredRetryUseRepairPrompt = true;

    /**
     * 重试时是否追加 STRICT_JSON_INSTRUCTION 严格 JSON 指令
     */
    private boolean structuredRetryAppendStrictJsonInstruction = true;

    /**
     * 失败原因单行化后的截断长度，防止把超长/带换行的异常灌进 prompt
     */
    private int structuredErrorMessageMaxLength = 200;

    /**
     * 是否上报 Micrometer 指标（invocation 计数 + 延迟 timer）
     */
    private boolean structuredMetricsEnabled = true;

    /**
     * 是否启用 Schema 校验分支。当前接入的 DashScope 文本接口未开启 JSON Mode，
     * 统一走本地修复路径，故默认关闭；未来接入支持原生 JSON Mode 的模型后可开启。
     */
    private boolean structuredSchemaValidationEnabled = false;
}