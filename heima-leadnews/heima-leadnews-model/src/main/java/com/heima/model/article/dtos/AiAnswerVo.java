package com.heima.model.article.dtos;

import java.util.List;
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
}
