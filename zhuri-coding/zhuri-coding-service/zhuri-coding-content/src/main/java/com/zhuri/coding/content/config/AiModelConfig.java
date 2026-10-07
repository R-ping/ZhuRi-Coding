package com.zhuri.coding.content.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 多模型注册：低成本模型（qwen3.8-flash）ChatModel Bean。
 *
 * <p>与 {@code spring-ai-starter-model-openai} 的自动配置主模型（bean 名 openAiChatModel，模型取
 * {@code spring.ai.openai.chat.options.model}）共存。本类额外注册一个显式命名 Bean，供
 * {@code AiModelRouter} 的功能级路由（ai.model-router.features）按 feature 选择：
 * 低价值高频功能（改写/精排/评论治理/AIGC 检测等）走 flash（便宜约 15 倍），高价值问答走主模型。
 *
 * <p>装配条件：仅在配置了 {@code spring.ai.openai.api-key}（百炼 Key 存在）时注册；
 * 无 Key 的环境不注册，路由器兜底首个可用模型，不因第二模型缺失而影响 AI 主链路（fail-open）。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.openai", name = "api-key")
public class AiModelConfig {

    @Bean("qwenFlashChatModel")
    public ChatModel qwenFlashChatModel(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:}") String baseUrl,
            @Value("${ai.model-router.flash-model:qwen3.8-flash}") String flashModel) {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        // maxTokens 3000 仅封顶不强制；enable_thinking=false 关闭模型默认思考——
        // 思考 token 与正文共享 max_tokens 预算，联调实测推理波动会随机挤占导致结构化 JSON
        // （模拟面试提纲）截断解析失败（rawLen=1547/1801 均未闭合），关闭后同题 token 减半且输出完整。
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(flashModel)
                .temperature(0.3)
                .maxTokens(3000)
                .extraBody(Map.of("enable_thinking", false))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(defaultOptions)
                .build();
    }

    /**
     * 面试报告专用模型：默认强模型（qwen3.8-27b）关闭思考。
     *
     * <p>主配置 max-completion-tokens=2048 与思考 token 共享预算，而报告任务（5 主题 +
     * 全量对话输入）在 reasoning-effort=high 下实测恰好吃满 2048（两次调用 completion
     * 都是 2048）× 正文为空 → 报告结构化生成失败。关闭思考后同题 20.6s、completion=1182
     * 即完整输出 5 项报告 JSON（直连 curl 验证）；maxTokens 4000 仅封顶不强制，留截断余量。
     */
    @Bean("qwenReportChatModel")
    public ChatModel qwenReportChatModel(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:qwen3.8-27b}") String reportModel) {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(reportModel)
                .temperature(0.3)
                .maxTokens(4000)
                .extraBody(Map.of("enable_thinking", false))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(defaultOptions)
                .build();
    }
}