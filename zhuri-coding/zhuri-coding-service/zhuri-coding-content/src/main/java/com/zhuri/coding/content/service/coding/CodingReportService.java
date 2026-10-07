package com.zhuri.coding.content.service.coding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhuri.coding.common.bailian.PromptSanitizer;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.AiPromptRegistry;
import com.zhuri.coding.content.service.coding.CodingInterviewJson.CoverageData;
import com.zhuri.coding.content.service.coding.CodingInterviewJson.PlanTopic;
import com.zhuri.coding.content.service.coding.CodingInterviewJson.ReportData;
import com.zhuri.coding.content.service.coding.CodingInterviewJson.ReportItemData;
import com.zhuri.coding.content.service.coding.CodingInterviewJson.TurnRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingInterview;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static com.zhuri.coding.content.service.coding.CodingInterviewJson.appendDialogueLine;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.difficultyLabel;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.extractJsonArray;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.extractJsonObject;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.isBlank;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.nvl;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.parsePlanList;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.parseTurns;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.truncate;

/**
 * 模拟面试报告服务（Coding 延展第三层 · Stage A）。
 *
 * <p>从 {@code CodingInterviewServiceImpl} 抽出的报告链路：一场面试结束后，
 * <b>分批逐题评估 → 批内逐位对齐 → 二次汇总</b>，产出报告 JSON；解析失败时存模型原文可重试。
 * 抽出来的动因不是"类太大"本身，而是报告链路已经长成一个约有自己名词体系的独立单元
 * （分批 / 对齐 / 占位 / 软硬预算 / 汇总兜底），和开面、轮次编排放一起互相干扰阅读。</p>
 *
 * <p><b>为什么分批</b>：整场对话一次喂给模型，长对话的后半段容易被略读，而且一次失败整份报告就没了。
 * 分批后每批只带本批主题的关键考点与问答实录，<b>段内不按条截断</b> —— 原实现给每条消息截 1200 字，
 * 砍掉的恰是判技术准确性的论证细节。</p>
 *
 * <p><b>三层降级</b>：① 单批失败（含预算耗尽）只给该批主题补「未评估」占位，其余批次照常出分；
 * ② 汇总失败/超预算用各主题结论确定性拼装总评与建议；③ 全部批次失败返回模型原文（调用方存库降级，可重试）。</p>
 */
@Slf4j
@Service
public class CodingReportService {

    // ==================== 常量 ====================

    /**
     * 报告分段评估：单个主题的问答实录保留上限（字符）。
     * 作答本就有 2000 字上限（每主题最多 1 次追问，正常一场远达不到），
     * 这里只是成本安全阀 —— 报告链路<b>不再按条截断</b>，否则被砍掉的恰是判准确性的论证细节。
     */
    private static final int REPORT_TOPIC_KEEP = 4_000;
    /** 二次汇总输入里单主题点评的截断长度（字符，喂结论摘要而非原始对话） */
    private static final int HIGHLIGHT_COMMENT_CHARS = 80;
    /**
     * 报告总超时里给二次汇总预留的时间（毫秒）。
     * 分批是串行累加的，不预留的话分批可能吃光预算、轮到汇总只能失败收场。
     */
    private static final long REPORT_SUMMARY_RESERVE_MS = 8_000L;
    /** 未评估占位评语（分批失败时该主题的说明，不参与综合等级计算） */
    private static final String PENDING_COMMENT = "该主题未生成评估结果，可点击「重新生成报告」重试";

    /** prompt 注册表 key：报告 · 分批逐题评估（吃本批主题的问答实录，出等级 + 逐题点评） */
    static final String PROMPT_KEY_REPORT_TOPIC = "coding_interview_report_topic";
    /** prompt 注册表 key：报告 · 二次汇总（只吃各主题结论摘要，出总评与建议） */
    static final String PROMPT_KEY_REPORT = "coding_interview_report";

