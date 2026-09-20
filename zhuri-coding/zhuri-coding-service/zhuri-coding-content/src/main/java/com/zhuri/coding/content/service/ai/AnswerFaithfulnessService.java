package com.heima.content.service.ai;

import com.heima.model.article.dtos.AiSourceVo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 答案忠实度校验（幻觉兜底）。
 *
 * <p>解决"答案带引用但引用对不上"的问题：模型可能编造没被资料支撑的句子，或者引用了不存在的序号。
 * 三级递进（与项目其它 AI 链路同一套路：确定性 → 便宜信号 → 贵的模型）：
 * <ol>
 *   <li><b>确定性检查</b>：引用序号越界、实质句子完全没有引用；</li>
 *   <li><b>向量预筛</b>：句子与其被引用来源正文的余弦相似度过低 → 疑似无支撑；</li>
 *   <li><b>LLM 复核</b>：只对可疑句调用一次模型判定，避免逐句问模型的高成本。</li>
 * </ol>
 * 全链路 fail-open：任何异常都退化为"未检出问题"，绝不阻断问答。
 */
public interface AnswerFaithfulnessService {

    /**
     * 校验一条答案。
     *
     * @param answer  回答正文（含 `[n]` 引用）
     * @param sources 本次引用的来源列表（与 [n] 序号一一对应）
     * @param docsText 检索上下文原文（用于还原每个序号对应的来源正文，可为空）
     * @return 校验报告，永不为 null
     */
    Report check(String answer, List<AiSourceVo> sources, String docsText);

    /** 校验报告 */
    final class Report {

        private final int checkedSentences;

        private final List<String> unsupported;

        private final List<Integer> invalidCitations;

        private final List<String> noCitation;

        private final boolean llmReviewed;

        private final String method;

        public Report(int checkedSentences, List<String> unsupported, List<Integer> invalidCitations,
                      List<String> noCitation, boolean llmReviewed, String method) {
            this.checkedSentences = checkedSentences;
            this.unsupported = unsupported == null ? Collections.emptyList() : unsupported;
            this.invalidCitations = invalidCitations == null ? Collections.emptyList() : invalidCitations;
            this.noCitation = noCitation == null ? Collections.emptyList() : noCitation;
            this.llmReviewed = llmReviewed;
            this.method = method;
        }

        public static Report empty() {
            return new Report(0, null, null, null, false, "skipped");
        }

        public int getCheckedSentences() {
            return checkedSentences;
        }

        /** 疑似无资料支撑的句子片段 */
        public List<String> getUnsupported() {
            return unsupported;
        }

        /** 越界的引用序号（如答案写 [5] 但只召回了 3 篇） */
        public List<Integer> getInvalidCitations() {
            return invalidCitations;
        }

        /** 含实质内容但没有任何引用的句子片段 */
        public List<String> getNoCitation() {
            return noCitation;
        }

        public boolean isLlmReviewed() {
            return llmReviewed;
        }

        public String getMethod() {
            return method;
        }

        public boolean hasIssue() {
            return !unsupported.isEmpty() || !invalidCitations.isEmpty();
        }

        /** 供接口返回/日志用的精简结构（不暴露整段答案） */
        public Map<String, Object> toBrief() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("checked", checkedSentences);
            m.put("suspect", unsupported.size());
            m.put("invalidCitations", invalidCitations);
            m.put("unsupported", new ArrayList<>(unsupported));
            m.put("method", method);
            m.put("llmReviewed", llmReviewed);
            return m;
        }
    }
}
