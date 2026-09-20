package com.zhuri.coding.content.service.ai;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 问答意图路由（Routing 落地：闲聊 / 技术问答 二分类，挂在 AiAskController 非流式入口）。
 *
 * <p>决策语义（启发式，快速且零模型成本；可配词表，绝不让闲谈烧 RAG 配额）：
 * <ol>
 *   <li>命中<b>技术关键词</b>（Redis/Spring/报错/如何…）→ {@code TECH}（知识问答优先保真）；</li>
 *   <li>否则命中<b>闲聊词</b>且句子较短（≤20 字）→ {@code CHAT}（寒暄/问候/致谢等）；</li>
 *   <li>其余一律 {@code TECH}（默认技术向，不误伤知识问答）。</li>
 * </ol>
 *
 * <p>配置（application.yml）：{@code ai.router.chat.enabled}（默认 true）、{@code ai.router.chat.words}、
 * {@code ai.router.chat.tech-words}（逗号分隔；留空用代码默认词表）。
 */
@Slf4j
@Component
public class AskQueryRouter {

    public enum Intent {
        /** 寒暄/闲聊：直接给引导话术，不消耗配额与 RAG */
        CHAT,
        /** 技术问答：走完整知识问答链路 */
        TECH
    }

    /** 中文问句较短才判闲聊的阈值（避免把长句技术讨论误判为寒暄） */
    private static final int CHAT_MAX_LEN = 20;

    private static final List<String> DEFAULT_CHAT_WORDS = Arrays.asList(
        "你好", "您好", "在吗", "在么", "谢谢", "感谢", "辛苦了", "再见", "拜拜",
        "你是谁", "你是啥", "你叫什么", "介绍一下你自己", "能聊天吗", "聊聊天", "哈哈", "嘿嘿", "嗯嗯", "好吧", "早安", "晚安");

    private static final List<String> DEFAULT_TECH_WORDS = Arrays.asList(
        "Redis", "Java", "Spring", "MySQL", "数据库", "算法", "架构", "面试", "报错", "异常",
        "代码", "编程", "部署", "Linux", "Docker", "Kubernetes", "k8s", "前端", "后端", "原理",
        "优化", "为什么", "怎么做", "怎么办", "怎么解决", "如何", "怎么", "是什么", "讲解", "教程", "学习");

    @Value("${ai.router.chat.enabled:true}")
    private boolean enabled;

    @Value("${ai.router.chat.words:}")
    private String chatWordsCfg;

    @Value("${ai.router.chat.tech-words:}")
    private String techWordsCfg;

    /** 意图判定（null/空白按 TECH 处理，保证知识问答不被误伤） */
    public Intent intent(String question) {
        if (!enabled || question == null) {
            return Intent.TECH;
        }
        String q = question.trim();
        if (q.isEmpty()) {
            return Intent.TECH;
        }
        List<String> techWords = split(techWordsCfg, DEFAULT_TECH_WORDS);
        for (String w : techWords) {
            if (q.contains(w)) {
                return Intent.TECH;
            }
        }
        List<String> chatWords = split(chatWordsCfg, DEFAULT_CHAT_WORDS);
        for (String w : chatWords) {
            if (q.contains(w)) {
                return q.length() <= CHAT_MAX_LEN ? Intent.CHAT : Intent.TECH;
            }
        }
        return Intent.TECH;
    }

    private static List<String> split(String cfg, List<String> defaults) {
        if (cfg == null || cfg.isBlank()) {
            return defaults;
        }
        return Arrays.stream(cfg.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toList());
    }
}