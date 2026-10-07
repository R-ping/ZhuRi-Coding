package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAssessmentMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.coding.CodingAssessmentService;
import com.zhuri.coding.content.service.coding.CodingJudge;
import com.zhuri.coding.model.coding.dtos.CodingAssessmentSubmitDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingAssessment;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingAssessmentHistoryVO;
import com.zhuri.coding.model.coding.vos.CodingAssessmentPaperVO;
import com.zhuri.coding.model.coding.vos.CodingAssessmentResultVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 能力测评服务实现（Coding 延展第二层 · Stage B）
 *
 * <p>组卷策略（目标 10 题）：难度配额 4 入门 / 4 进阶 / 2 挑战；每个难度内先按用户弱项领域
 * （tag_stats 正确率升序前 5）命中优先，再任意补足；某难度不足时由"不限难度"环节降级补齐，
 * 总数不足最低开卷题量则拒绝（题库准备中）。组卷成功立刻写含答案的组卷快照——判分与成绩单
 * 回放都以快照为准，题库后续编辑/下架不影响历史成绩。</p>
 *
 * <p>状态机：1进行中（可续答，deadline 不变）→ 2已提交（交卷幂等） / 3已过期（超时懒过期）。
 * 冷却是"已提交"起 N 天内不允许开新卷（0=关闭），只约束已提交，避免被超时锁死。
 * 测评不计等级分、不写每日一题作答流水（口径隔离）；百分位在样本量达标后展示（最低 1%）。</p>
 */
@Slf4j
@Service
public class CodingAssessmentServiceImpl implements CodingAssessmentService {

    /** 弱项领域取前 N 个（tag_stats 正确率升序） */
    private static final int WEAK_TAG_LIMIT = 5;

    /** 领域分布兜底标签（题目无标签时归入） */
    private static final String DEFAULT_TAG = "综合";

    /** 组卷目标题量（可配；@Value 默认值同时作为单测无容器时的兜底） */
    @Value("${app.coding.assessment.question-count:10}")
    private int questionCount = 10;

    /** 最低开卷题量（不足拒绝） */
    @Value("${app.coding.assessment.min-question-count:5}")
    private int minQuestionCount = 5;

    /** 限时（分钟） */
    @Value("${app.coding.assessment.duration-minutes:15}")
    private int durationMinutes = 15;

    /** 重考冷却天数（0=关闭） */
    @Value("${app.coding.assessment.retake-cooldown-days:90}")
    private int retakeCooldownDays = 90;

    /** 百分位最小样本量（不足不展示） */
    @Value("${app.coding.assessment.percentile-min-sample:20}")
    private int percentileMinSample = 20;

    @Autowired
    private ApCodingQuestionMapper questionMapper;

    @Autowired
    private ApCodingAssessmentMapper assessmentMapper;

    @Autowired
    private ApCodingUserStatMapper statMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 开卷 / 续答 ====================

    @Override
    public ResponseResult start(Integer userId) {
        Date now = new Date();

        // 1. 有进行中的卷：未超时直接续答（deadline 不变、不重新组卷）；已超时懒置过期后继续开新卷
        ApCodingAssessment ongoing = assessmentMapper.selectOngoing(userId);
        if (ongoing != null) {
            if (ongoing.getDeadlineTime() != null && now.before(ongoing.getDeadlineTime())) {
                return ResponseResult.okResult(toPaperVO(ongoing, true, now));
            }
            expireOngoing(ongoing.getId());
        }

        // 2. 重考冷却：只约束"已提交"（进行中/已过期可立即重开，避免被超时锁死）
        if (retakeCooldownDays > 0) {
            ApCodingAssessment latest = assessmentMapper.selectLatestSubmitted(userId);
            if (latest != null && latest.getSubmittedTime() != null) {
                long nextAvailable = latest.getSubmittedTime().getTime() + retakeCooldownDays * 86400_000L;
                if (now.getTime() < nextAvailable) {
                    return ResponseResult.errorResult(400,
                        "测评冷却中（每 " + retakeCooldownDays + " 天可考一次），下次可考时间："
                            + formatDateTime(new Date(nextAvailable)));
                }
            }
        }

        // 3. 组卷（弱项优先 + 难度配额 + 降级补齐）
        List<ApCodingQuestion> paper = composePaper(userId);
        if (paper.size() < minQuestionCount) {
            return ResponseResult.errorResult(400,
                "测评题库准备中（至少需要 " + minQuestionCount + " 道题），请稍后再来");
        }

        // 4. 入库：快照含答案（仅服务端），下发题面不含答案
        ApCodingAssessment record = new ApCodingAssessment();
        record.setUserId(userId);
        record.setStatus(ApCodingAssessment.STATUS_ONGOING);
        record.setPaperSnapshot(CodingJudge.writeJson(toSnapshot(paper)));
        record.setTotalCount(paper.size());
        record.setStartedTime(now);
        record.setDeadlineTime(new Date(now.getTime() + durationMinutes * 60_000L));
        assessmentMapper.insert(record);

        return ResponseResult.okResult(toPaperVO(record, false, now));
    }

