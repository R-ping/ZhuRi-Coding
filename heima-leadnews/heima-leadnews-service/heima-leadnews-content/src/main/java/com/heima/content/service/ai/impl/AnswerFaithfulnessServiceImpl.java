package com.heima.content.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.content.service.ai.AiMetricsCollector;
import com.heima.content.service.ai.AnswerFaithfulnessService;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.content.utils.CitationParser;
import com.heima.model.article.dtos.AiSourceVo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 忠实度校验实现（三级递进：确定性 → 向量预筛 → LLM 复核）。
 *
 * <p>成本控制要点：向量相似度用本地已有的 embedding 模型算（句子短、调用便宜），
 * 只有"疑似无支撑"的句子才打包成一次模型调用做复核，避免逐句问模型。
 */
@Slf4j
@Service
public class AnswerFaithfulnessServiceImpl implements AnswerFaithfulnessService {

    /** 方法标记（写进报告，便于对比不同版本口径） */
    private static final String METHOD = "cite_vec_llm_v1";

    /** 句子与其被引用来源的余弦低于该值 → 疑似无支撑（进 LLM 复核） */
    @Value("${ai.faithfulness.vec-threshold:0.45}")
    private double vecThreshold;

    /** 是否启用 LLM 复核（关闭则仅用向量预筛结论） */
    @Value("${ai.faithfulness.llm-review:true}")
    private boolean llmReviewEnabled;

    /** 单句送复核的最大可疑句数（防止异常答案触发超长 prompt） */
    @Value("${ai.faithfulness.max-suspects:5}")
    private int maxSuspects;

    /** 送复核时每段来源正文截断 */
    private static final int SNIPPET_CHARS = 600;

    private static final String REVIEW_PROMPT =
        "你是 RAG 答案的忠实度审核员。给定若干候选句与其被引用的资料片段，判断每句是否【确实由资料支撑】。"
            + "判定标准：资料里有对应事实（允许同义改写、允许概括）即视为支撑；若资料中找不到依据、或结论明显超出资料范围，则判为不支撑。"
            + "只输出 JSON：{\"unsupported\":[{\"idx\":序号,\"reason\":\"不超过30字理由\"}]}，没有不支撑的句子就输出 {\"unsupported\":[]}。";

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private AiMetricsCollector metrics;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Report check(String answer, List<AiSourceVo> sources, String docsText) {
        try {
            List<CitationParser.Sentence> sentences = CitationParser.splitSentences(answer);
            if (sentences.isEmpty()) {
                return Report.empty();
            }
            int sourceCount = sources == null ? 0 : sources.size();
            Map<Integer, String> blocks = CitationParser.parseSourceBlocks(docsText);

            List<String> invalid = new ArrayList<>();
            List<Integer> invalidNums = new ArrayList<>();
            List<String> noCitation = new ArrayList<>();
            List<String> suspects = new ArrayList<>();
            List<Integer> suspectSrcIdx = new ArrayList<>();

            for (CitationParser.Sentence s : sentences) {
                // ① 确定性：引用越界（记录具体序号，便于排查是拼接错位还是模型编造）
                for (Integer n : s.getCitations()) {
                    if (n > sourceCount) {
                        invalid.add(brief(s.getText()));
                        if (!invalidNums.contains(n)) {
                            invalidNums.add(n);
                        }
                    }
                }
                // ② 确定性：实质内容但完全没有引用（总结句可能合法，故只记录不重判）
                if (!s.hasCitation()) {
                    if (CitationParser.isSubstantive(s.getText())) {
                        noCitation.add(brief(s.getText()));
                    }
                    continue;
                }
                if (blocks.isEmpty()) {
                    continue; // 拿不到来源正文 → 跳过向量预筛（fail-open）
                }
                // ③ 向量预筛：取与该句所有被引用来源的最大余弦
                double best = maxCosine(CitationParser.stripCitations(s.getText()), s.getCitations(), blocks);
                if (best >= 0) {
                    if (best < vecThreshold) {
                        suspects.add(s.getText());
                        suspectSrcIdx.add(s.getCitations().get(0));
                    }
                }
            }

            boolean llmUsed = false;
            List<String> unsupported = new ArrayList<>(invalid);
            if (!suspects.isEmpty() && llmReviewEnabled) {
                List<String> llmFlagged = llmReview(suspects, suspectSrcIdx, blocks);
                llmUsed = true;
                if (llmFlagged != null) {
                    unsupported = new ArrayList<>(llmFlagged);
                    unsupported.addAll(invalid);
                } else {
                    // 模型不可用：按向量预筛结论兜底（宁可多提示，不漏报）
                    unsupported = new ArrayList<>(suspects);
                    unsupported.addAll(invalid);
                }
            } else if (!suspects.isEmpty()) {
                unsupported = new ArrayList<>(suspects);
                unsupported.addAll(invalid);
            }

            Report report = new Report(sentences.size(), unsupported, invalidNums, noCitation,
                llmUsed, METHOD);
            metrics.incr("ai_faithfulness_checked");
            if (report.hasIssue()) {
                metrics.incr("ai_faithfulness_suspect");
            }
            if (!invalid.isEmpty()) {
                metrics.incr("ai_faithfulness_invalid_citation");
            }
            log.info("[Faithfulness] checked={}, suspect={}, invalidCite={}, noCite={}, llm={}",
                sentences.size(), unsupported.size(), invalid.size(), noCitation.size(), llmUsed);
            return report;
        } catch (Exception e) {
            log.warn("[Faithfulness] 校验异常，按未检出处理（fail-open）", e);
            return Report.empty();
        }
    }

