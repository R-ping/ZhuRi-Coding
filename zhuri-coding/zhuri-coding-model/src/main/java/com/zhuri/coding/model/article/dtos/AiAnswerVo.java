package com.heima.model.article.dtos;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 社区 AI 问答响应（RAG：answer + 来源文章）
 */
@Data
public class AiAnswerVo {

    /** 回答正文（引用以 [n] 标注，对应 sources 下标） */
    private String answer;

    /** 召回并作为依据的来源文章（按相似度降序） */
    private List<AiSourceVo> sources;

    /** 总耗时 ms（向量+生成） */
    private long latencyMs;

    /**
     * 忠实度校验简报（可选，仅在开启同步校验时填充；前端不读也不影响）：
     * {checked, suspect, invalidCitations, unsupported, method, llmReviewed}
     */
    private Map<String, Object> faithfulness;

    /**
     * 本次回答使用的 prompt 版本归因（P2-1，可选；前端不读也不影响）：
     * key = 注册表 prompt_key，value = 命中版本号（0 = 代码内置兜底版）。
     * 用于灰度对比与质量问题归因（"这个答案用了 v2 的 rerank prompt"）。
     */
    private Map<String, Integer> promptVersions;
}
