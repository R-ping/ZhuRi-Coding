package com.zhuri.coding.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.ai.ArticleQaService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 单篇文章 AI 服务实现（Step3·①）
 *
 * <p>无向量检索：文章正文（Markdown）直接作为唯一上下文，答案只来自本文。
 * 生成链路与 AiAskServiceImpl 一致（Spring AI ChatClient + 提示词安全 Advisor + 会话记忆滑窗）。
 */
@Slf4j
@Service
public class ArticleQaServiceImpl implements ArticleQaService {

    /** 摘要 Redis 缓存前缀（TTL 24h） */
    private static final String SUMMARY_KEY_PREFIX = "ai:article:summary:";
    /** 相关问答 Redis 缓存前缀（TTL 24h，文章不变则问题稳定） */
    private static final String QUESTIONS_KEY_PREFIX = "ai:article:questions:";
    private static final long SUMMARY_TTL_HOURS = 24L;
    /** 单篇正文提供给模型的上限（字符）。Markdown 正文，长文截断控制上下文与成本 */
    private static final int CONTENT_MAX_CHARS = 12000;
    /** 会话记忆滑窗：最多注入的消息条数（约 6 轮 user/assistant） */
    private static final int MEMORY_MAX_MESSAGES = 12;
    /** 每请求独立 memory 实例 + 固定会话 key（实例随请求销毁，天然隔离） */
    private static final String MEMORY_CONVERSATION_ID = "article-qa-conversation";

    private static final String SUMMARY_SYSTEM =
        "你是文章摘要助手。阅读【文章正文】，用简体中文输出一段摘要，要求：\n"
            + "1. 第一句概括文章主题与核心结论（30 字内）；\n"
            + "2. 随后提炼 2~4 个关键要点，用“；”分隔，不编号、不换行；\n"
            + "3. 全文 80~160 字，只依据正文，禁止编造文中没有的信息；\n"
            + "4. 正文过短或内容不足时，如实写“文章内容较简略”，并给出仅有的要点。";

    /** 相关问题生成：读者读完最可能追问的 4 个问题（具体、可基于本文回答） */
    private static final String QUESTIONS_SYSTEM =
        "你是资深读者。阅读【文章正文】，提出读者读完最可能追问的 4 个问题，要求：\n"
            + "1. 每个问题 ≤30 字、足够具体（尽量带出文中关键概念），点击后能直接对本文提问并得到答案；\n"
            + "2. 覆盖不同角度：核心方案/做法细节、边界或失败场景、与读者自身情况的适配、可延伸实践；\n"
            + "3. 只依据正文提问，不要凭空发明文中不存在的主题。\n"
            + "只输出 JSON 字符串数组（如 [\"问题1\",\"问题2\",\"问题3\",\"问题4\"]），不要任何其它文字。";

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleContentMapper contentMapper;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private com.zhuri.coding.content.service.ai.AiLlmGateway llmGateway;

    /** Prompt 注册表（P2-1）：单篇问答 prompt 版本化 + 兜底；单测未注入时走代码常量 */
    @Autowired(required = false)
    private com.zhuri.coding.content.service.ai.AiPromptRegistry promptRegistry;

