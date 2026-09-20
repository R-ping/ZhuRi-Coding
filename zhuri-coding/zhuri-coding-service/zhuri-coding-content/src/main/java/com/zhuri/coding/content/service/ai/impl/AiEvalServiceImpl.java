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
import org.springframework.beans.factory.annotation.Value;
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

    // ===== 评测门禁阈值（百分数口径，与报告 avgRecall@5 等字段一致）=====
    /** 召回均值下限：avgRecall@5 >= 阈值 */
    @Value("${ai.eval.gate.min-avg-recall:60}")
    private double gateMinAvgRecall;
    /** 引用精确率下限：avgCitedPrecision >= 阈值 */
    @Value("${ai.eval.gate.min-cited-precision:50}")
    private double gateMinCitedPrecision;
    /** 未溯源率上限：avgUnsupportedRate <= 阈值 */
    @Value("${ai.eval.gate.max-unsupported-rate:30}")
    private double gateMaxUnsupportedRate;
    /** 无答案拒绝命中率下限：refusalRate >= 阈值；负值禁用该项检查 */
    @Value("${ai.eval.gate.min-refusal-rate:50}")
    private double gateMinRefusalRate;
    /** 召回评测最少有效题数（防评测集被清空/加载失败后门禁静默通过） */
    @Value("${ai.eval.gate.min-cases:10}")
    private int gateMinCases;

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
            int valid = 0;      // 总参评数（含无答案拒绝题）
            int scored = 0;     // 打分题数（有 golden，均值分母）
            int refusalCases = 0;
            int refusalHit = 0;
            double sumPrecision = 0d;
            double sumRecall = 0d;
            double sumSuspectRate = 0d;

            for (Map<String, Object> c : cases) {
                if (valid >= max) {
                    break;
                }
                String q = c.get("question") == null ? "" : String.valueOf(c.get("question"));
                boolean expectNoAnswer = Boolean.TRUE.equals(c.get("expectNoAnswer"));
                Set<Long> golden = toIdSet(c.get("goldenArticleIds"));
                if (q.isBlank() || (golden.isEmpty() && !expectNoAnswer)) {
                    continue;
                }

                com.heima.model.article.dtos.AiAnswerVo vo =
                    aiAskService.ask(q, ANSWER_TOP_K, Boolean.FALSE, null);
                if (vo == null || vo.getAnswer() == null || vo.getAnswer().isBlank()) {
                    // expectNoAnswer 题的降级空答案也不计命中（偏严格：拒绝能力应来自模型而非故障）
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
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("question", truncate(q, 60));
                if (expectNoAnswer) {
                    // 无答案拒绝题：答案有内容但引用为空 = 未拿资料硬编 = 拒绝成功；不参与打分
                    boolean refusal = citedIds.isEmpty();
                    refusalCases++;
                    if (refusal) {
                        refusalHit++;
                    }
                    item.put("expectNoAnswer", true);
                    item.put("cited", citedIds.size());
                    item.put("refusal", refusal);
                    perQuestion.add(item);
                    continue;
                }
                scored++;

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
            report.put("scored", scored);
            report.put("topK", ANSWER_TOP_K);
            report.put("faithfulnessSource", faithfulnessSource);
            report.put("refusalCases", refusalCases);
            report.put("refusalHit", refusalHit);
            report.put("refusalRate", refusalCases == 0 ? 0d
                : Math.round((double) refusalHit / refusalCases * 1000) / 10.0);
            report.put("avgCitedPrecision", scored == 0 ? 0d : Math.round(sumPrecision / scored * 1000) / 10.0);
            report.put("avgCitedRecall", scored == 0 ? 0d : Math.round(sumRecall / scored * 1000) / 10.0);
            report.put("avgUnsupportedRate", scored == 0 ? 0d : Math.round(sumSuspectRate / scored * 1000) / 10.0);
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

    /** 门禁阈值集合（百分数口径；负的 minRefusalRate 表示禁用拒绝率检查） */
    public static final class GateThresholds {
        public final double minAvgRecall;
        public final double minCitedPrecision;
        public final double maxUnsupportedRate;
        public final double minRefusalRate;
        public final int minRecallCases;

        public GateThresholds(double minAvgRecall, double minCitedPrecision, double maxUnsupportedRate,
                              double minRefusalRate, int minRecallCases) {
            this.minAvgRecall = minAvgRecall;
            this.minCitedPrecision = minCitedPrecision;
            this.maxUnsupportedRate = maxUnsupportedRate;
            this.minRefusalRate = minRefusalRate;
            this.minRecallCases = minRecallCases;
        }
    }

    @Override
    public Map<String, Object> runGate() {
        long start = System.currentTimeMillis();
        Map<String, Object> recall = runEval();
        Map<String, Object> answer = runAnswerEval(MAX_ANSWER_CASES);
        GateThresholds t = new GateThresholds(gateMinAvgRecall, gateMinCitedPrecision,
            gateMaxUnsupportedRate, gateMinRefusalRate, gateMinCases);
        Map<String, Object> gate = evaluateGate(t, recall, answer);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("gate", gate);
        report.put("recall", recall);
        report.put("answer", answer);
        report.put("costMs", System.currentTimeMillis() - start);
        log.info("[AiEval] 评测门禁完成, pass={}, costMs={}", gate.get("pass"),
            System.currentTimeMillis() - start);
        return report;
    }

    /**
     * 门禁判定（纯函数，便于单测）：逐项检查并聚合 pass。
     * fail-closed：报告缺失/带 error/召回题数不足按 0 分判定，评测跑不出来 ≠ 通过。
     */
    public static Map<String, Object> evaluateGate(GateThresholds t,
                                                   Map<String, Object> recall,
                                                   Map<String, Object> answer) {
        boolean recallOk = recall != null && !recall.containsKey("error");
        int recallCases = recallOk ? numInt(recall.get("cases"), 0) : 0;
        boolean recallEnough = recallOk && recallCases >= t.minRecallCases;
        double avgRecall5 = recallOk ? numDouble(recall.get("avgRecall@5"), 0d) : 0d;

        boolean answerOk = answer != null && !answer.containsKey("error");
        double citedPrecision = answerOk ? numDouble(answer.get("avgCitedPrecision"), 0d) : 0d;
        double unsupportedRate = answerOk ? numDouble(answer.get("avgUnsupportedRate"), 0d) : 0d;
        int refusalCases = answerOk ? numInt(answer.get("refusalCases"), 0) : 0;
        double refusalRate = answerOk ? numDouble(answer.get("refusalRate"), 0d) : 0d;

        List<Map<String, Object>> checks = new ArrayList<>();
        // 下限类 pass = value >= threshold，上限类 pass = value <= threshold；
        // 报告无效（缺失/带 error/题数不足）时按 0 分并强制 fail（fail-closed）。
        checks.add(check("avgRecall@5", avgRecall5, t.minAvgRecall, ">=",
            recallEnough && avgRecall5 >= t.minAvgRecall,
            recallEnough ? null : "recall 报告" + (recallOk ? "有效题数 " + recallCases + " < min-cases " + t.minRecallCases : "缺失或带 error") + "，按 0 分判定"));
        checks.add(check("avgCitedPrecision", citedPrecision, t.minCitedPrecision, ">=",
            answerOk && citedPrecision >= t.minCitedPrecision,
            answerOk ? null : "answer 报告缺失或带 error，按 0 分判定"));
        checks.add(check("avgUnsupportedRate", unsupportedRate, t.maxUnsupportedRate, "<=",
            answerOk && unsupportedRate <= t.maxUnsupportedRate,
            answerOk ? null : "answer 报告缺失或带 error，按 0 分判定"));
        if (t.minRefusalRate >= 0) {
            if (answerOk && refusalCases == 0) {
                // 评测集没有 expectNoAnswer 条目：该项 skipped 视为通过（门禁只能约束存在的样本）
                Map<String, Object> c = check("refusalRate", 0d, t.minRefusalRate, ">=", true, "skipped: 评测集无 expectNoAnswer 条目");
                c.put("skipped", true);
                checks.add(c);
            } else {
                checks.add(check("refusalRate", refusalRate, t.minRefusalRate, ">=",
                    answerOk && refusalRate >= t.minRefusalRate,
                    answerOk ? null : "answer 报告缺失或带 error，按 0 分判定"));
            }
        }

        boolean pass = true;
        for (Map<String, Object> c : checks) {
            if (!Boolean.TRUE.equals(c.get("pass"))) {
                pass = false;
                break;
            }
        }
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("pass", pass);
        Map<String, Object> th = new LinkedHashMap<>();
        th.put("minAvgRecall", t.minAvgRecall);
        th.put("minCitedPrecision", t.minCitedPrecision);
        th.put("maxUnsupportedRate", t.maxUnsupportedRate);
        th.put("minRefusalRate", t.minRefusalRate);
        th.put("minRecallCases", t.minRecallCases);
        gate.put("thresholds", th);
        gate.put("checks", checks);
        return gate;
    }

    private static Map<String, Object> check(String metric, double value, double threshold,
                                             String op, boolean pass, String detail) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("metric", metric);
        c.put("value", value);
        c.put("threshold", threshold);
        c.put("op", op);
        c.put("pass", pass);
        if (detail != null) {
            c.put("detail", detail);
        }
        return c;
    }

    /** 报告数值防御性提取：报告字段可能是 Double/Integer/其他，取不到给默认 */
    private static double numDouble(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    private static int numInt(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
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
