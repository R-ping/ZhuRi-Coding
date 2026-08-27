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
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DashScopeClient {

    @Autowired
    private BailianConfig bailianConfig;

    private final int maxRetries=3;

    private final long backoffDelay=1000;

    // ==================== Layer 3: 输出护栏 - 顺从短语检测 ====================
    // 检查 LLM 响应中是否包含"顺从短语"——模型已遵从注入指令的迹象
    // 匹配到则阻断响应，返回空值

    // 中文顺从短语
    private static final Pattern COMPLIANCE_PHRASE_CN = Pattern.compile(
        "(好的|可以|没问题|明白|收到|理解)[，,。.]?(我)?(已经|将|会|正在)?(忽略|忘记|无视|跳过|遵守|执行)(之前的|上面的|所有的)?(指令|指示|要求|规则|设定|命令)" +
        "|(我(现在|将|会)(成为|作为|扮演)(一个|一名|一位)?(翻译|助手|自由|不同))" +
        "|(新的角色|已切换角色|角色已变更)",
        Pattern.CASE_INSENSITIVE
    );

    // 英文顺从短语
    private static final Pattern COMPLIANCE_PHRASE_EN = Pattern.compile(
        "(i('ll| will| have| am)( now)? (act as|become|behave as|serve as|work as)( a| an| the)?)" +
        "|(sure, (i will|i'll|let me) (ignore|forget|disregard))" +
        "|(forget all previous instructions|ignoring previous instructions|override all instructions)" +
        "|(i('ll| will) (switch|change|shift) (my role|to a))",
        Pattern.CASE_INSENSITIVE
    );

    // 被阻断时的固定回复
    private static final String BLOCKED_RESPONSE = "审核分析异常，请重新提交。";

    @PostConstruct
    public void init() {
        if (bailianConfig.getApiKey() != null && !bailianConfig.getApiKey().isEmpty()) {
            Constants.apiKey = bailianConfig.getApiKey();
            Constants.baseHttpApiUrl = bailianConfig.getApiHost();
            log.info("DashScope API Key configured successfully, baseHttpApiUrl={}", bailianConfig.getApiHost());
            log.info("DashScope API Key configured successfully, apiKey={}", bailianConfig.getApiKey());
        } else {
            log.warn("DASH_SCOPE_API_KEY environment variable is not set. AI analysis will be disabled.");
        }
    }

    /**
     * 调用大模型进行文本生成
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @return 模型响应文本
     */
    public String callGeneration(String systemPrompt, String userMessage) {
        if (bailianConfig.getApiKey() == null || bailianConfig.getApiKey().isEmpty()) {
            log.warn("DashScope API Key not configured, skipping AI call");
            return null;
        }

        long startTime = System.currentTimeMillis();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                GenerationResult result = callTextGenerationResult(systemPrompt, userMessage);
                String response = result.getOutput().getChoices().get(0).getMessage().getContent();
                
                // Layer 3: 输出护栏 —— 检查响应是否包含顺从短语
                if (isComplianceResponse(response)) {
                    log.warn("Output guardrail triggered: LLM response contains compliance phrases, blocking response");
                    return BLOCKED_RESPONSE;
                }
                
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("DashScope API call succeeded, attempt={}, elapsed={}ms", attempt, elapsed);
                return response;

            } catch (ApiException e) {
                lastException = e;
                String errorMsg = e.getMessage();
                log.warn("DashScope API error, attempt={}/{}, message={}", attempt, maxRetries, errorMsg);
                // 对于网络错误和服务端错误，继续重试
                if (errorMsg != null && (errorMsg.contains("429") || errorMsg.contains("limit"))) {
                    log.warn("DashScope API rate limited, will retry");
                }
            } catch (NoApiKeyException | InputRequiredException e) {
                log.error("DashScope API configuration error: {}", e.getMessage());
                break;
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
    private GenerationResult callTextGenerationResult(String systemPrompt, String userMessage)
        throws NoApiKeyException, InputRequiredException {
        Generation gen = new Generation();

        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content(systemPrompt)
                .build();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(userMessage)
                .build();

        GenerationParam param = GenerationParam.builder()
                .apiKey(bailianConfig.getApiKey())
                .model(bailianConfig.getModel())
                .messages(Arrays.asList(systemMsg, userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .topP(0.8)
                .temperature(0.3f)
                .build();

        GenerationResult result = gen.call(param);
        return result;
    }

    /**
     * qwen3.8-max、qwen3.7-max-2026-06-08、Qwen3.6 和 Qwen3.5 系列的 DashScope API均需使用多模态接口。直接运行以下示例会提示 url error 错误。
     * @param systemPrompt
     * @param userMessage
     * @return
     * @throws ApiException
     * @throws NoApiKeyException
     * @throws InputRequiredException
     */
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
     * 调用文本向量模型生成向量嵌入
     * @param text 文本内容
     * @return 向量嵌入（double数组）
     */
    public double[] callEmbedding(String text) {
        if (bailianConfig.getApiKey() == null || bailianConfig.getApiKey().isEmpty()) {
            log.warn("DashScope API Key not configured, skipping embedding call");
            return null;
        }

        long startTime = System.currentTimeMillis();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                TextEmbedding embedding = new TextEmbedding();
                
                TextEmbeddingParam param = TextEmbeddingParam.builder()
                        .apiKey(bailianConfig.getApiKey())
                        .model(bailianConfig.getEmbeddingModel())
                        .texts(Collections.singletonList(text))
                        .build();

                TextEmbeddingResult result = embedding.call(param);
                List<Double> embeddingList = result.getOutput().getEmbeddings().get(0).getEmbedding();
                double[] vector = embeddingList.stream().mapToDouble(Double::doubleValue).toArray();
                
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("DashScope embedding call succeeded, attempt={}, dimension={}, elapsed={}ms", 
                        attempt, vector.length, elapsed);
                return vector;

            } catch (Exception e) {
                log.warn("DashScope embedding API error, attempt={}/{}, message={}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    long delay = backoffDelay * (long) Math.pow(2, attempt - 1);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.error("DashScope embedding call failed after {} attempts, elapsed={}ms", maxRetries, elapsed);
        return null;
    }

    // ==================== Layer 3: 输出护栏 ====================

    /**
     * 检查 LLM 响应是否包含"顺从短语"——模型已遵从注入指令的迹象
     *
     * 匹配中英文顺从短语，命中则说明模型可能已被注入攻击成功。
     * 这是三层防御的最后一道兜底，捕获的是"模型已经妥协并明确说出来"的情况。
     *
     * @param response LLM 响应文本
     * @return true 如果响应包含顺从短语
     */
    private boolean isComplianceResponse(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        String substring = response.substring(0, Math.min(100, response.length()));
        if (COMPLIANCE_PHRASE_CN.matcher(response).find()) {
            log.warn("Compliance phrase detected (CN) in response: {}",
                substring);
            return true;
        }
        if (COMPLIANCE_PHRASE_EN.matcher(response).find()) {
            log.warn("Compliance phrase detected (EN) in response: {}",
                substring);
            return true;
        }
        return false;
    }
}