package com.zhuri.coding.content.service.ai.agent.workflow;

import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import java.util.EnumMap;
import java.util.Map;

/**
 * 显式工作流的阶段上下文：贯穿整个预检流程的数据载体。
 *
 * <p>持有原始输入（标题/正文/文章 id/封面）与各阶段中间产物（按 {@link StageType}
 * 归档），供后续阶段与最终结构化阶段随时取用；并携带封面审核/相似度等兜底所需的
 * 已有实现引用，避免在工作流实现里重复注入一长串依赖。
 */
public class StageContext {

    /** 原始输入 */
    private final String title;
    private final String content;
    private final Long articleId;
    private final String coverImageUrl;

    /** 各阶段产物表（未执行/跳过的阶段无条目） */
    private final Map<StageType, StageResult> results = new EnumMap<>(StageType.class);

    /** 终态 VO（FORMAT 阶段填充） */
    private AiPrecheckVo vo;

    public StageContext(String title, String content, Long articleId, String coverImageUrl) {
        this.title = title;
        this.content = content;
        this.articleId = articleId;
        this.coverImageUrl = coverImageUrl;
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public Long articleId() {
        return articleId;
    }

    public String coverImageUrl() {
        return coverImageUrl;
    }

    /** 记录某阶段结果 */
    public void put(StageType type, StageResult result) {
        results.put(type, result);
    }

    /** 取某阶段产物（可能为 null：未执行/失败） */
    public StageResult get(StageType type) {
        return results.get(type);
    }

    /** 取某阶段的负载（JSON 字符串等）；未执行或失败返回 null */
    public Object payload(StageType type) {
        StageResult r = results.get(type);
        return r == null ? null : r.payload();
    }

    public AiPrecheckVo vo() {
        return vo;
    }

    public void setVo(AiPrecheckVo vo) {
        this.vo = vo;
    }
}