    /**
     * 报告 · 分批逐题评估 system（代码兜底）。
     *
     * <p>只评"用户消息里给出的这一批主题"，输出严格等长等序的 JSON 数组 —— 批内逐位对齐靠它。</p>
     */
    static final String FALLBACK_REPORT_TOPIC_SYSTEM = String.join("\n",
        "你是资深技术面试官，面试已结束，请评估用户消息中列出的主题。",
        "你必须只输出一个 JSON 数组，不能有 markdown 代码块或解释文字，每个主题对应一个元素：",
        "{\"topic\":\"主题名\",\"structure\":1,\"coverage\":{\"covered\":[\"已覆盖考点\"],\"missing\":[\"未覆盖考点\"]},"
            + "\"accuracy\":1,\"comment\":\"点评\"}",
        "评分纪律：",
        "1. 数组元素数量与顺序必须与用户消息中的主题数量与顺序完全一致，不要合并、不要新增、不要遗漏；",
        "2. 覆盖度先行：逐项对照该主题的关键考点，判断是否被候选人讲到，考点的原文对齐到 covered/missing；",
        "3. structure（回答结构）与 accuracy（技术准确性）都给 1-5 整数等级：1=几乎空白/错误，2=明显缺失，"
            + "3=基本合格，4=良好，5=优秀；没有把握时从低；",
        "4. comment 必须给出依据：引用候选人的原话要点或指出其遗漏的考点，不许空泛评价；",
        "5. 候选人未作答的主题也要输出元素：covered 为空、comment 说明「未作答」；",
        "6. 只评价技术内容与表达，不评价人格，不编造候选人没说过的内容。");

    /**
     * 报告 · 二次汇总 system（代码兜底）。
     *
     * <p>输入是各主题的<b>结论摘要</b>（等级 + 覆盖情况 + 点评摘要），不是原始对话 ——
     * 汇总阶段只做归纳，不重新判分。</p>
     */
    static final String FALLBACK_REPORT_SYSTEM = String.join("\n",
        "你是资深技术面试官，面试已结束，下面给出各主题的评估结论，请据此输出总评与改进建议。",
        "你必须只输出一个 JSON 对象，不能有 markdown 代码块或解释文字：",
        "{\"overall\":\"总评\",\"suggestions\":[\"改进建议\"]}",
        "要求：",
        "1. 只能使用输入中给出的评估结论，不要编造候选人没说过的内容或不存在的能力经历；",
        "2. overall 是整体结论（优势与短板，3-4 句），要指出哪几个主题扎实、哪几个薄弱及其原因；",
        "3. suggestions 给 3-5 条可执行的改进建议，优先来自「待补强考点」，条与条之间不要重复；",
        "4. 标为「未完成评估」的主题不要评价，也不要因此判断候选人不会；",
        "5. 只评价技术内容与表达，不评价人格。");

    // ==================== 配置 ====================

    /** 报告生成超时（秒；超时按结构化失败降级，可重试） */
    @Value("${app.coding.interview.report-timeout-seconds:30}")
    private int reportTimeoutSeconds = 30;

    /** 报告分批评估每批主题数（1=逐主题单独评估；越大越省调用但单次 prompt 越长） */
    @Value("${app.coding.interview.report-batch-size:3}")
    private int reportBatchSize = 3;

    // ==================== 依赖 ====================

    @Autowired
    private AiLlmGateway aiLlmGateway;

    /** prompt 注册表（可空注入：单测/未装配时走代码兜底） */
    @Autowired(required = false)
    private AiPromptRegistry promptRegistry;

    @Autowired
    private PromptSanitizer promptSanitizer;

    /** SSE 执行池（报告超时保护用；单测/未装配时同步直调） */
    @Autowired(required = false)
    @Qualifier("aiSseExecutor")
    private Executor aiSseExecutor;

    // ==================== 对外入口 ====================