    /** 句子与被引用来源的最大余弦；来源正文缺失返回 -1（跳过） */
    private double maxCosine(String sentence, List<Integer> citations, Map<Integer, String> blocks) {
        double best = -1;
        double[] sentEmb = null;
        for (Integer n : citations) {
            String src = blocks.get(n);
            if (src == null || src.isBlank()) {
                continue;
            }
            try {
                if (sentEmb == null) {
                    sentEmb = embeddingService.generateEmbedding(sentence);
                }
                double[] srcEmb = embeddingService.generateEmbedding(truncate(src, 2000));
                if (sentEmb == null || srcEmb == null) {
                    continue;
                }
                double cos = cosine(sentEmb, srcEmb);
                if (cos > best) {
                    best = cos;
                }
            } catch (Exception e) {
                log.debug("[Faithfulness] 余弦计算失败, idx={}", n);
            }
        }
        return best;
    }

    /** 一次模型调用复核全部可疑句；失败返回 null（调用方按向量结论兜底） */
    private List<String> llmReview(List<String> suspects, List<Integer> srcIdx, Map<Integer, String> blocks) {
        try {
            int limit = Math.min(suspects.size(), Math.max(1, maxSuspects));
            StringBuilder user = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                int srcNo = srcIdx.get(i);
                String snippet = blocks.getOrDefault(srcNo, "");
                user.append("候选句 ").append(i + 1).append("：")
                    .append(CitationParser.stripCitations(suspects.get(i))).append("\n")
                    .append("引用资料[").append(srcNo).append("]片段：")
                    .append(truncate(snippet, SNIPPET_CHARS)).append("\n\n");
            }
            String raw = org.springframework.ai.chat.client.ChatClient.builder(chatModel).build()
                .prompt().system(REVIEW_PROMPT).user(user.toString()).call().content();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode root = objectMapper.readTree(raw.substring(start, end + 1));
            JsonNode arr = root.path("unsupported");
            if (!arr.isArray()) {
                return null;
            }
            List<String> flagged = new ArrayList<>();
            for (JsonNode node : arr) {
                int idx = node.path("idx").asInt(0);
                if (idx >= 1 && idx <= limit) {
                    flagged.add(brief(suspects.get(idx - 1)));
                }
            }
            return flagged;
        } catch (Exception e) {
            log.warn("[Faithfulness] LLM 复核失败，回退向量结论", e);
            return null;
        }
    }

    private static double cosine(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String brief(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }
}
