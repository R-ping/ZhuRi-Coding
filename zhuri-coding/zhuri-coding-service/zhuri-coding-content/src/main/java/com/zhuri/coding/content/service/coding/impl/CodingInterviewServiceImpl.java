package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.common.bailian.PromptSanitizer;
import com.zhuri.coding.content.mapper.coding.ApCodingInterviewMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.AiPromptRegistry;
import com.zhuri.coding.content.service.ai.AiQuotaService;
import com.zhuri.coding.content.service.coding.CodingInterviewService;
import com.zhuri.coding.content.service.coding.CodingJudge;
import com.zhuri.coding.model.coding.dtos.CodingInterviewFinishDTO;
import com.zhuri.coding.model.coding.dtos.CodingInterviewStartDTO;
import com.zhuri.coding.model.coding.dtos.CodingInterviewTurnDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingInterview;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingInterviewFinishVO;
import com.zhuri.coding.model.coding.vos.CodingInterviewHistoryVO;
import com.zhuri.coding.model.coding.vos.CodingInterviewReportVO;
import com.zhuri.coding.model.coding.vos.CodingInterviewSessionVO;
import com.zhuri.coding.model.coding.vos.CodingInterviewTurnVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 模拟面试服务实现（Coding 延展第三层 · Stage A）
 *
 * <p><b>提纲生成（开面一次）</b>：方向 + 素材池标签（题库分布）+ 用户弱项 → LLM 输出
 * JSON 提纲（主题/主问题/keyPoints 关键考点）；校验不合格直接拒绝开面（宁缺勿假，不落库不扣费）。</p>
 *
 * <p><b>轮次编排（控制行协议）</b>：每轮要求模型首行输出 FOLLOWUP/NEXT——服务端缓冲首行
 * （5s 超时保护，超时按 FOLLOWUP 整段转发），FOLLOWUP 逐段转发、NEXT 检测到即中止模型流
 * （省 token）并由服务端拼装下一题；追问上限由服务端强制（不依赖模型自觉）；
 * 主题耗尽 done 事件 completed=true。</p>
 *
 * <p><b>防重与幂等</b>：turnSeq 与 DB turn_count 校验 + 原子 UPDATE
 * （{@code where id=? and status=1 and turn_count=?}）；finish 原子占位、重复 finish 幂等回放；
 * 报告解析失败/超时存原文并 reportReady=false（重试走 finish 幂等回放，可恢复）。</p>
 *
 * <p><b>额度</b>：开面 {@code tryConsume} 预检；每轮/报告由 {@link AiLlmGateway} 自动按 token 结算；
 * 流式耗尽经 {@code QuotaExhaustedException} 上抛由控制器转 {@code [3301]} 事件。</p>
 */
@Slf4j
@Service
public class CodingInterviewServiceImpl implements CodingInterviewService {

    // ==================== 常量 ====================

    /** 控制行：追问 */
    static final String CONTROL_FOLLOWUP = "FOLLOWUP";
    /** 控制行：换下一题 */
    static final String CONTROL_NEXT = "NEXT";
    /** 控制行缓冲超时（毫秒）：超时仍未见换行则整段按 FOLLOWUP 转发（协议未被执行时不吞内容） */
    private static final long CONTROL_LINE_TIMEOUT_MS = 5_000L;
    /** 提纲最低可用主题数（不足拒绝开面；题量配置更小时以题量为准） */
    private static final int PLAN_MIN_TOPICS = 3;
    /** 轮次上下文窗口（对话消息条数，含历史，约 4 轮往返） */
    private static final int TURN_WINDOW_MESSAGES = 8;
    /** 轮次窗口内单条消息截断长度（字符，防历史追问上下文膨胀） */
    private static final int WINDOW_TRUNCATE = 300;
    /** 报告输入中单条消息截断长度（字符，答案要多保留，取 1200） */
    private static final int REPORT_TRUNCATE = 1_200;
    /** 作答文本长度上限（字符，防 prompt 成本失控） */
    private static final int ANSWER_MAX_LENGTH = 2_000;
    /** 方向长度上限（DB 列 64） */
    private static final int DIRECTION_MAX_LENGTH = 64;
    /** 换题过渡语 */
    private static final String NEXT_PREFIX = "我们换个话题：";
    /** 全部主题答完后的收尾语 */
    private static final String CLOSING_TEXT = "好的，本次面试的问题已经聊完了，点击「结束面试」即可查看你的面试报告。";
    /** 弱项领域取前 N 个（与测评组卷同口径：tag_stats 正确率升序） */
    private static final int WEAK_TAG_LIMIT = 5;
    /** 素材标签取前 N 个 */
    private static final int MATERIAL_TAG_LIMIT = 10;
    /** 素材池取样条数 */
    private static final int MATERIAL_POOL_SIZE = 100;

    /** prompt 注册表 key（DB 可按需灰度覆盖） */
    static final String PROMPT_KEY_PLAN = "coding_interview_plan";
    static final String PROMPT_KEY_TURN = "coding_interview_turn";
    static final String PROMPT_KEY_REPORT = "coding_interview_report";

    /** 提纲生成 system（代码兜底） */
    static final String FALLBACK_PLAN_SYSTEM = String.join("\n",
        "你是资深技术面试官，负责为一场模拟面试生成提纲。",
        "你必须只输出一个 JSON 数组，不能有任何解释文字、markdown 代码块或多余符号。",
        "数组元素格式：",
        "{\"topic\":\"主题名\",\"mainQuestion\":\"主问题\",\"keyPoints\":[\"考点1\",\"考点2\",\"考点3\"],\"tag\":\"技术标签\"}",
        "生成要求：",
        "1. 主题数量严格按用户消息中「主题数量」的要求；",
        "2. 每个主题 1 个开放式主问题（可展开回答 3-5 分钟，不要选择题、不要是非题）；",
        "3. 每个主题给出 3-5 个关键考点 keyPoints，它们是判断回答覆盖度的锚点，必须具体、可判定；",
        "4. 难度与方向以用户消息为准；",
        "5. 只输出 JSON 数组本身。");