    /**
     * 生成报告（超时保护）。
     *
     * <p>注意分批把链路拉长了：n 个主题 = ⌈n/每批⌉ + 1 次调用，串行累加。
     * 所以超时不能只当成"最后一道保险"（那样一超时整份报告就没了），而要提前切成软预算：
     * 分批阶段用完「总预算 - 汇总预留」就停止再发批次，剩余主题标未评估；
     * 硬超时（软预算 + 预留）到了连汇总也不再调模型，直接用确定性兜底。</p>
     *
     * @return 报告 JSON；超时/失败返回 null（调用方存原文降级，可重试）
     */
    public String generateReport(ApCodingInterview record) {
        long budgetMs = Math.max(5, reportTimeoutSeconds) * 1000L;
        long softDeadline = System.currentTimeMillis() + Math.max(1_000L, budgetMs - REPORT_SUMMARY_RESERVE_MS);
        if (aiSseExecutor == null) {
            return buildReportJson(record, softDeadline);
        }
        try {
            // 单位必须是 MILLISECONDS：budgetMs 由「秒 × 1000」得出，这里若写成 SECONDS，
            // 45s 会变成 45000s —— 外层硬超时形同失效，单次 LLM 挂起会长期占住 aiSseExecutor 线程。
            return CompletableFuture.supplyAsync(() -> buildReportJson(record, softDeadline), aiSseExecutor)
                .get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[CodingInterview] 报告生成超时（{}s），按结构化失败降级: interviewId={}",
                reportTimeoutSeconds, record.getId());
            return null;
        } catch (Exception e) {
            log.warn("[CodingInterview] 报告生成失败: interviewId={}, err={}", record.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 解析报告 JSON（模型输出容错：剥离代码块/前后缀文本）；失败返回 null（调用方存原文降级）。
     *
     * <p>服务端规范化：考点清单缺失补空列表、等级夹取 1-5、覆盖度等级按
     * covered/(covered+missing) 比例映射（不信任模型自评的覆盖分）；
     * 「未评估」占位主题不参与等级与覆盖度计算。</p>
     */
    public ReportData parseReport(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            return null;
        }
        ReportData data;
        try {
            data = CodingInterviewJson.mapper().readValue(json, ReportData.class);
        } catch (Exception e) {
            log.warn("[CodingInterview] 报告 JSON 解析失败（存原文降级）: {}", e.getMessage());
            return null;
        }
        if (data == null || data.items == null || data.items.isEmpty()) {
            return null;
        }
        for (ReportItemData item : data.items) {
            if (item == null) {
                return null;
            }
            if (isPending(item)) {
                // 未评估占位：等级与覆盖度留空（既不虚高也不拉低均值）
                item.structure = null;
                item.accuracy = null;
                item.coverageScore = null;
                if (item.coverage == null) {
                    item.coverage = new CoverageData();
                }
                if (item.coverage.covered == null) {
                    item.coverage.covered = new ArrayList<>();
                }
                if (item.coverage.missing == null) {
                    item.coverage.missing = new ArrayList<>();
                }
                if (isBlank(item.comment)) {
                    item.comment = PENDING_COMMENT;
                }
                continue;
            }
            normalizeItem(item);
        }
        if (data.suggestions == null) {
            data.suggestions = new ArrayList<>();
        }
        return data;
    }

    /** 报告是否可用（结构化可解析）：finish 幂等回放/重试的判据 */
    public boolean hasUsableReport(ApCodingInterview record) {
        return parseReport(record.getReport()) != null;
    }

    /**
     * 综合等级 = 三维均值四舍五入（跳过「未评估」占位；仅供档案块与列表展示，不给百分制总分）。
     *
     * <p>未评估的主题既不虚高也不误伤 —— 直接把占位当 1 分会平白拉低整体，当 3 分又是虚高。</p>
     */
    public int computeOverallScore(ReportData report) {
        double sum = 0;
        int count = 0;
        for (ReportItemData item : report.items) {
            if (isPending(item) || item.structure == null || item.coverageScore == null || item.accuracy == null) {
                continue;
            }
            sum += item.structure + item.coverageScore + item.accuracy;
            count += 3;
        }
        if (count == 0) {
            // 理论不可达：全批失败不会产出结构化报告（见 buildReportJson），此处仅作除零兜底
            return 1;
        }
        return (int) Math.max(1, Math.min(5, Math.round(sum / count)));
    }

    // ==================== 报告主链路 ====================

    /**
     * 报告主链路：<b>分批逐题评估 → 批内逐位对齐 → 二次汇总</b>，最后拼成一份报告 JSON。
     *
     * @param softDeadline 软预算：超过它不再发起新批次（汇总仍可继续，另有硬超时兜底）
     */
    private String buildReportJson(ApCodingInterview record, long softDeadline) {
        List<PlanTopic> plan = parsePlanList(record.getPlanSnapshot());
        if (plan.isEmpty()) {
            log.warn("[CodingInterview] 提纲缺失，报告无法生成: interviewId={}", record.getId());
            return null;
        }
        List<TurnRecord> turns = parseTurns(record.getTurns());
        int batchSize = Math.max(1, reportBatchSize);
        List<ReportItemData> items = new ArrayList<>(plan.size());
        List<String> failedRaws = new ArrayList<>();
        int evaluated = 0;
        for (int from = 0; from < plan.size(); from += batchSize) {
            int to = Math.min(plan.size(), from + batchSize);
            if (System.currentTimeMillis() > softDeadline) {
                // 预算耗尽：剩余主题一律标未评估，但已经评出来的部分照常出报告（不整场丢）
                log.warn("[CodingInterview] 报告软预算耗尽，剩余主题按未评估处理: interviewId={}, from={}/{}",
                    record.getId(), from, plan.size());
                for (int i = from; i < plan.size(); i++) {
                    items.add(pendingItem(plan.get(i).topic));
                }
                break;
            }
            BatchResult batch = evaluateBatch(record, plan, turns, from, to);
            List<ReportItemData> aligned = alignBatch(batch.items, plan, from, to);
            for (ReportItemData item : aligned) {
                if (!isPending(item)) {
                    evaluated++;
                }
            }
            if (batch.items == null && batch.raw != null && !batch.raw.isBlank()) {
                failedRaws.add(batch.raw);
            }
            items.addAll(aligned);
        }
        if (evaluated == 0) {
            log.warn("[CodingInterview] 报告所有批次均失败，整场降级: interviewId={}, topics={}, batches={}",
                record.getId(), plan.size(), failedRaws.size());
            return failedRaws.isEmpty() ? null : String.join("\n\n", failedRaws);
        }
        Summary summary = summarize(record, items, softDeadline + REPORT_SUMMARY_RESERVE_MS);
        ReportData data = new ReportData();
        data.items = items;
        data.overall = summary.overall;
        data.suggestions = summary.suggestions;
        log.info("[CodingInterview] 报告生成完成: interviewId={}, 主题={}, 已评估={}, 失败批次={}",
            record.getId(), plan.size(), evaluated, failedRaws.size());
        return CodingJudge.writeJson(data);
    }

    /** 评估一批主题（段内不截断，只做防注入净化）；items 为 null 表示该批不可用 */
    private BatchResult evaluateBatch(ApCodingInterview record, List<PlanTopic> plan,
                                     List<TurnRecord> turns, int from, int to) {
        String system = resolvePrompt(PROMPT_KEY_REPORT_TOPIC, FALLBACK_REPORT_TOPIC_SYSTEM, record.getUserId());
        String userPrompt = buildTopicPrompt(record, plan, turns, from, to);
        String raw = aiLlmGateway.generateOrNull(AiFeatures.INTERVIEW_REPORT, system, userPrompt, null, null);
        BatchResult result = new BatchResult();
        result.raw = raw;
        result.items = parseReportItems(raw);
        if (result.items == null) {
            log.warn("[CodingInterview] 分批评估失败（该批主题补未评估占位）: interviewId={}, from={}, to={}, rawLen={}",
                record.getId(), from, to, raw == null ? 0 : raw.length());
        }
        return result;
    }

    /**
     * 批内逐位对齐：按「主题下标 - 批起始下标」取模型返回的第 n 条，缺失补「未评估」占位。
     *
     * <p><b>不能用 addAll</b>：模型少返一条时后续 item 会整体前移，题号与点评就错位了。</p>
     */
    private static List<ReportItemData> alignBatch(List<ReportItemData> batch, List<PlanTopic> plan,
                                                   int from, int to) {
        List<ReportItemData> aligned = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            int offset = i - from;
            ReportItemData item = batch != null && offset < batch.size() ? batch.get(offset) : null;
            if (item == null) {
                aligned.add(pendingItem(plan.get(i).topic));
                continue;
            }
            if (isBlank(item.topic)) {
                item.topic = plan.get(i).topic;
            }
            normalizeItem(item);
            aligned.add(item);
        }
        return aligned;
    }

