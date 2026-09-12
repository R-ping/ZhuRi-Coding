package com.heima.common.bailian;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DashScopeClient {

    @Autowired
    private BailianConfig bailianConfig;

    @Autowired
    private ComplianceGuard complianceGuard;

    private final int maxRetries=3;

    private final long backoffDelay=1000;

    // 被阻断时的固定回复
    private static final String BLOCKED_RESPONSE = "审核分析异常，请重新提交。";

    @PostConstruct
    public void init() {
        if (bailianConfig.getApiKey() != null && !bailianConfig.getApiKey().isEmpty()) {
            Constants.apiKey = bailianConfig.getApiKey();
            Constants.baseHttpApiUrl = bailianConfig.getApiHost();
            // 安全红线：绝不在日志中打印 apiKey 明文，仅输出掩码（保留末4位便于排查环境问题）
            log.info("DashScope API Key configured successfully, baseHttpApiUrl={}, apiKeyMasked={}",
                    bailianConfig.getApiHost(), maskKey(bailianConfig.getApiKey()));
        } else {
            log.warn("DASH_SCOPE_API_KEY environment variable is not set. AI analysis will be disabled.");
        }
    }

    /** 对密钥做脱敏处理，仅保留末 4 位，避免明文泄露 */
    private String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 调用大模型进行文本生成
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @return 模型响应文本
     */
    /** @deprecated 文本/向量调用已迁移 Spring AI（ChatModel/EmbeddingModel），本方法仅保留兼容 */
    @Deprecated
    public String callGeneration(String systemPrompt, String userMessage) {
        if (bailianConfig.getApiKey() == null || bailianConfig.getApiKey().isEmpty()) {
            log.warn("DashScope API Key not configured, skipping AI call");
            return null;
        }

        long startTime = System.currentTimeMillis();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String response = httpChat(systemPrompt, userMessage, false, null);
                
                // Layer 3: 输出护栏 —— 检查响应是否包含顺从短语
                if (complianceGuard.isComplianceResponse(response)) {
                    log.warn("Output guardrail triggered: LLM response contains compliance phrases, blocking response");
                    return BLOCKED_RESPONSE;
                }
                
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("DashScope API call succeeded, attempt={}, elapsed={}ms", attempt, elapsed);
                return response;

            } catch (Exception e) {
                lastException = e;
                log.warn("DashScope compatible chat error, attempt={}/{}, msg={}", attempt, maxRetries,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }

            if (attempt < maxRetries) {
                long delay = backoffDelay * (long) Math.pow(2, attempt - 1);
                try {
                    log.info("Retrying after {}ms...", delay);
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.error("DashScope API call failed after {} attempts, elapsed={}ms", maxRetries, elapsed, lastException);
        return null;
    }

    /**
     * qwen3.7-max、qwen3.7-max-2026-05-20 和 qwen3.6-max-preview 仅支持文本接口
     * @param systemPrompt
     * @param userMessage
     * @return
     * @throws NoApiKeyException
     * @throws InputRequiredException
     */
    @NotNull

    /**
     * qwen3.8-max、qwen3.7-max-2026-06-08、Qwen3.6 和 Qwen3.5 系列的 DashScope API均需使用多模态接口。直接运行以下示例会提示 url error 错误。
     * @param systemPrompt
     * @param userMessage
     * @return
     * @throws ApiException
     * @throws NoApiKeyException
     * @throws InputRequiredException
     */
    /** OpenAI 兼容 base（从原生 apiHost 推导：去掉尾部 /api/v1 后加 /compatible-mode/v1） */
    private String compatibleBaseUrl() {
        String host = bailianConfig.getApiHost();
        if (host == null || host.isBlank()) {
            host = "https://dashscope.aliyuncs.com/api/v1";
        }
        String base = host;
        if (base.endsWith("/api/v1")) {
            base = base.substring(0, base.length() - "/api/v1".length());
        } else if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/compatible-mode/v1";
    }

    /** OpenAI compatible /chat/completions 调用（stream=false 返回全文；stream=true 走 SSE 回调增量） */
    private String httpChat(String systemPrompt, String userMessage, boolean stream,
                            java.util.function.Consumer<String> onDelta) throws Exception {
        String url = compatibleBaseUrl() + "/chat/completions";
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", bailianConfig.getModel());
        java.util.List<java.util.Map<String, String>> msgs = new java.util.ArrayList<>();
        java.util.Map<String, String> sysMsg = new java.util.LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt == null ? "" : systemPrompt);
        java.util.Map<String, String> usrMsg = new java.util.LinkedHashMap<>();
        usrMsg.put("role", "user");
        usrMsg.put("content", userMessage == null ? "" : userMessage);
        msgs.add(sysMsg);
        msgs.add(usrMsg);
        payload.put("messages", msgs);
        payload.put("temperature", 0.3);
        payload.put("top_p", 0.8);
        java.util.List<String> mods = new java.util.ArrayList<>();
        mods.add("text");
        payload.put("modalities", mods);
        if (stream) {
            payload.put("stream", true);
        }
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .header("Authorization", "Bearer " + bailianConfig.getApiKey())
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(90))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build();
        java.net.http.HttpResponse<java.io.InputStream> resp = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String err = resp.body() == null ? "" : new String(resp.body().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            throw new IllegalStateException("HTTP " + resp.statusCode() + " - " + truncate(err, 300));
        }
        if (!stream) {
            String full = new String(resp.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return extractContent(full);
        }
        StringBuilder acc = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.startsWith("data:")) {
                    t = t.substring(5).trim();
                }
                if (t.equals("[DONE]")) {
                    break;
                }
                String delta = extractContent(t); // choices[0].delta.content 增量（含 uXXXX 十六进制反转义）
                if (delta != null && !delta.isEmpty()) {
                    acc.append(delta);
                    onDelta.accept(delta);
                }
            }
        }
        return acc.toString();
    }

    /** 从 chat.completions JSON（message 或 delta）中提取第一个 "content" 字段文本 */
    private String extractContent(String jsonLine) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"", java.util.regex.Pattern.DOTALL)
            .matcher(jsonLine);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 < raw.length()) {
                            try {
                                out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                out.append('u');
                            }
                        }
                        break;
                    default: out.append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public void MultiRoundConversationCall(String systemPrompt, String userMessage) throws ApiException, NoApiKeyException, UploadFileException {
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalMessage userMessageObj = MultiModalMessage.builder().role(Role.USER.getValue())
            .content(Arrays.asList(Collections.singletonMap("image", "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20251031/ownrof/f26d201b1e3f4e62ab4a1fc82dd5c9bb.png"),
                Collections.singletonMap("text", "请问图片展现了有哪些商品？"))).build();
        List<MultiModalMessage> messages = new ArrayList<>();
        messages.add(userMessageObj);
        MultiModalConversationParam param = MultiModalConversationParam.builder()
            .apiKey(bailianConfig.getApiKey())
            .model(bailianConfig.getModel())
            .messages(messages)
            .build();
        MultiModalConversationResult result = conv.call(param);
        System.out.println(result.getOutput().getChoices().get(0).getMessage().getContent().get(0).get("text"));        // add the result to conversation
    }
    /**
     * 流式对话（SSE）：直连 DashScope 兼容网关 /text/generation（incremental_output）。
     * 逐段回调增量文本，用于前端逐字渲染。鉴权/鉴权头按 DashScope OpenAPI 规范。
     *
     * @param systemPrompt 系统提示
     * @param userMessage  用户消息
     * @param onDelta      增量文本回调（线程内同步，勿阻塞）
     * @return true=正常完成；false=网络/解析失败
     */
    /** @deprecated 文本/向量调用已迁移 Spring AI（ChatModel/EmbeddingModel），本方法仅保留兼容 */
    @Deprecated
    public boolean streamChat(String systemPrompt, String userMessage,
                              java.util.function.Consumer<String> onDelta) {
        if (bailianConfig.getApiKey() == null || bailianConfig.getApiKey().isEmpty()) {
            log.warn("DashScope API Key not configured, skipping stream call");
            return false;
        }
        try {
            StringBuilder acc = new StringBuilder();
            String full = httpChat(systemPrompt, userMessage, true,
                delta -> {
                    acc.append(delta);
                    onDelta.accept(delta);
                });
            return full != null && !full.isEmpty();
        } catch (Exception e) {
            log.error("DashScope stream chat failed", e);
            return false;
        }
    }


    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }


    /**
     * 多模态图文理解（封面/图片审核等）。模型取 bailian.dashscope.vision-model（默认 qwen3.8-27b）。
     *
     * @param systemPrompt 系统约束（并入 user 前缀，兼容各模型对 system 的支持差异）
     * @param imageUrl     图片地址（公网可访问）
     * @param userText     针对图片的指令
     * @return 模型文本回答；失败返回 null
     */
    public String callVision(String systemPrompt, String imageUrl, String userText) {
        if (bailianConfig.getApiKey() == null || bailianConfig.getApiKey().isEmpty()) {
            log.warn("DashScope API Key not configured, skipping vision call");
            return null;
        }
        String model = bailianConfig.getVisionModel();
        if (model == null || model.isBlank()) {
            model = "qwen3.8-27b";
        }
        long startTime = System.currentTimeMillis();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                MultiModalConversation conv = new MultiModalConversation();
                List<java.util.Map<String, Object>> userContent = new ArrayList<>();
                userContent.add(Collections.singletonMap("image", imageUrl));
                userContent.add(Collections.singletonMap("text",
                    (systemPrompt == null ? "" : systemPrompt + "\n\n") + (userText == null ? "" : userText)));
                MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(userContent)
                    .build();
                List<MultiModalMessage> messages = new ArrayList<>();
                messages.add(userMsg);
                MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(bailianConfig.getApiKey())
                    .model(model)
                    .messages(messages)
                    .build();
                MultiModalConversationResult result = conv.call(param);
                List<java.util.Map<String, Object>> contents = result.getOutput().getChoices().get(0)
                    .getMessage().getContent();
                StringBuilder sb = new StringBuilder();
                for (java.util.Map<String, Object> c : contents) {
                    Object text = c.get("text");
                    if (text != null) {
                        sb.append(text);
                    }
                }
                log.info("DashScope vision call succeeded, attempt={}, elapsed={}ms", attempt,
                    System.currentTimeMillis() - startTime);
                return sb.toString();
            } catch (Exception e) {
                log.warn("DashScope vision call failed, attempt={}/{}, msg={}", attempt, maxRetries,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(backoffDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 调用文本向量模型生成向量嵌入
     * @param text 文本内容
     * @return 向量嵌入（double数组）
     */
    /**
     * 文本向量（OpenAI compatible /embeddings）。与 chat 同源，避免 SDK 静态 base 配置时机问题。
     */
    /** @deprecated 文本/向量调用已迁移 Spring AI（ChatModel/EmbeddingModel），本方法仅保留兼容 */
    @Deprecated
    public double[] callEmbedding(String text) {
        if (bailianConfig.getApiKey() == null || bailianConfig.getApiKey().isEmpty()) {
            log.warn("DashScope API Key not configured, skipping embedding call");
            return null;
        }
        long startTime = System.currentTimeMillis();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("model", bailianConfig.getEmbeddingModel());
                java.util.List<String> input = new java.util.ArrayList<>();
                input.add(text);
                payload.put("input", input);
                String url = compatibleBaseUrl() + "/embeddings";
                String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + bailianConfig.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(60))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
                java.net.http.HttpResponse<java.io.InputStream> resp = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    String err = new String(resp.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    throw new IllegalStateException("HTTP " + resp.statusCode() + " - " + truncate(err, 200));
                }
                String json = new String(resp.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.JsonNode arr = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(json).path("data").path(0).path("embedding");
                if (!arr.isArray() || arr.isEmpty()) {
                    throw new IllegalStateException("embeddings 响应异常: " + truncate(json, 150));
                }
                double[] vector = new double[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    vector[i] = arr.get(i).asDouble();
                }
                log.info("DashScope embedding call succeeded, attempt={}, dimension={}, elapsed={}ms", attempt,
                    vector.length, System.currentTimeMillis() - startTime);
                return vector;
            } catch (Exception e) {
                log.warn("DashScope embedding error, attempt={}/{}, msg={}", attempt, maxRetries,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(backoffDelay * (long) Math.pow(2, attempt - 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return null;
    }
}