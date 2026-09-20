package com.heima.content.service.ai.spring;

import com.heima.content.service.ai.agent.tools.SimilaritySearchTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spring AI @Tool：社区文章相似度查重（包装 pgvector 检索，模型自主决定调用）
 */
@Component
public class AiSimilarityTools {

    @Autowired
    private SimilaritySearchTool similaritySearchTool;

    @Tool(name = "search_similar_article",
          description = "根据正文检索社区中语义最相近的已发布文章，返回文章列表（含 articleId/title/similarity），用于判断内容是否与他人重复")
    public String searchSimilarArticle(@ToolParam(description = "文章正文内容") String content) {
        String jsonArgs = "{\"content\":\"" + safe(content) + "\"}";
        return similaritySearchTool.execute(jsonArgs);
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