    @Override
    public ResponseResult current(Integer userId) {
        ApCodingAssessment ongoing = assessmentMapper.selectOngoing(userId);
        if (ongoing == null) {
            return ResponseResult.okResult(null);
        }
        Date now = new Date();
        if (ongoing.getDeadlineTime() == null || now.after(ongoing.getDeadlineTime())) {
            expireOngoing(ongoing.getId());
            return ResponseResult.okResult(null);
        }
        return ResponseResult.okResult(toPaperVO(ongoing, true, now));
    }

    // ==================== 交卷 ====================

    @Override
    public ResponseResult submit(Integer userId, CodingAssessmentSubmitDTO dto) {
        if (dto == null || dto.getAssessmentId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "测评ID不能为空");
        }
        ApCodingAssessment record = assessmentMapper.selectById(dto.getAssessmentId());
        if (record == null || !userId.equals(record.getUserId())) {
            return ResponseResult.errorResult(400, "测评不存在");
        }
        // 幂等：已提交直接回放成绩单（网络重试/双击）
        if (isStatus(record, ApCodingAssessment.STATUS_SUBMITTED)) {
            return ResponseResult.okResult(buildResult(record));
        }
        Date now = new Date();
        if (isStatus(record, ApCodingAssessment.STATUS_EXPIRED)
            || (record.getDeadlineTime() != null && now.after(record.getDeadlineTime()))) {
            expireOngoing(record.getId());
            return ResponseResult.errorResult(400, "测评已过期，请重新开卷");
        }

        // 判分（以组卷快照为准）：逐题集合比对 + 领域分布聚合
        List<SnapshotQuestion> paper = parseSnapshot(record.getPaperSnapshot());
        Map<Long, List<Integer>> userAnswerMap = new HashMap<>();
        if (dto.getAnswers() != null) {
            for (CodingAssessmentSubmitDTO.Item item : dto.getAnswers()) {
                if (item != null && item.getQuestionId() != null) {
                    userAnswerMap.put(item.getQuestionId(), item.getUserAnswer());
                }
            }
        }
        List<AnswerItem> details = new ArrayList<>();
        Map<String, int[]> domain = new LinkedHashMap<>();
        int correctCount = 0;
        for (SnapshotQuestion q : paper) {
            Set<Integer> answerSet = sanitize(userAnswerMap.get(q.id),
                CodingJudge.parseStringList(q.options).size());
            boolean correct = CodingJudge.judge(CodingJudge.parseIntList(q.answer), answerSet);
            if (correct) {
                correctCount++;
            }
            AnswerItem detail = new AnswerItem();
            detail.questionId = q.id;
            detail.userAnswer = new ArrayList<>(answerSet);
            detail.correct = correct;
            details.add(detail);

            String tag = firstTag(q.tags);
            int[] agg = domain.computeIfAbsent(tag, k -> new int[2]);
            agg[0]++;
            if (correct) {
                agg[1]++;
            }
        }
        int total = paper.size();
        int score = total <= 0 ? 0 : (int) Math.round(correctCount * 100.0 / total);
        Integer percentile = calcPercentile(score);

