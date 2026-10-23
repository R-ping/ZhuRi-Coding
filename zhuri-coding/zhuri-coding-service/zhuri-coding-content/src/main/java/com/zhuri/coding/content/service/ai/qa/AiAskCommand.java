package com.zhuri.coding.content.service.ai.qa;

import com.zhuri.coding.model.article.dtos.AiAskDto;

import java.util.List;
import java.util.Map;

/**
 * 统一问答命令：范围、模式、锚点、会话与用户身份的唯一入参。
 */
public record AiAskCommand(
    String question,
    AiScope scope,
    AiMode mode,
    Long articleId,
    Long anchorArticleId,
    Integer topK,
    List<Map<String, String>> history,
    Integer userId,
    String source
) {

    private static final int MAX_QUESTION_LEN = 200;
    private static final int MAX_TOP_K = 8;

    /**
     * 从接口 DTO 归一化。旧客户端只传 fast 时按 fast=true→FAST / false→DEEP 兼容。
     *
     * @throws IllegalArgumentException 参数不合法
     */
    public static AiAskCommand from(AiAskDto dto, Integer userId) {
        if (dto == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }
        String question = dto.getQuestion() == null ? "" : dto.getQuestion().trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (question.length() > MAX_QUESTION_LEN) {
            throw new IllegalArgumentException("问题过长（最多" + MAX_QUESTION_LEN + "字）");
        }

        AiScope scope = parseScope(dto.getScope());
        AiMode mode = parseMode(dto.getMode(), dto.getFast());
        Long articleId = dto.getArticleId();
        if (scope == AiScope.ARTICLE) {
            if (articleId == null) {
                throw new IllegalArgumentException("单篇问答缺少 articleId");
            }
            // 单篇是封闭上下文，本期不提供深度模式
            mode = AiMode.FAST;
        }

        Integer topK = dto.getTopK();
        if (topK != null) {
            topK = Math.max(1, Math.min(topK, MAX_TOP_K));
        }

        String source = dto.getSource() == null || dto.getSource().isBlank()
            ? "normal" : dto.getSource().trim();

        return new AiAskCommand(question, scope, mode, articleId,
            dto.getAnchorArticleId(), topK, dto.getHistory(), userId, source);
    }

    /** 返回同参数但替换模式的新命令（用于深度流式开关降级）。 */
    public AiAskCommand withMode(AiMode newMode) {
        return new AiAskCommand(question, scope, newMode, articleId, anchorArticleId,
            topK, history, userId, source);
    }

    private static AiScope parseScope(String raw) {
        if (raw == null || raw.isBlank() || "global".equalsIgnoreCase(raw)) {
            return AiScope.GLOBAL;
        }
        if ("article".equalsIgnoreCase(raw)) {
            return AiScope.ARTICLE;
        }
        throw new IllegalArgumentException("scope 不合法：" + raw);
    }

    private static AiMode parseMode(String raw, Boolean legacyFast) {
        if (raw != null && !raw.isBlank()) {
            if ("deep".equalsIgnoreCase(raw)) {
                return AiMode.DEEP;
            }
            if ("fast".equalsIgnoreCase(raw)) {
                return AiMode.FAST;
            }
            throw new IllegalArgumentException("mode 不合法：" + raw);
        }
        if (legacyFast != null) {
            return legacyFast ? AiMode.FAST : AiMode.DEEP;
        }
        return AiMode.FAST;
    }
}