    /** 未评估占位：不计入综合等级，也不虚高（与"答得差"区分开） */
    private static ReportItemData pendingItem(String topic) {
        ReportItemData item = new ReportItemData();
        item.topic = topic == null ? "" : topic;
        item.pending = Boolean.TRUE;
        item.coverage = new CoverageData();
        item.comment = PENDING_COMMENT;
        return item;
    }

    private static boolean isPending(ReportItemData item) {
        return item != null && Boolean.TRUE.equals(item.pending);
    }

    /**
     * 解析一批评估结果。模型偶尔会包一层 {@code {"items":[...]}}（与旧的整场报告同构），两种形态都认。
     * 返回 null 表示该批不可用。
     */
    private List<ReportItemData> parseReportItems(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<ReportItemData> items = readItems(extractJsonArray(raw), true);
        if (items == null) {
            items = readItems(extractJsonObject(raw), false);
        }
        return items;
    }

    private List<ReportItemData> readItems(String json, boolean asArray) {
        if (json == null) {
            return null;
        }
        try {
            List<ReportItemData> items;
            if (asArray) {
                items = CodingInterviewJson.mapper().readValue(json, new TypeReference<List<ReportItemData>>() {});
            } else {
                ReportData data = CodingInterviewJson.mapper().readValue(json, ReportData.class);
                items = data == null ? null : data.items;
            }
            return items == null || items.isEmpty() ? null : items;
        } catch (Exception e) {
            log.debug("[CodingInterview] 分批评估 JSON 解析未命中: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 二次汇总：输入是各主题<b>结论摘要</b>（等级 + 覆盖 + 点评摘要），不是原始对话。
     * 汇总失败、超预算或不可用时退回确定性兜底（不抛异常，保证报告一定有总评与建议）。
     *
     * @param hardDeadline 硬超时：超过它连汇总也不再调模型（分批若吃光预算，这一步必须还能兜住）
     */
    private Summary summarize(ApCodingInterview record, List<ReportItemData> items, long hardDeadline) {
        Summary fallback = fallbackSummary(items);
        if (System.currentTimeMillis() > hardDeadline) {
            log.warn("[CodingInterview] 报告硬超时已到，汇总改用各主题结论兜底: interviewId={}", record.getId());
            return fallback;
        }
        String system = resolvePrompt(PROMPT_KEY_REPORT, FALLBACK_REPORT_SYSTEM, record.getUserId());
        String userPrompt = buildSummaryUserPrompt(record, items);
        String raw = aiLlmGateway.generateOrNull(AiFeatures.INTERVIEW_REPORT, system, userPrompt, null, null);
        Summary parsed = parseSummary(raw);
        if (parsed == null) {
            log.warn("[CodingInterview] 二次汇总失败，改用各主题结论兜底: interviewId={}", record.getId());
            return fallback;
        }
        if (parsed.suggestions == null || parsed.suggestions.isEmpty()) {
            // 建议不可缺（前端整块依赖它），模型漏给时用兜底建议补齐
            parsed.suggestions = fallback.suggestions;
        }
        return parsed;
    }

    private Summary parseSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            return null;
        }
        Summary summary;
        try {
            summary = CodingInterviewJson.mapper().readValue(json, Summary.class);
        } catch (Exception e) {
            log.warn("[CodingInterview] 汇总 JSON 解析失败: {}", e.getMessage());
            return null;
        }
        if (summary == null || isBlank(summary.overall)) {
            return null;
        }
        if (summary.suggestions == null) {
            summary.suggestions = new ArrayList<>();
        }
        return summary;
    }

    /**
     * 汇总兜底：完全不依赖模型，用各主题结论确定性拼装。
     *
     * <p>顺带把各主题的「未覆盖考点」直接转成建议 —— 这是服务端本来就掌握的信息，
     * 汇总 LLM 挂了也不至于让用户拿不到可执行结论。</p>
     */
    private static Summary fallbackSummary(List<ReportItemData> items) {
        Summary summary = new Summary();
        List<String> levels = new ArrayList<>();
        List<String> missingAll = new ArrayList<>();
        for (ReportItemData item : items) {
            if (isPending(item)) {
                continue;
            }
            int level = (int) Math.round((item.structure + item.coverageScore + item.accuracy) / 3.0);
            levels.add(item.topic + " " + level + "/5");
            collectMissing(item, missingAll);
        }
        summary.overall = "本次共 " + items.size() + " 个主题，已完成评估 " + levels.size() + " 个。"
            + "各主题综合等级：" + String.join("，", levels)
            + "。逐题依据见上方各主题点评，建议优先补强下方列出的考点。";
        summary.suggestions = suggestionsFromMissing(missingAll);
        return summary;
    }

    /** 未覆盖考点 → 可执行建议（去重、限 5 条；一条都没有时给通用建议） */
    private static List<String> suggestionsFromMissing(List<String> missingAll) {
        List<String> tips = new ArrayList<>();
        for (String missing : missingAll) {
            if (tips.size() >= 5) {
                break;
            }
            tips.add("补强「" + missing + "」：结合自己的项目讲清原理、边界与取舍");
        }
        if (tips.isEmpty()) {
            tips.add("对照提纲考点复盘本次没展开的部分，补齐原理与真实场景细节");
        }
        return tips;
    }

    private static void collectMissing(ReportItemData item, List<String> target) {
        if (item.coverage == null || item.coverage.missing == null) {
            return;
        }
        for (String missing : item.coverage.missing) {
            if (!isBlank(missing) && !target.contains(missing)) {
                target.add(missing);
            }
        }
    }

    // ==================== prompt 组装 ====================

    /** 分批评估 prompt：本批主题的关键考点 + 该主题下的完整问答（段内不截断） */
    private String buildTopicPrompt(ApCodingInterview record, List<PlanTopic> plan,
                                    List<TurnRecord> turns, int from, int to) {
        StringBuilder sb = new StringBuilder();
        sb.append("【面试方向】").append(promptSanitizer.sanitize(record.getDirection())).append('\n');
        sb.append("【难度】").append(difficultyLabel(nvl(record.getDifficulty()))).append('\n');
        sb.append("【本批需评估的主题（共 ").append(to - from)
            .append(" 个，请按相同顺序输出相同数量的元素）】\n");
        for (int i = from; i < to; i++) {
            PlanTopic topic = plan.get(i);
            sb.append(i - from + 1).append(". 主题：").append(topic.topic).append('\n');
            if (topic.keyPoints != null && !topic.keyPoints.isEmpty()) {
                sb.append("   关键考点：").append(String.join("、", topic.keyPoints)).append('\n');
            }
            sb.append("   问答实录：\n").append(topicDialogue(turns, i)).append('\n');
        }
        sb.append("请输出这一批主题的评估结果（JSON 数组）。");
        return sb.toString();
    }

    /** 汇总 prompt：只喂各主题结论摘要（题干/点评都截断），不喂原始对话 */
    private String buildSummaryUserPrompt(ApCodingInterview record, List<ReportItemData> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("【面试方向】").append(promptSanitizer.sanitize(record.getDirection())).append('\n');
        sb.append("【难度】").append(difficultyLabel(nvl(record.getDifficulty()))).append('\n');
        sb.append("【主题评估结论】\n");
        List<String> missingAll = new ArrayList<>();
        List<String> pendingTopics = new ArrayList<>();
        for (ReportItemData item : items) {
            if (isPending(item)) {
                pendingTopics.add(item.topic);
                continue;
            }
            sb.append("- ").append(item.topic)
                .append("：回答结构 ").append(item.structure).append("，考点覆盖 ").append(item.coverageScore)
                .append("，技术准确性 ").append(item.accuracy)
                .append("；点评：").append(truncate(item.comment, HIGHLIGHT_COMMENT_CHARS)).append('\n');
            collectMissing(item, missingAll);
        }
        sb.append("【待补强考点】").append(missingAll.isEmpty() ? "无" : String.join("、", missingAll)).append('\n');
        sb.append("【未完成评估的主题】")
            .append(pendingTopics.isEmpty() ? "无" : String.join("、", pendingTopics)).append('\n');
        sb.append("请输出总评与改进建议（JSON 对象）。");
        return sb.toString();
    }

    /** 单个主题下的问答实录（含追问）；候选人内容净化，超长按主题累计上限兜底截断 */
    private String topicDialogue(List<TurnRecord> turns, int topicIndex) {
        StringBuilder sb = new StringBuilder();
        for (TurnRecord turn : turns) {
            if (turn == null || turn.topicIndex == null || !turn.topicIndex.equals(topicIndex)) {
                continue;
            }
            appendDialogueLine(sb, turn, 0, promptSanitizer);
        }
        if (sb.length() == 0) {
            return "（该主题无作答记录）";
        }
        return truncate(sb.toString().trim(), REPORT_TOPIC_KEEP);
    }

    /** prompt 解析：注册表（灰度）→ 代码兜底；注册表缺失/异常一律 fail-open */
    private String resolvePrompt(String key, String fallback, Integer userId) {
        if (promptRegistry == null) {
            return fallback;
        }
        try {
            AiPromptRegistry.ResolvedPrompt resolved = promptRegistry.resolve(key, fallback, userId);
            return resolved == null || resolved.content == null || resolved.content.isBlank()
                ? fallback : resolved.content;
        } catch (Exception e) {
            log.warn("[CodingInterview] prompt 解析失败，走代码兜底: key={}, err={}", key, e.getMessage());
            return fallback;
        }
    }

    // ==================== 规范化 / 评分口径 ====================

    /** item 规范化：清单补空、等级夹取、覆盖度等级服务端重算（对模型输出与读库两条路径同口径） */
    private static void normalizeItem(ReportItemData item) {
        if (item.coverage == null) {
            item.coverage = new CoverageData();
        }
        if (item.coverage.covered == null) {
            item.coverage.covered = new ArrayList<>();
        }
        if (item.coverage.missing == null) {
            item.coverage.missing = new ArrayList<>();
        }
        item.structure = clampLevel(item.structure);
        item.accuracy = clampLevel(item.accuracy);
        item.coverageScore = coverageLevel(item.coverage.covered.size(), item.coverage.missing.size());
        if (item.topic == null) {
            item.topic = "";
        }
        if (item.comment == null) {
            item.comment = "";
        }
        item.pending = Boolean.FALSE;
    }

    /** 等级夹取 1-5；缺失按 3（基本合格）中性处理（模型未给等级时不虚高也不误伤） */
    private static int clampLevel(Integer level) {
        return level == null ? 3 : Math.max(1, Math.min(5, level));
    }

    /** 覆盖度等级：covered/(covered+missing) 比例映射 1-5（覆盖度先行，无考点信息从低） */
    private static int coverageLevel(int covered, int missing) {
        int total = covered + missing;
        if (total <= 0) {
            return 1;
        }
        double ratio = covered * 1.0 / total;
        if (ratio >= 0.8) {
            return 5;
        }
        if (ratio >= 0.6) {
            return 4;
        }
        if (ratio >= 0.4) {
            return 3;
        }
        if (ratio >= 0.2) {
            return 2;
        }
        return 1;
    }

    // ==================== 内部结构 ====================

    /** 报告汇总结果（二次汇总产出；失败时由各主题结论确定性拼装） */
    private static class Summary {
        public String overall;
        public List<String> suggestions;
    }

    /** 一批主题的评估结果（items 为 null 表示该批不可用，raw 留作整场降级展示） */
    private static final class BatchResult {
        List<ReportItemData> items;
        String raw;
    }
}