        // 原子占位写入：仅当仍为进行中才落结果（并发双交只落一次）
        ApCodingAssessment update = new ApCodingAssessment();
        update.setStatus(ApCodingAssessment.STATUS_SUBMITTED);
        update.setAnswers(CodingJudge.writeJson(details));
        update.setScore(score);
        update.setCorrectCount(correctCount);
        update.setDomainStats(writeDomain(domain));
        update.setPercentile(percentile);
        update.setDurationSeconds((int) Math.max(0,
            (now.getTime() - record.getStartedTime().getTime()) / 1000));
        update.setSubmittedTime(now);
        int rows = assessmentMapper.update(update, new LambdaUpdateWrapper<ApCodingAssessment>()
            .eq(ApCodingAssessment::getId, record.getId())
            .eq(ApCodingAssessment::getStatus, ApCodingAssessment.STATUS_ONGOING));
        if (rows == 0) {
            // 并发交卷或已过期：回读——已提交则幂等回放，否则按过期处理
            ApCodingAssessment fresh = assessmentMapper.selectById(record.getId());
            if (fresh != null && isStatus(fresh, ApCodingAssessment.STATUS_SUBMITTED)) {
                return ResponseResult.okResult(buildResult(fresh));
            }
            expireOngoing(record.getId());
            return ResponseResult.errorResult(400, "测评已过期，请重新开卷");
        }

