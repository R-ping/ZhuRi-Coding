package com.heima.content.service.ai.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.content.service.ai.AiAskService;
import com.heima.content.service.ai.AiEvalService;
import com.heima.content.service.ai.AnswerFaithfulnessService;
import com.heima.content.service.ai.HybridRecallService;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.content.utils.CitationParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 检索评测实现（Recall@k）
 */
@Slf4j
@Service
public class AiEvalServiceImpl implements AiEvalService {

    private static final String EVAL_FILE = "ai-eval/eval-questions.json";
    private static final int[] TOP_KS = {5, 10};

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    @Autowired
    private HybridRecallService hybridRecallService;

    @Autowired
    private AiAskService aiAskService;

    @Autowired
    private AnswerFaithfulnessService faithfulnessService;

    /** 答案级评测单次最多参评问题数（每题一次生成，硬顶防刷成本） */
    private static final int MAX_ANSWER_CASES = 12;

    /** 答案级评测取答案的 topK */
    private static final int ANSWER_TOP_K = 5;

    @Override
    public Map<String, Object> runEval() {
        long start = System.currentTimeMillis();
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> perQuestion = new ArrayList<>();
        try {
            List<Map<String, Object>> cases = loadCases();
            if (cases == null || cases.isEmpty()) {
                report.put("error", "评测集为空: " + EVAL_FILE);
                return report;
            }
            // recall 均值累加器
            Map<Integer, Double> recallSum = new HashMap<>();
            for (int k : TOP_KS) {
                recallSum.put(k, 0d);
            }
            int valid = 0;
            for (Map<String, Object> c : cases) {
                String q = c.get("question") == null ? "" : String.valueOf(c.get("question"));
                Set<Long> golden = toIdSet(c.get("goldenArticleIds"));
                if (q.isBlank() || golden.isEmpty()) {
                    continue;
                }
                valid++;
                // query 向量化 → pgvector 召回（k 取最大，按序判断各 k 命中）
                double[] emb = embeddingService.generateEmbedding(q);
                if (emb == null || emb.length == 0) {
                    log.warn("[AiEval] query 向量化失败: {}", q);
                    continue;
                }
                // 与线上 RAG 同口径（向量父子分块 + BM25 → RRF 融合）：口径不一致时 recall@k 没有意义
                com.heima.content.service.ai.HybridRecallService.Recall hybrid =
                    hybridRecallService.recall(q, emb, TOP_KS[TOP_KS.length - 1]);
                Set<Long> recallIds = new HashSet<>(hybrid.getIds());
                List<Long> recalled = hybrid.getIds();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("question", truncate(q, 60));
                item.put("golden", golden.size());
                int hitCount = 0;
                for (Long g : golden) {
                    if (recallIds.contains(g)) {
                        hitCount++;
                    }
                }
                item.put("hitInTopK", hitCount);
                item.put("goldenIds", golden);
                for (int k : TOP_KS) {
                    // 取融合召回前 k 与 golden 的交集（须保持 RRF 融合排名顺序，否则 recall@k 无意义）
                    int cnt = 0;
                    if (recalled != null) {
                        for (int i = 0; i < recalled.size() && i < k; i++) {
                            if (golden.contains(recalled.get(i))) {
                                cnt++;
                            }
                        }
                    }
                    double r = golden.isEmpty() ? 0d : (double) cnt / golden.size();
                    item.put("recall@" + k, Math.round(r * 1000) / 10.0);
                    recallSum.put(k, recallSum.get(k) + r);
                }
                perQuestion.add(item);
            }
            report.put("cases", valid);
            report.put("perQuestion", perQuestion);
            for (int k : TOP_KS) {
                double avg = valid == 0 ? 0d : recallSum.get(k) / valid;
                report.put("avgRecall@" + k, Math.round(avg * 1000) / 10.0);
            }
            report.put("costMs", System.currentTimeMillis() - start);
            log.info("[AiEval] 评测完成, cases={}, avgRecall@5={}, avgRecall@10={}, costMs={}",
                valid, report.get("avgRecall@5"), report.get("avgRecall@10"),
                System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[AiEval] 评测异常", e);
            report.put("error", "评测异常: " + e.getMessage());
        }
        return report;
    }

    /**
     * 答案级评测（生成侧）：真实跑问答，统计引用精确率 / 引用召回率 / 未溯源率。
     *
     * <p>引用精确率 = 引用的文章里真属 golden 的比例（低说明"引了不相关的文章"）；
     * 引用召回率 = golden 被引用的比例（低说明"该引的没引"）；
     * 未溯源率 = 忠实度校验判定为疑似无支撑的句子占比（低为佳）。
     */
    @Override
    public Map<String, Object> runAnswerEval(int limit) {
        long start = System.currentTimeMillis();
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> perQuestion = new ArrayList<>();
        String faithfulnessSource = "deterministic-only";
        try {
            List<Map<String, Object>> cases = loadCases();
            if (cases == null || cases.isEmpty()) {
                report.put("error", "评测集为空: " + EVAL_FILE);
                return report;
            }
            int max = Math.max(1, Math.min(limit, MAX_ANSWER_CASES));
            int valid = 0;
            double sumPrecision = 0d;
            double sumRecall = 0d;
            double sumSuspectRate = 0d;

            for (Map<String, Object> c : cases) {
                if (valid >= max) {
                    break;
                }
                String q = c.get("question") == null ? "" : String.valueOf(c.get("question"));
                Set<Long> golden = toIdSet(c.get("goldenArticleIds"));
                if (q.isBlank() || golden.isEmpty()) {
                    continue;
                }
                com.heima.model.article.dtos.AiAnswerVo vo =
                    aiAskService.ask(q, ANSWER_TOP_K, Boolean.FALSE, null);
                if (vo == null || vo.getAnswer() == null || vo.getAnswer().isBlank()) {
                    continue;
                }
                valid++;

                // ① 从答案解析被引用的序号 → 映射到 sources 的 articleId
                Set<Long> citedIds = new HashSet<>();
                for (CitationParser.Sentence s : CitationParser.splitSentences(vo.getAnswer())) {
                    for (Integer n : s.getCitations()) {
                        if (vo.getSources() != null && n >= 1 && n <= vo.getSources().size()) {
                            Long aid = vo.getSources().get(n - 1).getArticleId();
                            if (aid != null) {
                                citedIds.add(aid);
                            }
                        }
                    }
                }
                long hit = 0;
                for (Long id : citedIds) {
                    if (golden.contains(id)) {
                        hit++;
                    }
                }
                double precision = citedIds.isEmpty() ? 0d : (double) hit / citedIds.size();
                double recall = (double) hit / golden.size();

                // ② 忠实度：优先用响应里的同步简报（含向量预筛 + LLM 复核），否则退化为确定性检查
                int checked;
                int suspect;
                Map<String, Object> brief = vo.getFaithfulness();
                if (brief != null) {
                    faithfulnessSource = "sync-response";
                    checked = brief.get("checked") instanceof Number ? ((Number) brief.get("checked")).intValue() : 0;
                    suspect = brief.get("suspect") instanceof Number ? ((Number) brief.get("suspect")).intValue() : 0;
                } else {
                    AnswerFaithfulnessService.Report fr =
                        faithfulnessService.check(vo.getAnswer(), vo.getSources(), null);
                    checked = fr.getCheckedSentences();
                    suspect = fr.getUnsupported().size();
                }
                double suspectRate = checked == 0 ? 0d : (double) suspect / checked;

                sumPrecision += precision;
                sumRecall += recall;
                sumSuspectRate += suspectRate;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("question", truncate(q, 60));
                item.put("golden", golden.size());
                item.put("cited", citedIds.size());
                item.put("citedPrecision", Math.round(precision * 1000) / 10.0);
                item.put("citedRecall", Math.round(recall * 1000) / 10.0);
                item.put("sentences", checked);
                item.put("unsupported", suspect);
                item.put("unsupportedRate", Math.round(suspectRate * 1000) / 10.0);
                perQuestion.add(item);
            }

            report.put("cases", valid);
            report.put("topK", ANSWER_TOP_K);
            report.put("faithfulnessSource", faithfulnessSource);
            report.put("avgCitedPrecision", valid == 0 ? 0d : Math.round(sumPrecision / valid * 1000) / 10.0);
            report.put("avgCitedRecall", valid == 0 ? 0d : Math.round(sumRecall / valid * 1000) / 10.0);
            report.put("avgUnsupportedRate", valid == 0 ? 0d : Math.round(sumSuspectRate / valid * 1000) / 10.0);
            report.put("perQuestion", perQuestion);
            report.put("costMs", System.currentTimeMillis() - start);
            log.info("[AiEval] 答案级评测完成, cases={}, avgCitedPrecision={}, avgCitedRecall={}, avgUnsupportedRate={}, costMs={}",
                valid, report.get("avgCitedPrecision"), report.get("avgCitedRecall"),
                report.get("avgUnsupportedRate"), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[AiEval] 答案级评测异常", e);
            report.put("error", "答案级评测异常: " + e.getMessage());
        }
        return report;
    }

    private List<Map<String, Object>> loadCases() throws Exception {        ClassPathResource res = new ClassPathResource(EVAL_FILE);
        if (!res.exists()) {
            return new ArrayList<>();
        }
        try (InputStream in = res.getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        }
    }

    private Set<Long> toIdSet(Object raw) {
        Set<Long> out = new HashSet<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Number) {
                    out.add(((Number) o).longValue());
                } else if (o != null) {
                    try {
                        out.add(Long.parseLong(String.valueOf(o)));
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }
}
