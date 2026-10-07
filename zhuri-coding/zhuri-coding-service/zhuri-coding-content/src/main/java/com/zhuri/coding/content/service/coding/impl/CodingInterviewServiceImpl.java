package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zhuri.coding.common.bailian.PromptSanitizer;
import com.zhuri.coding.content.mapper.coding.ApCodingInterviewMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.AiPromptRegistry;
import com.zhuri.coding.content.service.ai.AiQuotaService;
import com.zhuri.coding.content.service.coding.CodingInterviewJson;
import com.zhuri.coding.content.service.coding.CodingInterviewJson.PlanTopic;
import com.zhuri.coding.content.service.coding.CodingInterviewJson.ReportData;
import com.zhuri.coding.content.service.coding.CodingInterviewJson.ReportItemData;
import com.zhuri.coding.content.service.coding.CodingInterviewJson.TurnRecord;
import com.zhuri.coding.content.service.coding.CodingInterviewService;
import com.zhuri.coding.content.service.coding.CodingJudge;
import com.zhuri.coding.content.service.coding.CodingReportService;
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
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static com.zhuri.coding.content.service.coding.CodingInterviewJson.appendDialogueLine;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.difficultyLabel;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.isBlank;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.nvl;
import static com.zhuri.coding.content.service.coding.CodingInterviewJson.parseTurns;

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
 * <p><b>报告链路已抽到 {@link CodingReportService}</b>：分批逐题评估 → 批内逐位对齐 → 二次汇总 →
 * 解析规范化，连带三层降级（单批失败补「未评估」占位 / 汇总失败确定性兜底 / 全批失败存原文）都在那边；
 * 本类对报告只剩「生成 + 落库 + 幂等回放」三件事。三段 JSON 列的契约与共用小工具见
 * {@link CodingInterviewJson}。</p>
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
    /** 作答文本长度上限（字符，防 prompt 成本失控） */
    private static final int ANSWER_MAX_LENGTH = 2_000;
    /** 已考主题回看场次（近 N 场已完成面试） */
    private static final int HISTORY_SESSION_LIMIT = 5;
    /** 已考主题注入条数上限（防 prompt 膨胀） */
    private static final int HISTORY_TOPIC_LIMIT = 15;
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
    /** 主题数可选范围（入参越界由服务端夹取，前端只做展示约束） */
    private static final int MIN_QUESTION_COUNT = 3;
    private static final int MAX_QUESTION_COUNT = 10;
    /** 提纲主题来源标记：简历深挖 / 方向通用 */
    static final String SOURCE_RESUME = "resume";
    static final String SOURCE_DIRECTION = "direction";

    /**
     * prompt 注册表 key（DB 可按需灰度覆盖）。
     * 报告链路的两个 key（{@code coding_interview_report_topic} / {@code coding_interview_report}）
     * 随报告逻辑一起搬到了 {@link CodingReportService}。
     */
    static final String PROMPT_KEY_PLAN = "coding_interview_plan";
    static final String PROMPT_KEY_TURN = "coding_interview_turn";

    /** 提纲生成 system（代码兜底） */
    static final String FALLBACK_PLAN_SYSTEM = String.join("\n",
        "你是资深技术面试官，负责为一场模拟面试生成提纲。",
        "你必须只输出一个 JSON 数组，不能有任何解释文字、markdown 代码块或多余符号。",
        "数组元素格式：",
        "{\"topic\":\"主题名\",\"mainQuestion\":\"主问题\",\"keyPoints\":[\"考点1\",\"考点2\",\"考点3\"],\"tag\":\"技术标签\",\"source\":\"resume\"}",
        "生成要求：",
        "1. 主题数量严格按用户消息中「主题数量」的要求；",
        "2. 每个主题 1 个开放式主问题（可展开回答 3-5 分钟，不要选择题、不要是非题）；",
        "3. 每个主题给出 3-5 个关键考点 keyPoints，它们是判断回答覆盖度的锚点，必须具体、可判定；",
        "4. 难度与方向以用户消息为准；",
        "5. source 只能取 resume 或 direction（小写）：主题来自候选人简历里写到的项目/经历取 resume，"
            + "通用技术考察取 direction；",
        "6. 严格按用户消息中「简历题配额」分配两类主题的数量；没给简历时全部取 direction；",
        "7. 若用户消息给出了「已考过的主题」，本次提纲要与它们区分开（换角度、换深度或换考点），"
            + "不要原题重问；",
        "8. 只输出 JSON 数组本身。");

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

    /** 简历文本上限（字符）；超出按入参截断，与解析接口的 resume-max-chars 保持一致 */
    @Value("${app.coding.interview.resume-max-chars:8000}")
    private int resumeMaxChars = 8000;

    /** 有简历时简历深挖题在大纲中的目标占比（其余为方向通用题） */
    @Value("${app.coding.interview.resume-topic-ratio:0.6}")
    private double resumeTopicRatio = 0.6;

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

    /**
     * 报告链路（分批评估 → 逐位对齐 → 二次汇总 → 解析/规范化）。
     * 抽成独立服务：报告有自己的名词体系与降级层次，和开面、轮次编排放一起会互相干扰阅读。
     */
    @Autowired
    private CodingReportService reportService;

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
        int topicCount = resolveTopicCount(dto.getQuestionCount());
        String resume = normalizeResumeText(dto.getResumeText());
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

        // 4. 素材池标签 + 用户弱项 + 近几场已考主题（标签不足自然降级为纯方向生成，不阻断）
        List<String> materialTags = materialTags(direction);
        List<String> weakTags = weakTags(userId);
        List<String> historyTopics = recentTopics(userId);

        // 5. 提纲生成（同步 LLM；失败/不合格拒绝开面——不落库、不产生脏数据）
        //    简历只作用于本次生成，不落库；有简历时按 resume-topic-ratio 分配简历深挖题配额
        String system = resolvePrompt(PROMPT_KEY_PLAN, FALLBACK_PLAN_SYSTEM, userId);
        String userPrompt = buildPlanUserPrompt(direction, difficultyLabel(difficulty),
            topicCount, resume, materialTags, weakTags, historyTopics);
        String raw = aiLlmGateway.generateOrNull(AiFeatures.INTERVIEW_PLAN, system, userPrompt, null, null);
        List<PlanTopic> plan = parsePlan(raw);
        int minTopics = Math.min(PLAN_MIN_TOPICS, Math.max(1, topicCount));
        if (plan == null || plan.size() < minTopics) {
            log.warn("[CodingInterview] 提纲生成失败或不合格，拒绝开面: userId={}, rawLen={}, topicCount={}, hasResume={}",
                userId, raw == null ? 0 : raw.length(), topicCount, resume != null);
            return ResponseResult.errorResult(500, "面试提纲生成失败，请稍后重试");
        }
        if (plan.size() > topicCount) {
            plan = new ArrayList<>(plan.subList(0, topicCount));
        }

        // 5.1 无简历时来源一律归为方向题：prompt 已说明"没给简历全部取 direction"，
        //     但模型仍可能凭空标 resume——不修的话前端会显示"简历深挖"而用户根本没传简历
        if (resume == null) {
            plan.forEach(t -> t.source = SOURCE_DIRECTION);
        }

        // 5.2 简历题配额留痕：模型没按配额分配时只告警不阻断——题目本身仍可用，
        //     且事后无法可靠重贴来源（改标而已，不如留痕观察 prompt 效果）
        if (resume != null) {
            long actual = plan.stream().filter(t -> SOURCE_RESUME.equals(t.source)).count();
            int expect = resumeTopicQuota(plan.size());
            if (actual != expect) {
                log.warn("[CodingInterview] 简历题配额未命中: userId={}, 期望={}, 实际={}, 主题数={}",
                    userId, expect, actual, plan.size());
            }
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
        if (isStatus(record, ApCodingInterview.STATUS_FINISHED) && reportService.hasUsableReport(record)) {
            return ResponseResult.okResult(toFinishVO(record, true));
        }
        Date now = new Date();
        if (isStatus(record, ApCodingInterview.STATUS_ONGOING)
            && record.getDeadlineTime() != null && now.after(record.getDeadlineTime())) {
            expireOngoing(record.getId());
            return ResponseResult.errorResult(400, "面试已超时，无法生成报告");
        }

        // 生成报告（超时保护；失败/解析失败存原文降级，重试可恢复）
        String raw = reportService.generateReport(record);
        ReportData report = reportService.parseReport(raw);
        String stored = report == null ? raw : CodingJudge.writeJson(report);
        Integer overallScore = report == null ? null : reportService.computeOverallScore(report);
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
            if (fresh != null && isStatus(fresh, ApCodingInterview.STATUS_FINISHED)
                && reportService.hasUsableReport(fresh)) {
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
        // 显式投影：turns / report 是两列大 TEXT（对话流水、报告 JSON），列表页一条都用不到。
        // 不写 select(...) 时 MyBatis-Plus 会 `SELECT` 全列，等于把每页 10 条的大字段一起读进内存。
        // plan_snapshot 虽然也是大列，但下面要用它统计主题数，必须留在投影里。
        IPage<ApCodingInterview> result = interviewMapper.selectPage(new Page<>(p, s),
            new LambdaQueryWrapper<ApCodingInterview>()
                .select(ApCodingInterview::getId, ApCodingInterview::getDirection,
                    ApCodingInterview::getDifficulty, ApCodingInterview::getOverallScore,
                    ApCodingInterview::getStatus, ApCodingInterview::getPlanSnapshot,
                    ApCodingInterview::getStartedTime, ApCodingInterview::getFinishedTime)
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

    // ==================== Prompt 组装 ====================

    /** 主题数：入参缺省取配置默认，越界夹取到 [MIN, MAX]（不报错，与 difficulty 同口径） */
    private int resolveTopicCount(Integer requested) {
        if (requested == null) {
            return Math.max(MIN_QUESTION_COUNT, Math.min(MAX_QUESTION_COUNT, questionCount));
        }
        return Math.max(MIN_QUESTION_COUNT, Math.min(MAX_QUESTION_COUNT, requested));
    }

    /** 简历文本：去空白、按上限截断；空串归一为 null（后续据此判断"有无简历"） */
    private String normalizeResumeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > resumeMaxChars) {
            return trimmed.substring(0, resumeMaxChars) + "\n...(简历内容过长，已截断)";
        }
        return trimmed;
    }

    /**
     * 简历题配额：按占比四舍五入后夹在 [1, topicCount-1]。
     * 上下都夹住是为了保证两类主题都出现——全给简历题会丢掉方向通用考察，反之则白传简历。
     */
    private int resumeTopicQuota(int topicCount) {
        if (topicCount <= 1) {
            return 0;
        }
        int quota = (int) Math.round(topicCount * resumeTopicRatio);
        return Math.max(1, Math.min(topicCount - 1, quota));
    }

    private String buildPlanUserPrompt(String direction, String difficultyLabel, int topicCount,
                                       String resumeText, List<String> materialTags,
                                       List<String> weakTags, List<String> historyTopics) {
        StringBuilder sb = new StringBuilder();
        sb.append("【面试方向】").append(promptSanitizer.sanitize(direction)).append('\n');
        sb.append("【难度】").append(difficultyLabel).append('\n');
        sb.append("【主题数量】").append(topicCount).append('\n');
        sb.append("【参考技术标签】").append(materialTags.isEmpty()
            ? "无（请按方向自行覆盖常见考点）" : promptSanitizer.sanitize(String.join("、", materialTags))).append('\n');
        sb.append("【候选人薄弱方向（可适当侧重考察）】").append(weakTags.isEmpty()
            ? "无" : promptSanitizer.sanitize(String.join("、", weakTags))).append('\n');
        // 跨场去重：同一用户连做多场时避免原题重问（近 N 场已完成面试的提纲主题）
        sb.append("【已考过的主题（本用户近几场；请换角度、换深度或换考点）】").append(historyTopics.isEmpty()
            ? "无" : promptSanitizer.sanitize(String.join("、", historyTopics))).append('\n');

        if (resumeText == null) {
            sb.append("【简历题配额】无简历，全部主题取 source=direction\n");
        } else {
            int resumeQuota = resumeTopicQuota(topicCount);
            sb.append("【简历题配额】").append(resumeQuota).append(" 个主题取 source=resume（针对简历里的")
                .append("项目/技术栈深挖），其余 ").append(topicCount - resumeQuota).append(" 个取 source=direction\n");
            sb.append("【候选人简历】\n")
                .append(promptSanitizer.sanitizeAndWrap("resume", resumeText)).append('\n');
        }

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

    /** 当前应回答的问题：最近一条面试官发言（可能是追问）；兜底为主题主问题 */
    private static String currentQuestion(ApCodingInterview record, PlanTopic topic) {
        List<TurnRecord> turns = parseTurns(record.getTurns());
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
            appendDialogueLine(sb, turns.get(i), WINDOW_TRUNCATE, promptSanitizer);
        }
        return sb.toString().trim();
    }

    /**
     * 近 N 场已考主题（去重后限条数）。
     * 无历史、查询失败、快照解析失败一律返回空列表 —— 去重是锦上添花，不能拖垮开面。
     */
    private List<String> recentTopics(Integer userId) {
        List<String> snapshots;
        try {
            snapshots = interviewMapper.selectRecentPlanSnapshots(userId, HISTORY_SESSION_LIMIT);
        } catch (Exception e) {
            log.warn("[CodingInterview] 查询历史主题失败（本次不做跨场去重）: userId={}, err={}",
                userId, e.getMessage());
            return new ArrayList<>();
        }
        if (snapshots == null || snapshots.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        for (String snapshot : snapshots) {
            for (PlanTopic topic : parsePlanList(snapshot)) {
                if (isBlank(topic.topic)) {
                    continue;
                }
                topics.add(topic.topic.trim());
                if (topics.size() >= HISTORY_TOPIC_LIMIT) {
                    break;
                }
            }
            if (topics.size() >= HISTORY_TOPIC_LIMIT) {
                break;
            }
        }
        return new ArrayList<>(topics);
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
            raw = CodingInterviewJson.mapper().readValue(stat.getTagStats(), new TypeReference<Map<String, Object>>() {});
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

    /**
     * 解析并校验提纲（生成校验用，不合格返回 null；存储重载请用 {@link #parsePlanList}）。
     *
     * <p>{@link CodingInterviewJson#parsePlan(String)} 只做"形状"层面的校验，
     * <b>source 归一留在这一层</b>：只认 {@code resume}，其余（含缺失、拼错、模型自创值）一律
     * {@code direction} —— 模型随手写的值不该被原样落库，否则前端会出现"既不是简历深挖、
     * 也不是方向题"的第三种来源。</p>
     */
    private static List<PlanTopic> parsePlan(String raw) {
        List<PlanTopic> plan = CodingInterviewJson.parsePlan(raw);
        if (plan == null) {
            return null;
        }
        for (PlanTopic t : plan) {
            t.source = SOURCE_RESUME.equalsIgnoreCase(t.source) ? SOURCE_RESUME : SOURCE_DIRECTION;
        }
        return plan;
    }

    /** 存储提纲重载（快照为服务端写出，异常时返回空列表 = 不可用） */
    private static List<PlanTopic> parsePlanList(String json) {
        List<PlanTopic> list = parsePlan(json);
        return list == null ? new ArrayList<>() : list;
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
            // 只暴露当前主题的来源（不下发后续主题，避免提前看到全部题目）
            current.setSource(SOURCE_RESUME.equals(topic.source) ? SOURCE_RESUME : SOURCE_DIRECTION);
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

        ReportData report = reportService.parseReport(record.getReport());
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
                // 未评估占位：等级为空，前端据此标「未评估」而不是显示 0/5
                item.setPending(Boolean.TRUE.equals(data.pending));
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

}