        // 用内存中已算好的结果组装成绩单（不必回读）
        Map<Long, AnswerItem> detailMap = details.stream()
            .collect(Collectors.toMap(d -> d.questionId, d -> d, (a, b) -> a, LinkedHashMap::new));
        return ResponseResult.okResult(composeResult(record.getId(), record.getPaperSnapshot(), detailMap,
            score, correctCount, total, percentile, writeDomain(domain), now));
    }

    // ==================== 成绩单 ====================

    @Override
    public ResponseResult latest(Integer userId) {
        ApCodingAssessment latest = assessmentMapper.selectLatestSubmitted(userId);
        return ResponseResult.okResult(latest == null ? null : buildResult(latest));
    }

    @Override
    public ResponseResult history(Integer userId, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 || size > 50 ? 10 : size;
        // 显式投影：paper_snapshot（含正确答案的全卷快照）与 answers 是本表最大的两列，
        // 列表页只用得到标量列，不写 select(...) 会把这两列按页整批读出来。
        IPage<ApCodingAssessment> result = assessmentMapper.selectPage(new Page<>(p, s),
            new LambdaQueryWrapper<ApCodingAssessment>()
                .select(ApCodingAssessment::getId, ApCodingAssessment::getScore,
                    ApCodingAssessment::getCorrectCount, ApCodingAssessment::getTotalCount,
                    ApCodingAssessment::getStatus, ApCodingAssessment::getSubmittedTime)
                .eq(ApCodingAssessment::getUserId, userId)
                .orderByDesc(ApCodingAssessment::getId));
        List<CodingAssessmentHistoryVO> list = result.getRecords().stream().map(r -> {
            CodingAssessmentHistoryVO vo = new CodingAssessmentHistoryVO();
            vo.setAssessmentId(r.getId());
            vo.setScore(nvl(r.getScore()));
            vo.setCorrectCount(nvl(r.getCorrectCount()));
            vo.setTotalCount(nvl(r.getTotalCount()));
            vo.setStatus(r.getStatus());
            vo.setSubmittedTime(formatDateTime(r.getSubmittedTime()));
            return vo;
        }).collect(Collectors.toList());
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    /** 成绩单回放（幂等交卷/最近成绩）：从组卷快照 + 已存作答明细重建 */
    private CodingAssessmentResultVO buildResult(ApCodingAssessment record) {
        return composeResult(record.getId(), record.getPaperSnapshot(), parseAnswers(record.getAnswers()),
            nvl(record.getScore()), nvl(record.getCorrectCount()), nvl(record.getTotalCount()),
            record.getPercentile(), record.getDomainStats(), record.getSubmittedTime());
    }

    private CodingAssessmentResultVO composeResult(Long assessmentId, String snapshotJson,
                                                   Map<Long, AnswerItem> detailMap, int score, int correctCount,
                                                   int totalCount, Integer percentile, String domainJson,
                                                   Date submittedTime) {
        CodingAssessmentResultVO vo = new CodingAssessmentResultVO();
        vo.setAssessmentId(assessmentId);
        vo.setScore(score);
        vo.setCorrectCount(correctCount);
        vo.setTotalCount(totalCount);
        vo.setPercentile(percentile);
        vo.setSubmittedTime(formatDateTime(submittedTime));
        vo.setDomainStats(parseDomain(domainJson));
        List<CodingAssessmentResultVO.ResultItem> items = new ArrayList<>();
        for (SnapshotQuestion q : parseSnapshot(snapshotJson)) {
            AnswerItem detail = detailMap.get(q.id);
            CodingAssessmentResultVO.ResultItem item = new CodingAssessmentResultVO.ResultItem();
            item.setQuestionId(q.id);
            item.setStem(q.stem);
            item.setUserAnswer(detail == null || detail.userAnswer == null
                ? new ArrayList<>() : detail.userAnswer);
            item.setCorrectAnswer(CodingJudge.parseIntList(q.answer));
            item.setCorrect(detail != null && Boolean.TRUE.equals(detail.correct));
            item.setExplanation(q.explanation);
            item.setTags(CodingJudge.parseTags(q.tags));
            items.add(item);
        }
        vo.setItems(items);
        return vo;
    }

    // ==================== 组卷 ====================

    /** 组卷：难度配额内弱项标签优先，最后不限难度降级补齐（不重复题） */
    private List<ApCodingQuestion> composePaper(Integer userId) {
        List<ApCodingQuestion> paper = new ArrayList<>();
        Set<Long> usedIds = new HashSet<>();
        List<String> weakTags = weakTags(userId);

        int easy = Math.max(1, Math.round(questionCount * 0.4f));
        int medium = Math.max(1, Math.round(questionCount * 0.4f));
        int hard = Math.max(1, questionCount - easy - medium);

        paper.addAll(pick(ApCodingQuestion.DIFFICULTY_EASY, weakTags, easy, usedIds));
        paper.addAll(pick(ApCodingQuestion.DIFFICULTY_MEDIUM, weakTags, medium, usedIds));
        paper.addAll(pick(ApCodingQuestion.DIFFICULTY_HARD, weakTags, hard, usedIds));

        // 降级补齐：不限难度先带弱项标签，仍缺则完全放开（覆盖"某难度题库不足"）
        int shortfall = questionCount - paper.size();
        if (shortfall > 0) {
            paper.addAll(pick(null, weakTags, shortfall, usedIds));
            shortfall = questionCount - paper.size();
        }
        if (shortfall > 0) {
            paper.addAll(pick(null, null, shortfall, usedIds));
        }
        return paper;
    }

    /** 抽取一批题（多取候选过滤同卷重复），返回实际命中列表 */
    private List<ApCodingQuestion> pick(Integer difficulty, List<String> tags, int need, Set<Long> usedIds) {
        if (need <= 0) {
            return new ArrayList<>();
        }
        List<ApCodingQuestion> candidates = questionMapper.selectRandomBatch(
            difficulty, tags, need + usedIds.size() + 2);
        List<ApCodingQuestion> picked = new ArrayList<>();
        if (candidates == null) {
            return picked;
        }
        for (ApCodingQuestion q : candidates) {
            if (picked.size() >= need) {
                break;
            }
            if (q == null || q.getId() == null || usedIds.contains(q.getId())) {
                continue;
            }
            picked.add(q);
            usedIds.add(q.getId());
        }
        return picked;
    }

    /** 用户弱项领域（tag_stats 正确率升序前 5；无数据返回空=不限定标签） */
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
            log.warn("解析答题领域分布失败（组卷忽略弱项）: userId={}", userId);
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

    // ==================== 组装 / 工具 ====================

    private CodingAssessmentPaperVO toPaperVO(ApCodingAssessment record, boolean resumed, Date now) {
        CodingAssessmentPaperVO vo = new CodingAssessmentPaperVO();
        vo.setAssessmentId(record.getId());
        vo.setDeadlineTime(formatDateTime(record.getDeadlineTime()));
        vo.setDurationSeconds(durationMinutes * 60);
        vo.setResumed(resumed);
        long remainMs = record.getDeadlineTime() == null ? 0
            : record.getDeadlineTime().getTime() - now.getTime();
        vo.setRemainingSeconds((int) Math.max(0, remainMs / 1000));
        List<CodingAssessmentPaperVO.PaperQuestion> questions = new ArrayList<>();
        for (SnapshotQuestion q : parseSnapshot(record.getPaperSnapshot())) {
            CodingAssessmentPaperVO.PaperQuestion pv = new CodingAssessmentPaperVO.PaperQuestion();
            pv.setQuestionId(q.id);
            pv.setStem(q.stem);
            pv.setQuestionType(q.questionType);
            pv.setOptions(CodingJudge.parseStringList(q.options));
            pv.setDifficulty(q.difficulty);
            pv.setTags(CodingJudge.parseTags(q.tags));
            questions.add(pv);
        }
        vo.setQuestions(questions);
        return vo;
    }

    /** 百分位：样本不足返回 null；最低 1%（避免出现"超过 0%"的误导展示） */
    private Integer calcPercentile(int score) {
        long total = assessmentMapper.countSubmitted() + 1; // 含本次即将写入的提交
        if (total < percentileMinSample) {
            return null;
        }
        long below = assessmentMapper.countSubmittedBelow(score);
        return (int) Math.max(1, Math.round(below * 100.0 / total));
    }

    /** 超时/并发兜底：仅当仍为进行中才置过期（防覆盖已提交结果） */
    private void expireOngoing(Long id) {
        ApCodingAssessment expire = new ApCodingAssessment();
        expire.setStatus(ApCodingAssessment.STATUS_EXPIRED);
        assessmentMapper.update(expire, new LambdaUpdateWrapper<ApCodingAssessment>()
            .eq(ApCodingAssessment::getId, id)
            .eq(ApCodingAssessment::getStatus, ApCodingAssessment.STATUS_ONGOING));
    }

    /** 用户答案清洗：去重、剔除非法下标（越界视为未选） */
    private static Set<Integer> sanitize(List<Integer> raw, int optionCount) {
        Set<Integer> set = new LinkedHashSet<>();
        if (raw == null) {
            return set;
        }
        for (Integer a : raw) {
            if (a != null && a >= 0 && a < optionCount) {
                set.add(a);
            }
        }
        return set;
    }

    private static boolean isStatus(ApCodingAssessment record, int status) {
        return record.getStatus() != null && record.getStatus() == status;
    }

    private static String firstTag(String tags) {
        List<String> list = CodingJudge.parseTags(tags);
        return list.isEmpty() ? DEFAULT_TAG : list.get(0);
    }

    private String writeDomain(Map<String, int[]> domain) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> entry : domain.entrySet()) {
            Map<String, Object> one = new HashMap<>();
            one.put("total", entry.getValue()[0]);
            one.put("correct", entry.getValue()[1]);
            out.put(entry.getKey(), one);
        }
        return CodingJudge.writeJson(out);
    }

    private Map<String, Object> parseDomain(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception e) {
            log.warn("解析测评领域分布失败: {}", json);
            return new LinkedHashMap<>();
        }
    }

    private List<SnapshotQuestion> toSnapshot(List<ApCodingQuestion> paper) {
        List<SnapshotQuestion> list = new ArrayList<>();
        for (ApCodingQuestion q : paper) {
            SnapshotQuestion s = new SnapshotQuestion();
            s.id = q.getId();
            s.stem = q.getStem();
            s.questionType = q.getQuestionType();
            s.options = q.getOptions();
            s.answer = q.getAnswer();
            s.explanation = q.getExplanation();
            s.difficulty = q.getDifficulty();
            s.tags = q.getTags();
            list.add(s);
        }
        return list;
    }

    private List<SnapshotQuestion> parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<SnapshotQuestion> list = objectMapper.readValue(json,
                new TypeReference<List<SnapshotQuestion>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("解析组卷快照失败: {}", json);
            return new ArrayList<>();
        }
    }

    private Map<Long, AnswerItem> parseAnswers(String json) {
        Map<Long, AnswerItem> map = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return map;
        }
        try {
            List<AnswerItem> list = objectMapper.readValue(json, new TypeReference<List<AnswerItem>>() {});
            if (list != null) {
                for (AnswerItem item : list) {
                    if (item != null && item.questionId != null) {
                        map.put(item.questionId, item);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析测评作答明细失败: {}", json);
        }
        return map;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
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

    private static String formatDateTime(java.util.Date date) {
        if (date == null) {
            return "";
        }
        return date.toInstant().atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ==================== 快照 / 明细 JSON 结构 ====================

    /** 组卷快照题（含答案，仅服务端可见；public 字段供 Jackson 直读直写） */
    static class SnapshotQuestion {
        public Long id;
        public String stem;
        public Integer questionType;
        public String options;
        public String answer;
        public String explanation;
        public Integer difficulty;
        public String tags;
    }

    /** 作答明细项（交卷时写入） */
    static class AnswerItem {
        public Long questionId;
        public List<Integer> userAnswer;
        public Boolean correct;
    }
}