    /** 逐轮判定 system（代码兜底；首行控制行协议） */
    static final String FALLBACK_TURN_SYSTEM = String.join("\n",
        "你是资深技术面试官，正在进行一场文字模拟面试，对方是候选人。",
        "你刚收到候选人对当前问题的回答，需要决定：追问（FOLLOWUP）还是换下一题（NEXT）。",
        "输出协议（必须严格遵守）：",
        "1. 第一行只能是 FOLLOWUP 或 NEXT（大写，独占一行，不要带任何其它字符）；",
        "2. 若第一行是 FOLLOWUP，从第二行开始写你的追问（口语化、具体，1-3 句）；",
        "3. 若第一行是 NEXT，后面可以不写内容，系统会直接给出下一题。",
        "判定原则：",
        "- 回答含糊、泛泛而谈、缺少关键考点的支撑细节，或存在疑点 → FOLLOWUP，针对疑点追问一个具体问题；",
        "- 回答完整、清晰、覆盖关键考点 → NEXT；",
        "- 不要点评对错，不要总结，不要鼓励式套话；像真实面试官一样简短直接；",
        "- 追问要基于候选人的回答内容，不要复述题干。");

    /** 报告生成 system（代码兜底） */
    static final String FALLBACK_REPORT_SYSTEM = String.join("\n",
        "你是资深技术面试官，面试已结束，请基于面试提纲与完整对话输出面试报告。",
        "你必须只输出一个 JSON 对象，不能有 markdown 代码块或解释文字：",
        "{\"items\":[{\"topic\":\"主题名\",\"structure\":1,\"coverage\":{\"covered\":[\"已覆盖考点\"],\"missing\":[\"未覆盖考点\"]},\"accuracy\":1,\"comment\":\"点评\"}],\"overall\":\"总评\",\"suggestions\":[\"改进建议\"]}",
        "评分纪律：",
        "1. 覆盖度先行：逐项对照提纲中该主题的关键考点，判断是否被候选人讲到，考点的原文对齐到 covered/missing；",
        "2. structure（回答结构）与 accuracy（技术准确性）都给 1-5 整数等级：1=几乎空白/错误，2=明显缺失，3=基本合格，4=良好，5=优秀；没有把握时从低；",
        "3. comment 必须给出依据：引用候选人的原话要点或指出其遗漏的考点，不许空泛评价；",
        "4. overall 是整体结论（优势与短板，3-4 句）；suggestions 给 3-5 条可执行的改进建议；",
        "5. 候选人未作答的主题也要输出 item：covered 为空、comment 说明「未作答」；",
        "6. 只评价技术内容与表达，不评价人格，不编造候选人没说过的内容。");

    // ==================== 配置 ====================

    /** 主题数（每主题 1 主问题 + ≤followup-max 次追问） */
    @Value("${app.coding.interview.question-count:5}")
    private int questionCount = 5;

    /** 每主题最大追问次数（服务端强制；0=不追问） */
    @Value("${app.coding.interview.followup-max:1}")
    private int followupMax = 1;

    /** 总限时（分钟） */
    @Value("${app.coding.interview.duration-minutes:45}")
    private int durationMinutes = 45;

    /** 每日场次上限（0=不限；进行中与已完成计入，已过期不计） */
    @Value("${app.coding.interview.daily-limit:3}")
    private int dailyLimit = 3;

    /** 报告生成超时（秒；超时按结构化失败降级，可重试） */
    @Value("${app.coding.interview.report-timeout-seconds:30}")
    private int reportTimeoutSeconds = 30;

    // ==================== 依赖 ====================

    @Autowired
    private ApCodingInterviewMapper interviewMapper;

    @Autowired
    private ApCodingQuestionMapper questionMapper;

    @Autowired
    private ApCodingUserStatMapper statMapper;

    @Autowired
    private AiLlmGateway aiLlmGateway;

    /** 配额准入（可空注入：单测上下文跳过） */
    @Autowired(required = false)
    private AiQuotaService quotaService;

    /** prompt 注册表（可空注入：单测/未装配时走代码兜底） */
    @Autowired(required = false)
    private AiPromptRegistry promptRegistry;

    @Autowired
    private PromptSanitizer promptSanitizer;

    /** SSE 执行池（报告超时保护用；单测/未装配时同步直调） */
    @Autowired(required = false)
    @Qualifier("aiSseExecutor")
    private Executor aiSseExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 开面 / 续答 ====================

