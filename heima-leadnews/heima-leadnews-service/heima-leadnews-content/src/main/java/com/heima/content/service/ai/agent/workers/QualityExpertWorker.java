package com.heima.content.service.ai.agent.workers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 质量评审专家 Worker（多智能体发布助手 · Worker 之一）
 *
 * <p>职责：从原创性、逻辑性、表达清晰度、信息密度综合评分（0-100），
 * 并给出 2~4 条可执行的针对性建议。
 *
 * <p>输出 JSON（严格）：{"quality_score":0,"is_tech":true,"suggestions":["建议1",...]}
 */
@Component
public class QualityExpertWorker extends ExpertWorkerBase {

    public QualityExpertWorker(@Qualifier("aiExpertChatClient") ChatClient chatClient) {
        super(chatClient);
    }

    private static final String SYSTEM_PROMPT =
        "你是内容社区《逐日 Coding》的资深质量评审专家。作者提交文章发布前的质量评审任务。\n\n" +
        "请从以下维度综合评分并给出改进建议，仅输出一个 JSON 对象（不要多余文字/markdown）：\n" +
        "{\"quality_score\":0,\"is_tech\":true,\"suggestions\":[\"建议1\",\"建议2\"]}\n\n" +
        "规则：\n" +
        "1. quality_score 0-100，从原创性、逻辑结构、表达清晰度、信息密度四个维度综合评定；\n" +
        "2. is_tech：是否属于技术类内容（编程/架构/工具/运维等）为 true，生活/情感/新闻等为 false；\n" +
        "3. suggestions：2~4 条可执行的建议，必须针对本文具体问题（如结构、示例、深度、表达），禁止空话套话；\n" +
        "4. 严打标题党/水文：信息密度低、结论无依据时评分需明显压低并在建议中指出。";

    @Tool(name = "expert_quality",
          description = "质量评审专家：从原创性/逻辑/表达/信息密度对文章评分(0-100)并给改进建议，返回 {\"quality_score\":0,\"is_tech\":true,\"suggestions\":[...]}")
    public String review(
            @ToolParam(description = "文章标题") String title,
            @ToolParam(description = "文章正文内容") String content) {
        String user = "标题：" + title + "\n\n正文：\n" + content + "\n\n请完成质量评审，仅输出 JSON。";
        return askExpert(SYSTEM_PROMPT, user);
    }
}