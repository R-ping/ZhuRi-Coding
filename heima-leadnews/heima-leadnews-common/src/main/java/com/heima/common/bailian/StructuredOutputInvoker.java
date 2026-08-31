package com.heima.common.bailian;

import com.alibaba.fastjson.JSON;
import com.heima.model.common.enums.AppHttpCodeEnum;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LLM 结构化输出统一调用器（核心组件）
 *
 * 收敛 "调用 -> 清洗 -> 触发式修复 -> 解析 -> 带失败原因重试" 的完整流程，所有业务方 LLM
 * 结构化输出统一走 {@link #invoke(String, String, Class, AppHttpCodeEnum, String, String, Logger)}，
 * 禁止各自复制粘贴解析/修复/重试逻辑。
 *
 * 三重保障：
 * 1. 解析兜底：清洗 Markdown 代码块 + 修复字符串内的未转义引号，让模型输出可被 Java 类型直接反序列化。
 * 2. 重试增强：解析失败后携带 "上次失败原因 + 严格 JSON 指令" 重新调用模型，而非盲目重试。
 * 3. 统一入口：失败统一包装为 {@link StructuredOutputException} 抛出，不回传裸异常。
 *
 * 说明：当前接入的 DashScope 文本接口未开启原生 JSON Mode，故默认走本地修复路径；
 * 未来接入支持 JSON Mode 的模型后，可复用重试层并开启 Schema 校验分支。
 */
@Component
public class StructuredOutputInvoker {

    /**
     * 严格 JSON 指令：重试时追加到 system prompt，约束模型输出合法、可直接解析的 JSON
     */
    private static final String STRICT_JSON_INSTRUCTION =
        "请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：\n" +
        "1) 不要输出 Markdown 代码块（如 ```json）。\n" +
        "2) 不要输出任何解释文字、前后缀、注释。\n" +
        "3) 所有字符串内引号必须正确转义。";

    private final DashScopeClient dashScopeClient;
    private final StructuredOutputProperties properties;
    private final MeterRegistry meterRegistry;

    public StructuredOutputInvoker(DashScopeClient dashScopeClient,
                                   StructuredOutputProperties properties,
                                   @Autowired(required = false) MeterRegistry meterRegistry) {
        this.dashScopeClient = dashScopeClient;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 统一结构化输出入口：调用模型 -> 解析为强类型 DTO，失败时带原因重试。
     *
     * @param systemPrompt 系统提示词（含字段约束描述，末尾会自动追加防注入指令）
     * @param userPrompt   用户提示词（一般为模板渲染后的业务数据）
     * @param dtoClass     目标 DTO 类型，字段需与模型输出的 JSON key 对应
     * @param errorCode    最终失败时抛业务异常的错误码
     * @param errorPrefix  异常信息前缀，如 "AI综合审核失败："
     * @param logContext   日志/指标上下文标签，如 "综合审核"
     * @param log          调用方 SLF4J Logger，用于输出分级日志
     * @return 已解析的结构化 DTO
     * @throws StructuredOutputException 达到最大重试次数仍无法解析时抛出
     */
    public <T> T invoke(String systemPrompt,
                        String userPrompt,
                        Class<T> dtoClass,
                        AppHttpCodeEnum errorCode,
                        String errorPrefix,
                        String logContext,
                        Logger log) {
        String securedSystemPrompt = systemPrompt.trim() + "\n\n" + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        long start = System.currentTimeMillis();
        Throwable lastError = null;
        int maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptSystemPrompt = (attempt == 1)
                ? securedSystemPrompt
                : buildRetrySystemPrompt(securedSystemPrompt, lastError);
            try {
                String content = dashScopeClient.callGeneration(attemptSystemPrompt, userPrompt);
                T result = convertRawToDto(content, dtoClass, logContext, log);
                recordSuccess(logContext, start);
                if (log.isInfoEnabled()) {
                    log.info("[{}] 结构化输出成功, attempt={}", logContext, attempt);
                }
                return result;
            } catch (Throwable e) {
                lastError = e;
                recordFailure(logContext);
                if (log.isWarnEnabled()) {
                    log.warn("[{}] 结构化输出第 {}/{} 次解析失败: {}",
                        logContext, attempt, maxAttempts, summarize(e));
                }
            }
        }

        String message = errorPrefix + (lastError != null ? lastError.getMessage() : "未知错误");
        log.error("[{}] 结构化输出失败, 已达最大重试次数 {}", logContext, maxAttempts, lastError);
        throw new StructuredOutputException(errorCode, message, lastError);
    }

    /**
     * 原生 Dart/JSON 解析前的清洗 + 修复 + 解析
     *
     * 先清洗 Markdown 代码块，直接解析；失败则触发式修复未转义引号后再解析一次。
     */
    private <T> T convertRawToDto(String content, Class<T> dtoClass, String logContext, Logger log) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("LLM 返回为空，无法解析为结构化输出");
        }
        String cleaned = stripMarkdownFences(content);
        try {
            return JSON.parseObject(cleaned, dtoClass);
        } catch (RuntimeException firstError) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] 首次解析失败，尝试触发式修复未转义引号", logContext);
            }
            String repaired = repairUnescapedQuotesInJsonStrings(cleaned);
            if (repaired != cleaned) {
                try {
                    return JSON.parseObject(repaired, dtoClass);
                } catch (RuntimeException repairError) {
                    // 修复也失败则保留原始异常，并通过 addSuppressed 保留修复异常，不吞错
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    /**
     * 清洗响应中的 Markdown 代码块标记与多余空白
     */
    private String stripMarkdownFences(String content) {
        String cleaned = content.trim();
        if (cleaned.startsWith("```json") || cleaned.startsWith("```JSON")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    /**
     * 触发式修复：字符串内部未转义的引号，单遍字符扫描。
     *
     * 启发式算法，非 JSON 解析器：字符串内部遇引号时，若其后（跳过空白）是
     * ',  }  ]  :' 之一则视为字符串结束符，否则当作普通文本转义为 \"。
     * 若修复无任何改变（repaired != content 判定由调用方判断），则不触发二次解析。
     */
    private String repairUnescapedQuotesInJsonStrings(String content) {
        StringBuilder sb = new StringBuilder(content.length());
        boolean inString = false;
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (!inString) {
                sb.append(c);
                if (c == '"') {
                    inString = true;
                }
                i++;
            } else {
                // 转义态：\ 与下一字符原样透传
                if (c == '\\') {
                    sb.append(c);
                    if (i + 1 < n) {
                        sb.append(content.charAt(i + 1));
                        i += 2;
                    } else {
                        i++;
                    }
                    continue;
                }
                if (c == '"') {
                    if (isLikelyJsonStringTerminator(content, i + 1)) {
                        sb.append(c);
                        inString = false;
                        i++;
                    } else {
                        // 当作普通文本，转义为 \"
                        sb.append("\\\"");
                        i++;
                    }
                    continue;
                }
                sb.append(c);
                i++;
            }
        }
        // 字符串未闭合（扫描中断），说明启发式不可靠，返回原始内容交由调用方判定无修复
        if (inString) {
            return content;
        }
        return sb.toString();
    }

    /**
     * 判断字符串内引号是否为合法 JSON 字符串结束符：
     * 向后扫描跳过空白，返回首个非空白字符是否为 ',  }  ]  :'。
     */
    private boolean isLikelyJsonStringTerminator(String content, int from) {
        int i = from;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
                continue;
            }
            return c == ',' || c == '}' || c == ']' || c == ':';
        }
        return true; // 末尾，视为字符串结束
    }

    /**
     * 构造重试用 system prompt：在原始（已加固）system prompt 基础上，按配置追加
     * 严格 JSON 指令、修复提示与上一次失败原因。
     */
    private String buildRetrySystemPrompt(String securedSystemPrompt, Throwable lastError) {
        StringBuilder sb = new StringBuilder(securedSystemPrompt);
        sb.append("\n\n");
        if (properties.isStructuredRetryAppendStrictJsonInstruction()) {
            sb.append(STRICT_JSON_INSTRUCTION).append("\n");
        }
        if (properties.isStructuredRetryUseRepairPrompt()) {
            sb.append("上次输出解析失败，请仅返回合法 JSON。").append("\n");
        }
        if (properties.isStructuredIncludeLastError() && lastError != null) {
            sb.append("上次失败原因：").append(summarize(lastError));
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 将异常信息单行化并截断，防止把超长/带换行的异常灌进 prompt
     */
    private String summarize(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            msg = e.getClass().getSimpleName();
        }
        msg = msg.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        int maxLength = properties.getStructuredErrorMessageMaxLength();
        if (maxLength > 0 && msg.length() > maxLength) {
            msg = msg.substring(0, maxLength) + "...";
        }
        return msg;
    }

    /** 记录成功指标（计数 + 延迟），受 structured-metrics-enabled 开关控制 */
    private void recordSuccess(String logContext, long start) {
        if (!properties.isStructuredMetricsEnabled() || meterRegistry == null) {
            return;
        }
        String tag = normalizeTag(logContext);
        meterRegistry.counter("ai.structured.invocation", "context", tag, "result", "success").increment();
        meterRegistry.timer("ai.structured.invocation.time", "context", tag, "result", "success")
            .record(Duration.ofMillis(System.currentTimeMillis() - start));
    }

    /** 记录失败指标，受 structured-metrics-enabled 开关控制 */
    private void recordFailure(String logContext) {
        if (!properties.isStructuredMetricsEnabled() || meterRegistry == null) {
            return;
        }
        meterRegistry.counter("ai.structured.invocation", "context", normalizeTag(logContext), "result", "failure")
            .increment();
    }

    /** 将中文/空白标签归一化为合法的 Prometheus tag 值 */
    private String normalizeTag(String logContext) {
        return logContext == null ? "unknown"
            : logContext.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }
}