    @Override
    public ResponseResult start(Integer userId, CodingInterviewStartDTO dto) {
        String direction = dto == null || dto.getDirection() == null ? "" : dto.getDirection().trim();
        if (direction.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请选择或输入面试方向");
        }
        if (direction.length() > DIRECTION_MAX_LENGTH) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "面试方向最多 " + DIRECTION_MAX_LENGTH + " 字");
        }
        int difficulty = dto.getDifficulty() == null ? 2 : Math.max(1, Math.min(3, dto.getDifficulty()));
        Date now = new Date();

        // 1. 单场并发：进行中且未超时直接续答（deadline 不变、不重新生成提纲）；已超时懒置过期
        ApCodingInterview ongoing = interviewMapper.selectOngoing(userId);
        if (ongoing != null) {
            if (ongoing.getDeadlineTime() != null && now.before(ongoing.getDeadlineTime())) {
                return ResponseResult.okResult(toSessionVO(ongoing, now));
            }
            expireOngoing(ongoing.getId());
        }

        // 2. 每日场次上限（0=不限；已过期不计，避免被超时锁死）
        if (dailyLimit > 0) {
            long today = interviewMapper.countToday(userId, startOfDay());
            if (today >= dailyLimit) {
                return ResponseResult.errorResult(400,
                    "今日模拟面试已达上限（" + dailyLimit + " 场），明天再来吧");
            }
        }

        // 3. 额度预检（免费 token 优先 → 钱包兜底；不足不放行，避免白跑一次提纲 LLM）
        if (quotaService != null && !quotaService.tryConsume(userId)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.AI_QUOTA_EXHAUSTED);
        }

        // 4. 素材池标签 + 用户弱项（标签不足自然降级为纯方向生成，不阻断）
        List<String> materialTags = materialTags(direction);
        List<String> weakTags = weakTags(userId);

        // 5. 提纲生成（同步 LLM；失败/不合格拒绝开面——不落库、不产生脏数据）
        String system = resolvePrompt(PROMPT_KEY_PLAN, FALLBACK_PLAN_SYSTEM, userId);
        String userPrompt = buildPlanUserPrompt(direction, difficultyLabel(difficulty), materialTags, weakTags);
        String raw = aiLlmGateway.generateOrNull(AiFeatures.INTERVIEW_PLAN, system, userPrompt, null, null);
        List<PlanTopic> plan = parsePlan(raw);
        int minTopics = Math.min(PLAN_MIN_TOPICS, Math.max(1, questionCount));
        if (plan == null || plan.size() < minTopics) {
            log.warn("[CodingInterview] 提纲生成失败或不合格，拒绝开面: userId={}, rawLen={}",
                userId, raw == null ? 0 : raw.length());
            return ResponseResult.errorResult(500, "面试提纲生成失败，请稍后重试");
        }
        if (plan.size() > questionCount) {
            plan = new ArrayList<>(plan.subList(0, questionCount));
        }

        // 6. 落库：turns 预置首题（提纲含 keyPoints，仅服务端可见，不下发）
        ApCodingInterview record = new ApCodingInterview();
        record.setUserId(userId);
        record.setStatus(ApCodingInterview.STATUS_ONGOING);
        record.setDirection(direction);
        record.setDifficulty(difficulty);
        record.setPlanSnapshot(CodingJudge.writeJson(plan));
        record.setTurns(CodingJudge.writeJson(java.util.Collections.singletonList(
            interviewerTurn("question", plan.get(0).mainQuestion, 0, now))));
        record.setCurrentIndex(0);
        record.setFollowupCount(0);
        record.setTurnCount(0);
        record.setStartedTime(now);
        record.setDeadlineTime(new Date(now.getTime() + durationMinutes * 60_000L));
        record.setLastActiveTime(now);
        interviewMapper.insert(record);

        return ResponseResult.okResult(toSessionVO(record, now));
    }

    @Override
    public ResponseResult current(Integer userId) {
        ApCodingInterview ongoing = interviewMapper.selectOngoing(userId);
        if (ongoing == null) {
            return ResponseResult.okResult(null);
        }
        Date now = new Date();
        if (ongoing.getDeadlineTime() == null || now.after(ongoing.getDeadlineTime())) {
            expireOngoing(ongoing.getId());
            return ResponseResult.okResult(null);
        }
        return ResponseResult.okResult(toSessionVO(ongoing, now));
    }

    // ==================== 轮次（SSE） ====================

    @Override
    public void turnStream(Integer userId, CodingInterviewTurnDTO dto, TurnSink sink) {
        // ---- 入参与状态校验（同步、无 LLM 成本） ----
        if (dto == null || dto.getInterviewId() == null || dto.getTurnSeq() == null) {
            sink.error("面试参数缺失，请刷新后重试");
            return;
        }
        String answer = dto.getAnswer() == null ? "" : dto.getAnswer().trim();
        if (answer.isEmpty()) {
            sink.error("回答不能为空");
            return;
        }
        if (answer.length() > ANSWER_MAX_LENGTH) {
            sink.error("回答过长（最多 " + ANSWER_MAX_LENGTH + " 字）");
            return;
        }
        ApCodingInterview record = interviewMapper.selectById(dto.getInterviewId());
        if (record == null || !userId.equals(record.getUserId())) {
            sink.error("面试不存在");
            return;
        }
        if (isStatus(record, ApCodingInterview.STATUS_FINISHED)) {
            sink.error("面试已结束，请查看面试报告");
            return;
        }
        if (isStatus(record, ApCodingInterview.STATUS_EXPIRED)) {
            sink.error("面试已过期，请重新开面");
            return;
        }
        Date now = new Date();
        if (record.getDeadlineTime() != null && !now.before(record.getDeadlineTime())) {
            expireOngoing(record.getId());
            sink.error("面试已超时，请重新开面");
            return;
        }
        if (!dto.getTurnSeq().equals(nvl(record.getTurnCount()))) {
            sink.error("面试状态已变化，请刷新后重试");
            return;
        }
        List<PlanTopic> plan = parsePlanList(record.getPlanSnapshot());
        if (plan.isEmpty()) {
            sink.error("面试提纲数据异常，请重新开面");
            return;
        }
        int currentIndex = Math.max(0, Math.min(nvl(record.getCurrentIndex()), plan.size() - 1));

        // ---- 生成本轮面试官发言：追问上限由服务端强制（不依赖模型自觉） ----
        boolean followupAllowed = followupMax > 0 && nvl(record.getFollowupCount()) < followupMax;
        TurnOutcome outcome;
        if (!followupAllowed) {
            outcome = nextOutcome(plan, currentIndex);
        } else {
            outcome = generateTurn(record, plan, currentIndex, answer, sink);
            if (outcome == null) {
                // LLM 降级：本轮不落库（前端可重试，不产生脏数据）
                sink.error("面试官暂时离线，请重试");
                return;
            }
        }
        // NEXT 分支为服务端拼装文本，未经过流式转发，此处一次性下发
        if ("next".equals(outcome.kind)) {
            sink.delta(outcome.text);
        }

        // ---- 原子落库（turnSeq 防重：双击/重放会导致 rows==0） ----
        List<TurnRecord> turns = parseTurns(record.getTurns());
        turns.add(userTurn(answer, currentIndex, now));
        turns.add(interviewerTurn("followup".equals(outcome.kind) ? "followup" : "question",
            outcome.text, outcome.topicIndex, now));
        ApCodingInterview update = new ApCodingInterview();
        update.setTurns(CodingJudge.writeJson(turns));
        update.setTurnCount(nvl(record.getTurnCount()) + 1);
        update.setCurrentIndex(outcome.topicIndex);
        update.setFollowupCount("followup".equals(outcome.kind) ? nvl(record.getFollowupCount()) + 1 : 0);
        update.setLastActiveTime(new Date());
        int rows = interviewMapper.update(update, new LambdaUpdateWrapper<ApCodingInterview>()
            .eq(ApCodingInterview::getId, record.getId())
            .eq(ApCodingInterview::getStatus, ApCodingInterview.STATUS_ONGOING)
            .eq(ApCodingInterview::getTurnCount, dto.getTurnSeq()));
        if (rows == 0) {
            sink.error("面试状态已变化，请刷新后重试");
            return;
        }

        // ---- done：进度 + 完整发言 ----
        CodingInterviewTurnVO vo = new CodingInterviewTurnVO();
        vo.setText(outcome.text);
        vo.setKind(outcome.kind);
        vo.setTopicIndex(outcome.topicIndex);
        vo.setTurnCount(nvl(record.getTurnCount()) + 1);
        vo.setCompleted(outcome.completed);
        sink.done(vo);
    }

    /**
     * 调用 LLM 生成一轮发言（控制行协议）；返回 null 表示降级（本轮不落库）。
     *
     * <p>FOLLOWUP：缓冲区判定后逐段转发；NEXT：检测到控制行即中止模型流（省 token），
     * 由服务端拼装下一题（不额外生成）。</p>
     */
    private TurnOutcome generateTurn(ApCodingInterview record, List<PlanTopic> plan, int currentIndex,
                                     String answer, TurnSink sink) {
        String system = resolvePrompt(PROMPT_KEY_TURN, FALLBACK_TURN_SYSTEM, record.getUserId());
        String userPrompt = buildTurnUserPrompt(record, plan, currentIndex, answer);
        ControlStream stream = new ControlStream(sink);
        String text = aiLlmGateway.generateStreamOrNull(AiFeatures.INTERVIEW_TURN, system, userPrompt,
            null, null, stream::onDelta);
        if (stream.nextDetected) {
            return nextOutcome(plan, currentIndex);
        }
        if (text == null) {
            return null;
        }
        ControlDecision decision = stream.decisionOrDecide(text);
        if (decision.next) {
            return nextOutcome(plan, currentIndex);
        }
        String followupText = decision.text == null ? "" : decision.text.trim();
        if (followupText.isEmpty()) {
            // FOLLOWUP 但无有效文本：兜底走下一题（不产生空发言）
            return nextOutcome(plan, currentIndex);
        }
        TurnOutcome outcome = new TurnOutcome();
        outcome.kind = "followup";
        outcome.text = followupText;
        outcome.topicIndex = currentIndex;
        outcome.completed = false;
        return outcome;
    }

    /** 服务端拼装的换题发言：有下一主题则过渡语 + 主问题；主题耗尽则收尾语 + completed */
    private TurnOutcome nextOutcome(List<PlanTopic> plan, int currentIndex) {
        TurnOutcome outcome = new TurnOutcome();
        outcome.kind = "next";
        int nextIndex = currentIndex + 1;
        if (nextIndex >= plan.size()) {
            outcome.completed = true;
            outcome.topicIndex = plan.size() - 1;
            outcome.text = CLOSING_TEXT;
        } else {
            outcome.completed = false;
            outcome.topicIndex = nextIndex;
            outcome.text = NEXT_PREFIX + plan.get(nextIndex).mainQuestion;
        }
        return outcome;
    }

    // ==================== 结束与报告 ====================

    @Override
    public ResponseResult finish(Integer userId, CodingInterviewFinishDTO dto) {
        if (dto == null || dto.getInterviewId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "面试ID不能为空");
        }
        ApCodingInterview record = interviewMapper.selectById(dto.getInterviewId());
        if (record == null || !userId.equals(record.getUserId())) {
            return ResponseResult.errorResult(400, "面试不存在");
        }
        if (isStatus(record, ApCodingInterview.STATUS_EXPIRED)) {
            return ResponseResult.errorResult(400, "面试已过期，无法生成报告");
        }
        // 幂等回放：已完成且报告可用 → 直接回放（重复 finish 不重复生成）
        if (isStatus(record, ApCodingInterview.STATUS_FINISHED) && hasUsableReport(record)) {
            return ResponseResult.okResult(toFinishVO(record, true));
        }
        Date now = new Date();
        if (isStatus(record, ApCodingInterview.STATUS_ONGOING)
            && record.getDeadlineTime() != null && now.after(record.getDeadlineTime())) {
            expireOngoing(record.getId());
            return ResponseResult.errorResult(400, "面试已超时，无法生成报告");
        }

        // 生成报告（超时保护；失败/解析失败存原文降级，重试可恢复）
        String raw = generateReportWithTimeout(record);
        ReportData report = parseReport(raw);
        String stored = report == null ? raw : CodingJudge.writeJson(report);
        Integer overallScore = report == null ? null : computeOverallScore(report);
        Date finished = new Date();

        ApCodingInterview update = new ApCodingInterview();
        update.setStatus(ApCodingInterview.STATUS_FINISHED);
        update.setReport(stored);
        update.setOverallScore(overallScore);
        update.setFinishedTime(finished);
        update.setLastActiveTime(finished);
        int expectedStatus = nvl(record.getStatus());
        int rows = interviewMapper.update(update, new LambdaUpdateWrapper<ApCodingInterview>()
            .eq(ApCodingInterview::getId, record.getId())
            .eq(ApCodingInterview::getStatus, expectedStatus));
        if (rows == 0) {
            // 并发结束/过期：回读——已完成则幂等回放，否则拒绝
            ApCodingInterview fresh = interviewMapper.selectById(record.getId());
            if (fresh != null && isStatus(fresh, ApCodingInterview.STATUS_FINISHED) && hasUsableReport(fresh)) {
                return ResponseResult.okResult(toFinishVO(fresh, true));
            }
            if (fresh != null && isStatus(fresh, ApCodingInterview.STATUS_EXPIRED)) {
                return ResponseResult.errorResult(400, "面试已过期，无法生成报告");
            }
            return ResponseResult.errorResult(400, "面试状态已变化，请刷新后重试");
        }

        record.setStatus(ApCodingInterview.STATUS_FINISHED);
        record.setOverallScore(overallScore);
        record.setFinishedTime(finished);
        return ResponseResult.okResult(toFinishVO(record, report != null));
    }

    @Override
    public ResponseResult report(Integer userId, Long id) {
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "面试ID不能为空");
        }
        ApCodingInterview record = interviewMapper.selectById(id);
        if (record == null || !userId.equals(record.getUserId())) {
            return ResponseResult.errorResult(400, "面试不存在");
        }
        return ResponseResult.okResult(toReportVO(record));
    }

    @Override
    public ResponseResult history(Integer userId, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 || size > 50 ? 10 : size;
        IPage<ApCodingInterview> result = interviewMapper.selectPage(new Page<>(p, s),
            new LambdaQueryWrapper<ApCodingInterview>()
                .eq(ApCodingInterview::getUserId, userId)
                .eq(ApCodingInterview::getStatus, ApCodingInterview.STATUS_FINISHED)
                .orderByDesc(ApCodingInterview::getId));
        List<CodingInterviewHistoryVO> list = result.getRecords().stream().map(r -> {
            CodingInterviewHistoryVO vo = new CodingInterviewHistoryVO();
            vo.setInterviewId(r.getId());
            vo.setDirection(r.getDirection());
            vo.setDifficulty(r.getDifficulty());
            vo.setOverallScore(r.getOverallScore());
            vo.setStatus(r.getStatus());
            vo.setTotalTopics(parsePlanList(r.getPlanSnapshot()).size());
            vo.setStartedTime(formatDateTime(r.getStartedTime()));
            vo.setFinishedTime(formatDateTime(r.getFinishedTime()));
            return vo;
        }).collect(Collectors.toList());
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    // ==================== 报告生成 / 解析 ====================

    /** 报告生成（超时保护）：超时返回 null → 按结构化失败降级（report 留空，finish 重试可恢复） */
    private String generateReportWithTimeout(ApCodingInterview record) {
        String system = resolvePrompt(PROMPT_KEY_REPORT, FALLBACK_REPORT_SYSTEM, record.getUserId());
        String userPrompt = buildReportUserPrompt(record);
        if (aiSseExecutor == null) {
            return aiLlmGateway.generateOrNull(AiFeatures.INTERVIEW_REPORT, system, userPrompt, null, null);
        }
        try {
            return CompletableFuture.supplyAsync(() -> aiLlmGateway.generateOrNull(
                    AiFeatures.INTERVIEW_REPORT, system, userPrompt, null, null), aiSseExecutor)
                .get(Math.max(5, reportTimeoutSeconds), TimeUnit.SECONDS);
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
     * covered/(covered+missing) 比例映射（不信任模型自评的覆盖分）。</p>
     */
    private ReportData parseReport(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            return null;
        }
        ReportData data;
        try {
            data = objectMapper.readValue(json, ReportData.class);
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
        }
        if (data.suggestions == null) {
            data.suggestions = new ArrayList<>();
        }
        return data;
    }

    /** 报告是否可用（结构化可解析）：finish 幂等回放/重试的判据 */
    private boolean hasUsableReport(ApCodingInterview record) {
        return parseReport(record.getReport()) != null;
    }

    /** 综合等级 = 三维均值四舍五入（仅供档案块与列表展示；不给百分制总分） */
    private static int computeOverallScore(ReportData report) {
        double sum = 0;
        int count = 0;
        for (ReportItemData item : report.items) {
            sum += item.structure + item.coverageScore + item.accuracy;
            count += 3;
        }
        if (count == 0) {
            return 1;
        }
        return (int) Math.max(1, Math.min(5, Math.round(sum / count)));
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

    // ==================== Prompt 组装 ====================

    private String buildPlanUserPrompt(String direction, String difficultyLabel,
                                       List<String> materialTags, List<String> weakTags) {
        StringBuilder sb = new StringBuilder();
        sb.append("【面试方向】").append(promptSanitizer.sanitize(direction)).append('\n');
        sb.append("【难度】").append(difficultyLabel).append('\n');
        sb.append("【主题数量】").append(questionCount).append('\n');
        sb.append("【参考技术标签】").append(materialTags.isEmpty()
            ? "无（请按方向自行覆盖常见考点）" : promptSanitizer.sanitize(String.join("、", materialTags))).append('\n');
        sb.append("【候选人薄弱方向（可适当侧重考察）】").append(weakTags.isEmpty()
            ? "无" : promptSanitizer.sanitize(String.join("、", weakTags))).append('\n');
        sb.append("请生成这次模拟面试的提纲（JSON 数组）。");
        return sb.toString();
    }

    private String buildTurnUserPrompt(ApCodingInterview record, List<PlanTopic> plan,
                                       int currentIndex, String answer) {
        PlanTopic topic = plan.get(currentIndex);
        StringBuilder sb = new StringBuilder();
        sb.append("【当前主题】").append(topic.topic).append('\n');
        sb.append("【候选人正在回答的问题】").append(currentQuestion(record, topic)).append('\n');
        sb.append("【本主题关键考点】").append(String.join("、", topic.keyPoints)).append('\n');
        sb.append("【最近对话】\n").append(recentDialogue(record)).append('\n');
        sb.append("【候选人本轮回答】\n")
            .append(promptSanitizer.sanitizeAndWrap("answer", answer)).append('\n');
        sb.append("请按输出协议输出（第一行 FOLLOWUP 或 NEXT）。");
        return sb.toString();
    }

    private String buildReportUserPrompt(ApCodingInterview record) {
        StringBuilder sb = new StringBuilder();
        sb.append("【面试方向】").append(promptSanitizer.sanitize(record.getDirection())).append('\n');
        sb.append("【面试提纲（JSON）】\n").append(record.getPlanSnapshot()).append('\n');
        sb.append("【完整对话】\n").append(fullDialogue(record)).append('\n');
        sb.append("请输出面试报告（JSON 对象）。");
        return sb.toString();
    }

    /** 当前应回答的问题：最近一条面试官发言（可能是追问）；兜底为主题主问题 */
    private static String currentQuestion(ApCodingInterview record, PlanTopic topic) {
        List<TurnRecord> turns = parseTurnsStatic(record.getTurns());
        for (int i = turns.size() - 1; i >= 0; i--) {
            TurnRecord t = turns.get(i);
            if ("interviewer".equals(t.role) && t.content != null && !t.content.isBlank()) {
                return t.content;
            }
        }
        return topic.mainQuestion;
    }

    /** 最近 K 条对话（用户内容净化 + 截断，防注入与 token 膨胀） */
    private String recentDialogue(ApCodingInterview record) {
        List<TurnRecord> turns = parseTurns(record.getTurns());
        if (turns.isEmpty()) {
            return "（暂无）";
        }
        int from = Math.max(0, turns.size() - TURN_WINDOW_MESSAGES);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < turns.size(); i++) {
            appendDialogueLine(sb, turns.get(i), WINDOW_TRUNCATE);
        }
        return sb.toString().trim();
    }

    /** 全量对话（报告输入；用户内容净化 + 适度截断） */
    private String fullDialogue(ApCodingInterview record) {
        List<TurnRecord> turns = parseTurns(record.getTurns());
        if (turns.isEmpty()) {
            return "（候选人未作答任何问题）";
        }
        StringBuilder sb = new StringBuilder();
        for (TurnRecord turn : turns) {
            appendDialogueLine(sb, turn, REPORT_TRUNCATE);
        }
        return sb.toString().trim();
    }

    private void appendDialogueLine(StringBuilder sb, TurnRecord turn, int truncate) {
        String content = turn.content == null ? "" : turn.content;
        if ("user".equals(turn.role)) {
            content = promptSanitizer.sanitize(content);
        }
        if (content.length() > truncate) {
            content = content.substring(0, truncate) + "…";
        }
        sb.append("user".equals(turn.role) ? "候选人：" : "面试官：");
        sb.append(content).append('\n');
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

    // ==================== 素材池 / 弱项 ====================

    /** 素材标签：方向命中题优先聚合标签（Top N）；无命中则全库取样（标签不足自然降级） */
    private List<String> materialTags(String direction) {
        List<ApCodingQuestion> pool = questionMapper.selectRandomBatch(
            null, java.util.Collections.singletonList(direction), MATERIAL_POOL_SIZE);
        if (pool == null || pool.isEmpty()) {
            pool = questionMapper.selectRandomBatch(null, null, MATERIAL_POOL_SIZE);
        }
        if (pool == null || pool.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ApCodingQuestion q : pool) {
            for (String tag : CodingJudge.parseTags(q.getTags())) {
                counts.merge(tag, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(MATERIAL_TAG_LIMIT)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /** 用户弱项领域（tag_stats 正确率升序前 5；无数据返回空=不限定） */
    private List<String> weakTags(Integer userId) {
        ApCodingUserStat stat = statMapper.selectOne(new LambdaQueryWrapper<ApCodingUserStat>()
            .eq(ApCodingUserStat::getUserId, userId));
        if (stat == null || stat.getTagStats() == null || stat.getTagStats().isBlank()) {
            return new ArrayList<>();
        }
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(stat.getTagStats(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[CodingInterview] 解析答题领域分布失败（提纲忽略弱项）: userId={}", userId);
            return new ArrayList<>();
        }
        List<Map.Entry<String, Double>> rates = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> value)) {
                continue;
            }
            Integer total = toInt(value.get("total"));
            Integer correct = toInt(value.get("correct"));
            if (total == null || total <= 0) {
                continue;
            }
            rates.add(new AbstractMap.SimpleEntry<>(entry.getKey(),
                (correct == null ? 0 : correct) * 1.0 / total));
        }
        rates.sort(Comparator.comparingDouble(Map.Entry::getValue));
        List<String> tags = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rates) {
            if (tags.size() >= WEAK_TAG_LIMIT) {
                break;
            }
            tags.add(entry.getKey());
        }
        return tags;
    }

    // ==================== JSON 解析 / VO 组装 ====================

    /** 解析并校验提纲（生成校验用，不合格返回 null；存储重载请用 parsePlanList） */
    private List<PlanTopic> parsePlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJsonArray(raw);
        if (json == null) {
            return null;
        }
        List<PlanTopic> list;
        try {
            list = objectMapper.readValue(json, new TypeReference<List<PlanTopic>>() {});
        } catch (Exception e) {
            log.warn("[CodingInterview] 提纲 JSON 解析失败: {}", e.getMessage());
            return null;
        }
        if (list == null) {
            return null;
        }
        List<PlanTopic> valid = new ArrayList<>();
        for (PlanTopic t : list) {
            if (t == null || isBlank(t.topic) || isBlank(t.mainQuestion) || t.keyPoints == null) {
                continue;
            }
            List<String> keyPoints = t.keyPoints.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());
            if (keyPoints.isEmpty()) {
                continue;
            }
            t.topic = t.topic.trim();
            t.mainQuestion = t.mainQuestion.trim();
            t.keyPoints = keyPoints;
            if (t.tag == null) {
                t.tag = "";
            }
            valid.add(t);
        }
        return valid;
    }

    /** 存储提纲重载（快照为服务端写出，异常时返回空列表 = 不可用） */
    private List<PlanTopic> parsePlanList(String json) {
        List<PlanTopic> list = parsePlan(json);
        return list == null ? new ArrayList<>() : list;
    }

    private List<TurnRecord> parseTurns(String json) {
        return new ArrayList<>(parseTurnsStatic(json));
    }

    private static List<TurnRecord> parseTurnsStatic(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<TurnRecord> list = ObjectMapperHolder.MAPPER.readValue(json,
                new TypeReference<List<TurnRecord>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("[CodingInterview] 对话流水解析失败");
            return new ArrayList<>();
        }
    }

    private CodingInterviewSessionVO toSessionVO(ApCodingInterview record, Date now) {
        List<PlanTopic> plan = parsePlanList(record.getPlanSnapshot());
        CodingInterviewSessionVO vo = new CodingInterviewSessionVO();
        vo.setInterviewId(record.getId());
        vo.setDirection(record.getDirection());
        vo.setDifficulty(record.getDifficulty());
        vo.setStatus(record.getStatus());
        vo.setTotalTopics(plan.size());
        int currentIndex = nvl(record.getCurrentIndex());
        vo.setCurrentIndex(currentIndex);
        vo.setStartedTime(formatDateTime(record.getStartedTime()));
        vo.setDeadlineTime(formatDateTime(record.getDeadlineTime()));
        vo.setDurationSeconds(durationMinutes * 60);
        long remainMs = record.getDeadlineTime() == null ? 0
            : record.getDeadlineTime().getTime() - now.getTime();
        vo.setRemainingSeconds((int) Math.max(0, remainMs / 1000));

        CodingInterviewSessionVO.CurrentTopic current = new CodingInterviewSessionVO.CurrentTopic();
        current.setIndex(currentIndex);
        PlanTopic topic = currentIndex >= 0 && currentIndex < plan.size() ? plan.get(currentIndex) : null;
        if (topic != null) {
            current.setTopic(topic.topic);
            current.setQuestion(currentQuestion(record, topic));
        }
        vo.setCurrentTopic(current);
        vo.setTurns(toTurnItems(parseTurns(record.getTurns())));
        return vo;
    }

    private CodingInterviewFinishVO toFinishVO(ApCodingInterview record, boolean reportReady) {
        CodingInterviewFinishVO vo = new CodingInterviewFinishVO();
        vo.setInterviewId(record.getId());
        vo.setDirection(record.getDirection());
        vo.setOverallScore(record.getOverallScore());
        vo.setReportReady(reportReady);
        vo.setFinishedTime(formatDateTime(record.getFinishedTime()));
        return vo;
    }

    private CodingInterviewReportVO toReportVO(ApCodingInterview record) {
        CodingInterviewReportVO vo = new CodingInterviewReportVO();
        vo.setInterviewId(record.getId());
        vo.setDirection(record.getDirection());
        vo.setDifficulty(record.getDifficulty());
        vo.setStatus(record.getStatus());
        vo.setOverallScore(record.getOverallScore());
        vo.setTotalTopics(parsePlanList(record.getPlanSnapshot()).size());
        vo.setStartedTime(formatDateTime(record.getStartedTime()));
        vo.setDeadlineTime(formatDateTime(record.getDeadlineTime()));
        vo.setFinishedTime(formatDateTime(record.getFinishedTime()));
        Date end = record.getFinishedTime() != null ? record.getFinishedTime() : new Date();
        long duration = record.getStartedTime() == null ? 0
            : (end.getTime() - record.getStartedTime().getTime()) / 1000;
        vo.setDurationSeconds((int) Math.max(0, duration));

        List<TurnRecord> turns = parseTurns(record.getTurns());
        vo.setCompletedTopics(completedTopics(turns));
        vo.setTurns(toTurnItems(turns));

        ReportData report = parseReport(record.getReport());
        if (report != null) {
            vo.setReportReady(true);
            vo.setOverall(report.overall);
            vo.setSuggestions(report.suggestions);
            List<CodingInterviewReportVO.ReportItem> items = new ArrayList<>();
            for (ReportItemData data : report.items) {
                CodingInterviewReportVO.ReportItem item = new CodingInterviewReportVO.ReportItem();
                item.setTopic(data.topic);
                item.setStructure(data.structure);
                item.setCoverageScore(data.coverageScore);
                item.setAccuracy(data.accuracy);
                item.setComment(data.comment);
                CodingInterviewReportVO.Coverage coverage = new CodingInterviewReportVO.Coverage();
                coverage.setCovered(data.coverage.covered);
                coverage.setMissing(data.coverage.missing);
                item.setCoverage(coverage);
                items.add(item);
            }
            vo.setItems(items);
        } else {
            // 结构化失败降级：原文展示（报告不丢，可重试 finish 恢复结构化）
            vo.setReportReady(false);
            vo.setRawText(record.getReport());
        }
        return vo;
    }

    /** 已答主题数（出现过作答的主题去重计数） */
    private static int completedTopics(List<TurnRecord> turns) {
        Set<Integer> topics = new HashSet<>();
        for (TurnRecord t : turns) {
            if ("answer".equals(t.type) && t.topicIndex != null) {
                topics.add(t.topicIndex);
            }
        }
        return topics.size();
    }

    private static List<CodingInterviewSessionVO.TurnItem> toTurnItems(List<TurnRecord> turns) {
        List<CodingInterviewSessionVO.TurnItem> items = new ArrayList<>();
        for (TurnRecord t : turns) {
            CodingInterviewSessionVO.TurnItem item = new CodingInterviewSessionVO.TurnItem();
            item.setRole(t.role);
            item.setType(t.type);
            item.setContent(t.content);
            item.setTopicIndex(t.topicIndex);
            item.setTs(t.ts);
            items.add(item);
        }
        return items;
    }

    private static TurnRecord interviewerTurn(String type, String content, int topicIndex, Date ts) {
        TurnRecord t = new TurnRecord();
        t.role = "interviewer";
        t.type = type;
        t.content = content;
        t.topicIndex = topicIndex;
        t.ts = ts.getTime();
        return t;
    }

    private static TurnRecord userTurn(String content, int topicIndex, Date ts) {
        TurnRecord t = new TurnRecord();
        t.role = "user";
        t.type = "answer";
        t.content = content;
        t.topicIndex = topicIndex;
        t.ts = ts.getTime();
        return t;
    }

    // ==================== 状态 / 工具 ====================

    /** 超时/并发兜底：仅当仍为进行中才置过期（防覆盖已完成结果） */
    private void expireOngoing(Long id) {
        ApCodingInterview expire = new ApCodingInterview();
        expire.setStatus(ApCodingInterview.STATUS_EXPIRED);
        interviewMapper.update(expire, new LambdaUpdateWrapper<ApCodingInterview>()
            .eq(ApCodingInterview::getId, id)
            .eq(ApCodingInterview::getStatus, ApCodingInterview.STATUS_ONGOING));
    }

    private static boolean isStatus(ApCodingInterview record, int status) {
        return record.getStatus() != null && record.getStatus() == status;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String difficultyLabel(int difficulty) {
        switch (difficulty) {
            case 1:
                return "入门";
            case 3:
                return "挑战";
            default:
                return "进阶";
        }
    }

    private static Date startOfDay() {
        return Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static String formatDateTime(Date date) {
        if (date == null) {
            return "";
        }
        return date.toInstant().atZone(ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 剥离 markdown 代码块围栏（模型可能仍输出 ```json ... ```） */
    private static String stripFences(String raw) {
        String t = raw.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) {
            return t;
        }
        String body = t.substring(firstNl + 1);
        int fence = body.lastIndexOf("```");
        return fence >= 0 ? body.substring(0, fence).trim() : body.trim();
    }

    private static String extractJsonArray(String raw) {
        String t = stripFences(raw);
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return t.substring(start, end + 1);
    }

    private static String extractJsonObject(String raw) {
        String t = stripFences(raw);
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return t.substring(start, end + 1);
    }

    /** 剥离 FOLLOWUP 独占行时的行内前缀（无换行输出的兜底，如 "FOLLOWUP：你提到…"） */
    private static String stripFollowupPrefix(String head) {
        return head.replaceFirst("(?is)^\\s*FOLLOWUP\\s*[:：]?\\s*", "").trim();
    }

    // ==================== 控制行流式缓冲 ====================

    /**
     * 控制行流式缓冲：首个换行前的内容不转发（避免控制词泄漏）；判定后 FOLLOWUP 逐段转发，
     * NEXT 抛 {@link CancellationException} 中止模型流（省 token，由服务端拼装下一题）。
     */
    private static final class ControlStream {

        private final TurnSink sink;
        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder followup = new StringBuilder();
        private boolean decided;
        private boolean followupMode;
        private long firstDeltaAt;
        /** 模型输出 NEXT（已中止流） */
        boolean nextDetected;

        ControlStream(TurnSink sink) {
            this.sink = sink;
        }

        void onDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (decided) {
                if (followupMode) {
                    followup.append(delta);
                    sink.delta(delta);
                }
                return;
            }
            if (buffer.length() == 0) {
                firstDeltaAt = System.currentTimeMillis();
            }
            buffer.append(delta);
            int nl = buffer.indexOf("\n");
            if (nl >= 0) {
                decide(buffer.toString());
                if (nextDetected) {
                    // 中止模型流：网关按取消处理（不计熔断失败），已生成部分仍计量
                    throw new CancellationException("interview:next");
                }
            } else if (System.currentTimeMillis() - firstDeltaAt > CONTROL_LINE_TIMEOUT_MS) {
                // 超时保护：协议未被执行时不吞内容——整段按 FOLLOWUP 转发
                decided = true;
                followupMode = true;
                String text = buffer.toString();
                followup.append(text);
                sink.delta(text);
                buffer.setLength(0);
            }
        }

        /** 流结束仍未见换行/未判定时，用完整文本补判定 */
        ControlDecision decisionOrDecide(String fullText) {
            if (!decided) {
                decide(fullText == null ? "" : fullText);
            }
            return new ControlDecision(nextDetected, followup.toString());
        }

        private void decide(String raw) {
            decided = true;
            int nl = raw.indexOf('\n');
            String head = nl >= 0 ? raw.substring(0, nl) : raw;
            String rest = nl >= 0 ? raw.substring(nl + 1) : "";
            String normalized = head.trim().toUpperCase(Locale.ROOT);
            if (normalized.startsWith(CONTROL_NEXT)) {
                nextDetected = true;
                return;
            }
            followupMode = true;
            String text = nl >= 0 ? rest : stripFollowupPrefix(head);
            if (normalized.startsWith(CONTROL_FOLLOWUP)) {
                followup.append(text);
                if (!text.isEmpty()) {
                    sink.delta(text);
                }
                return;
            }
            // 非协议输出：整段当追问转发（不吞内容、不泄漏给用户造成困惑）
            followup.append(raw);
            sink.delta(raw);
        }
    }

    /** 控制行判定结果：next=true 走服务端换题；否则 text 为追问全文 */
    private static final class ControlDecision {
        final boolean next;
        final String text;

        ControlDecision(boolean next, String text) {
            this.next = next;
            this.text = text;
        }
    }

    /** 一轮生成结果（面试官发言 + 进度） */
    private static final class TurnOutcome {
        String text;
        String kind;
        int topicIndex;
        boolean completed;
    }

    /** Jackson 静态持有（parseTurnsStatic 用，避免静态方法访问实例字段） */
    private static final class ObjectMapperHolder {
        static final ObjectMapper MAPPER = new ObjectMapper();
    }

    // ==================== JSON 结构 ====================

    /** 提纲主题（仅服务端可见，含 keyPoints） */
    static class PlanTopic {
        public String topic;
        public String mainQuestion;
        public List<String> keyPoints;
        public String tag;
    }

    /** 对话流水（turns JSON 元素） */
    static class TurnRecord {
        public String role;
        public String type;
        public String content;
        public Integer topicIndex;
        public Long ts;
    }

    /** 报告结构（解析成功时写库；失败时 report 存原文） */
    static class ReportData {
        public List<ReportItemData> items;
        public String overall;
        public List<String> suggestions;
    }

    static class ReportItemData {
        public String topic;
        public Integer structure;
        public CoverageData coverage;
        public Integer accuracy;
        public String comment;
        /** 服务端按 covered/(covered+missing) 计算的覆盖度等级（1-5） */
        public Integer coverageScore;
    }

    static class CoverageData {
        public List<String> covered;
        public List<String> missing;
    }
}