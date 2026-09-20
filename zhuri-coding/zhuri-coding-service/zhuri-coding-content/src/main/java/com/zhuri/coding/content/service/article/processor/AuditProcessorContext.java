package com.zhuri.coding.content.service.article.processor;

import java.util.HashMap;
import java.util.Map;

/**
 * 审核处理器上下文 - 在处理器链中传递数据
 * 用于在审核流程的不同阶段之间共享数据
 */
public class AuditProcessorContext {

    /** AI分析结果 */
    private Map<String, Object> aiAnalysisResult;

    /** 是否高相似度文章 */
    private boolean highSimilarity;

    /** 扩展数据 */
    private final Map<String, Object> extra = new HashMap<>();

    public Map<String, Object> getAiAnalysisResult() {
        return aiAnalysisResult;
    }

    public void setAiAnalysisResult(Map<String, Object> aiAnalysisResult) {
        this.aiAnalysisResult = aiAnalysisResult;
    }

    public boolean isHighSimilarity() {
        return highSimilarity;
    }

    public void setHighSimilarity(boolean highSimilarity) {
        this.highSimilarity = highSimilarity;
    }

    public void putExtra(String key, Object value) {
        extra.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getExtra(String key) {
        return (T) extra.get(key);
    }
}