    /** 注册表解析（带 null 兜底） */
    private com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt prompt(
        String key, String fallback) {
        if (promptRegistry == null) {
            return new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
        try {
            return promptRegistry.resolve(key, fallback, null);
        } catch (Exception e) {
            return new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
    }

    @Override
    public java.util.List<String> genRelatedQuestions(Long articleId) {
        if (articleId == null) {
            return null;
        }
        String key = QUESTIONS_KEY_PREFIX + articleId;
        try {
            String cached = cacheService.get(key);
            java.util.List<String> hit = parseQuestionList(cached);
            if (hit != null && !hit.isEmpty()) {
                return hit;
            }
        } catch (Exception e) {
            log.warn("读取相关问题缓存失败, articleId={}", articleId, e);
        }
        String bodyText = loadArticleBody(articleId);
        if (bodyText == null) {
            return null;
        }
        try {
            String raw = genText(prompt("qa_questions", QUESTIONS_SYSTEM).content, "【文章正文】\n" + bodyText, null);
            java.util.List<String> qs = parseQuestionList(raw);
            if (qs == null || qs.isEmpty()) {
                return null;
            }
            if (qs.size() > 5) {
                qs = qs.subList(0, 5);
            }
            try {
                cacheService.set(key, objectMapper.writeValueAsString(qs));
                cacheService.expire(key, SUMMARY_TTL_HOURS, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("写入相关问题缓存失败, articleId={}", articleId, e);
            }
            return qs;
        } catch (Exception e) {
            log.error("生成相关问题异常, articleId={}", articleId, e);
            return null;
        }
    }

    /** 从模型输出/缓存中解析问题字符串数组（容错：容忍代码围栏与前后杂文本） */
    private java.util.List<String> parseQuestionList(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String s = raw.trim();
            int start = s.indexOf('[');
            int end = s.lastIndexOf(']');
            if (start >= 0 && end > start) {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(s.substring(start, end + 1));
                if (node.isArray()) {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode it : node) {
                        if (it != null && it.isTextual() && !it.asText().isBlank()) {
                            out.add(it.asText().trim());
                        }
                    }
                    return out.isEmpty() ? null : out;
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[ArticleQa] 解析问题数组失败: {}", raw == null ? "" : truncate(raw, 120));
            return null;
        }
    }

    @Override
    public String genSummary(Long articleId) {
        if (articleId == null) {
            return null;
        }
        String key = SUMMARY_KEY_PREFIX + articleId;
        try {
            String cached = cacheService.get(key);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        } catch (Exception e) {
            log.warn("读取摘要缓存失败, articleId={}", articleId, e);
        }
        // 优先复用文章元数据 summary（发布预检已回填 ap_article.summary，0 LLM 调用、实时返回）。
        // 只在未回填时兜底走 LLM 生成——避免每次页面访问都重新请求强模型（实测 40-50s 太慢且易失败）。
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article != null && StringUtils.isNotBlank(article.getSummary())) {
            String metaSummary = article.getSummary().trim();
            try {
                cacheService.set(key, metaSummary);
                cacheService.expire(key, SUMMARY_TTL_HOURS, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("写入摘要元数据缓存失败, articleId={}", articleId, e);
            }
            return metaSummary;
        }
        // 元数据无摘要时兜底 LLM 生成：摘要任务为低成本高频场景，映射到 flash 快模型（
        // ai.model-router.features.article_summary），避免强模型 40-50s 级别的同步阻塞。
        String bodyText = loadArticleBody(articleId);
        if (bodyText == null) {
            return null;
        }
        try {
            String user = "【文章正文】\n" + bodyText;
            String summary = genText(com.zhuri.coding.content.service.ai.AiFeatures.ARTICLE_SUMMARY,
                prompt("qa_summary", SUMMARY_SYSTEM).content, user, null);
            if (summary == null || summary.isBlank()) {
                return null;
            }
            String clean = summary.trim();
            if (!clean.isEmpty()) {
                try {
                    cacheService.set(key, clean);
                    cacheService.expire(key, SUMMARY_TTL_HOURS, TimeUnit.HOURS);
                } catch (Exception e) {
                    log.warn("写入摘要缓存失败, articleId={}", articleId, e);
                }
            }
            return clean;
        } catch (Exception e) {
            log.error("生成文章摘要异常, articleId={}", articleId, e);
            return null;
        }
    }

    @Override
    public String streamAskArticle(Long articleId, String question,
                                   List<Map<String, String>> history,
                                   Consumer<String> onDelta) {
        String bodyText = loadArticleBody(articleId);
        if (bodyText == null) {
            throw new IllegalArgumentException("文章不存在或未发布");
        }
        ApArticle article = apArticleMapper.selectById(articleId);
        String title = article != null && article.getTitle() != null ? article.getTitle() : "本文";
        String system = "你是《" + title + "》这篇文章的专属解读助手。请遵守：\n"
            + "1. 只能依据【文章正文】回答，禁止使用正文之外的知识或编造内容；\n"
            + "2. 正文没有的内容，明确回答“文中没有提到”，不要脑补；确需推断时说明这是基于正文的推断；\n"
            + "3. 引用正文时可用双引号摘录原文关键句；\n"
            + "4. 用简体中文、条理清晰，回答控制在 500 字以内；\n"
            + "5. 若问题里有指代（如“它/上面/那一步”），结合对话历史理解，但回答依据仍只来自本篇正文。";
        String user = "【文章正文】\n" + bodyText + "\n【问题】" + question;
        return genStream(system, user, history, onDelta);
    }

    /**
     * 读取文章正文（Markdown）并截断。
     *
     * @return 截断后的正文；文章不存在/未发布/无正文返回 null
     */
    private String loadArticleBody(Long articleId) {
        if (articleId == null) {
            return null;
        }
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null || article.getStatus() == null
            || article.getStatus() != Status.PUBLISHED.getCode()) {
            return null;
        }
        ApArticleContent content = contentMapper.selectOne(
            new LambdaQueryWrapper<ApArticleContent>().eq(ApArticleContent::getArticleId, articleId).last("LIMIT 1"));
        if (content == null || content.getContent() == null || content.getContent().isBlank()) {
            return null;
        }
        return truncate(content.getContent(), CONTENT_MAX_CHARS);
    }

    /** 同步生成统一入口（P0-2）：委托 gateway（安全 advisor + token 计量），本方法只构建记忆窗口 */
    private String genText(String systemPrompt, String user,
                           List<Map<String, String>> history) {
        return genText(com.zhuri.coding.content.service.ai.AiFeatures.ASK_ARTICLE, systemPrompt, user, history);
    }

    /** 同步生成统一入口（按 feature 指定）用于摘要等低成本场景走模型路由的 flash 映射 */
    private String genText(String feature, String systemPrompt, String user,
                           List<Map<String, String>> history) {
        return llmGateway.generateOrNull(feature,
            systemPrompt, user, buildConversationMemory(history), MEMORY_CONVERSATION_ID);
    }

    /** 流式生成统一入口（P0-2）：委托 gateway（含流式 token 计量） */
    private String genStream(String systemPrompt, String user,
                             List<Map<String, String>> history,
                             Consumer<String> onDelta) {
        return llmGateway.generateStreamOrNull(com.zhuri.coding.content.service.ai.AiFeatures.ASK_ARTICLE,
            systemPrompt, user, buildConversationMemory(history), MEMORY_CONVERSATION_ID, onDelta);
    }

    /** 把前端会话历史预载到请求级记忆窗口（滑动窗口 MEMORY_MAX_MESSAGES 条，实例随请求销毁） */
    private static MessageWindowChatMemory buildConversationMemory(List<Map<String, String>> history) {
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
            .maxMessages(MEMORY_MAX_MESSAGES)
            .build();
        List<Message> turns = new ArrayList<>();
        if (history != null && !history.isEmpty()) {
            int from = Math.max(0, history.size() - MEMORY_MAX_MESSAGES);
            for (int i = from; i < history.size(); i++) {
                Map<String, String> turn = history.get(i);
                String role = turn == null ? null : turn.get("role");
                String content = turn == null ? null : turn.get("content");
                if (content == null || content.isBlank()) {
                    continue;
                }
                if ("user".equalsIgnoreCase(role)) {
                    turns.add(new UserMessage(truncate(content, 300)));
                } else {
                    turns.add(new AssistantMessage(truncate(content, 300)));
                }
            }
        }
        memory.add(MEMORY_CONVERSATION_ID, turns);
        return memory;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
