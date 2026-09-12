package com.heima.content.service.ai.spring;

import com.heima.content.service.ai.agent.tools.ContentSafetyTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spring AI @Tool 注册的内容安全工具（全迁 Gate：验证 qwen compatible 网关对 OpenAI tools 协议的支持）
 */
@Component
public class AiSafetyTools {

    @Autowired
    private ContentSafetyTool contentSafetyTool;

    @Tool(name = "content_safety_check", description = "对标题与正文做内容安全预检（色情/赌博/诈骗/毒品/暴力等），返回 {\"is_violation\":true/false,...}")
    public String contentSafetyCheck(
            @ToolParam(description = "文章标题") String title,
            @ToolParam(description = "文章正文") String content) {
        String jsonArgs = "{\"title\":\"" + safe(title) + "\",\"content\":\"" + safe(content) + "\"}";
        return contentSafetyTool.execute(jsonArgs);
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
