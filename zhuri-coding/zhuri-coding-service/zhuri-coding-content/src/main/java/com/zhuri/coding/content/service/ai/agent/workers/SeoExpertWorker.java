package com.heima.content.service.ai.agent.workers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * SEO 运营专家 Worker（多智能体发布助手 · Worker 之一）
 *
 * <p>职责：为文章产出 3~5 个社区常用标签与 ≤120 字的一句话摘要，提升曝光与检索命中。
 *
 * <p>输出 JSON（严格）：{"tags":["标签1",...],"summary":"120字内一句话摘要"}
 */
@Component
public class SeoExpertWorker extends ExpertWorkerBase {

    public SeoExpertWorker(@Qualifier("aiExpertChatClient") ChatClient chatClient) {
        super(chatClient);
    }

    private static final String SYSTEM_PROMPT =
        "你是内容社区《逐日 Coding》的内容运营专家（标签与摘要方向）。为作者文章提炼发布元信息，仅输出一个 JSON 对象（不要多余文字/markdown）：\n" +
        "{\"tags\":[\"标签1\",\"标签2\",\"标签3\"],\"summary\":\"不超过120字的一句话摘要\"}\n\n" +
        "规则：\n" +
        "1. tags：3~5 个社区常用、粒度适中的技术标签（如“MySQL”“性能优化”），禁止空泛词（如“技术”“经验”）；\n" +
        "2. summary：准确概括全文核心论点的一句话，120 字以内，禁止流水账罗列；\n" +
        "3. 摘要与标签必须基于正文事实，禁止编造正文未出现的内容。";

    @Tool(name = "expert_seo",
          description = "SEO/运营专家：提炼文章标签(3~5个)与摘要(120字内)，返回 {\"tags\":[...],\"summary\":\"...\"}")
    public String review(
            @ToolParam(description = "文章标题") String title,
            @ToolParam(description = "文章正文内容") String content) {
        String user = "标题：" + title + "\n\n正文：\n" + content + "\n\n请提炼标签与摘要，仅输出 JSON。";
        return askExpert(prompt("expert_seo", SYSTEM_PROMPT).content, user);
    }
}