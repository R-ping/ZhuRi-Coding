package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.constants.LevelScoreActionCode;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.coding.CodingAnswerTxService;
import com.zhuri.coding.content.service.coding.CodingQuestionService;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.coding.dtos.CodingAnswerDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingAnswerVO;
import com.zhuri.coding.model.coding.vos.CodingQuestionVO;
import com.zhuri.coding.model.coding.vos.CodingRankingVO;
import com.zhuri.coding.model.coding.vos.CodingStatVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 每日一题与刷题服务实现（Coding 延展第一层）
 *
 * <p>三处既有体系接入点：</p>
 * <ul>
 *   <li>连续天数：答对当日一题 → reward 服务幂等打卡（continuous_days 唯一来源，与签到共用）；</li>
 *   <li>等级分：答对当日一题 → {@code LevelService.recordActionWithLimit(answer_question)}，
 *       日上限 1 次天然防刷；</li>
 *   <li>来源文章：题目 VO 回填来源文章标题，答错/解析给出阅读入口（双向导流）。</li>
 * </ul>
 */
@Slf4j
@Service
public class CodingQuestionServiceImpl implements CodingQuestionService {

    /** 今日题缓存：题目一天一抽，缓存后不再查库（key 带日期，跨天自然失效） */
    private static final String DAILY_KEY_PREFIX = "coding:daily:";
    /** 当日一题提交防抖锁（防双击并发写入两条记录） */
    private static final String ANSWER_LOCK_PREFIX = "coding:answer:lock:";
    /** 今日题缓存 TTL：26h 覆盖全天（key 带日期，不会跨天串题） */
    private static final long DAILY_CACHE_TTL_HOURS = 26L;
    /** 榜单条数 */
    private static final int RANKING_LIMIT = 20;
    /** 难度自适应所需的最少样本数（不足按入门起步） */
    private static final int ADAPTIVE_MIN_SAMPLES = 3;
    /** 正确率低于该值 → 入门 */
    private static final double ADAPTIVE_EASY_MAX = 0.5;
    /** 正确率低于该值 → 进阶，否则挑战 */
    private static final double ADAPTIVE_MEDIUM_MAX = 0.8;

    @Autowired
    private ApCodingQuestionMapper questionMapper;

    @Autowired
    private ApCodingAnswerRecordMapper recordMapper;

    @Autowired
    private ApCodingUserStatMapper statMapper;

    @Autowired
    private CodingAnswerTxService txService;

    @Autowired
    private ApArticleMapper articleMapper;

    @Autowired
    private LevelService levelService;

    @Autowired
    private IRewardClient rewardClient;

    @Autowired
    private IUserClient userClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ResponseResult today(Integer userId, Integer difficulty) {
        Date today = Date.valueOf(LocalDate.now());

        // 1. 当天已答：回放完整作答结果（含答案与解析），不再抽题
        ApCodingAnswerRecord record = txService.findDailyRecord(userId, today);
        if (record != null) {
            ApCodingQuestion answered = questionMapper.selectById(record.getQuestionId());
            if (answered != null) {
                return ResponseResult.okResult(buildQuestionVO(answered, record));
            }
            // 题目已不存在（理论不发生）：按未答继续抽题，保证不断供
        }

        // 2. 命中缓存：同一用户同一天固定一题；显式切换难度时重抽（难度可自选）
        String key = dailyKey(userId, today);
        ApCodingQuestion question = loadCachedQuestion(key);
        if (question != null && isValidDifficulty(difficulty)
            && !difficulty.equals(question.getDifficulty())) {
            question = null;
        }

        // 3. 未命中：按自选难度/历史正确率自适应抽题并缓存
        if (question == null) {
            Integer target = isValidDifficulty(difficulty) ? difficulty : adaptiveDifficulty(userId);
            question = pickQuestion(userId, target);
            if (question == null) {
                return ResponseResult.errorResult(400, "题库准备中，暂无可用题目，请稍后再来");
            }
            redisTemplate.opsForValue().set(key, String.valueOf(question.getId()),
                DAILY_CACHE_TTL_HOURS, TimeUnit.HOURS);
        }
        return ResponseResult.okResult(buildQuestionVO(question, null));
    }

    @Override
    public ResponseResult answer(Integer userId, CodingAnswerDTO dto) {
        if (dto == null || dto.getQuestionId() == null) {
            return ResponseResult.errorResult(400, "题目ID不能为空");
        }
        if (dto.getAnswers() == null || dto.getAnswers().isEmpty()) {
            return ResponseResult.errorResult(400, "请选择答案后再提交");
        }
        ApCodingQuestion question = questionMapper.selectById(dto.getQuestionId());
        if (question == null || question.getStatus() == null
            || question.getStatus() != ApCodingQuestion.STATUS_PUBLISHED) {
            return ResponseResult.errorResult(400, "题目不存在或已下架");
        }
        List<String> options = parseStringList(question.getOptions());
        // 用户答案去重排序（防重复下标），并校验下标范围与题型
        Set<Integer> answerSet = new LinkedHashSet<>();
        for (Integer a : dto.getAnswers()) {
            if (a == null || a < 0 || a >= options.size()) {
                return ResponseResult.errorResult(400, "答案超出选项范围");
            }
            answerSet.add(a);
        }
        if (question.getQuestionType() != null
            && question.getQuestionType() == ApCodingQuestion.TYPE_SINGLE && answerSet.size() != 1) {
            return ResponseResult.errorResult(400, "单选题只能选择一个选项");
        }
        List<Integer> userAnswers = new ArrayList<>(answerSet);
        List<Integer> correctAnswers = parseIntList(question.getAnswer());
        boolean correct = answerSet.equals(new HashSet<>(correctAnswers));

        boolean isDaily = Boolean.TRUE.equals(dto.getIsDaily());
        Date today = Date.valueOf(LocalDate.now());
        Integer elapsedSeconds = dto.getElapsedSeconds() != null && dto.getElapsedSeconds() >= 0
            ? Math.min(dto.getElapsedSeconds(), 24 * 3600) : null;

        ApCodingAnswerRecord record;
        if (isDaily) {
            // 当日题合法性：提交的题目必须与 Redis 缓存一致（防止绕开缓存挑简单题）；
            // 缓存缺失（过期/重启）时仅靠 DB 防重兜底，不阻断提交
            String expected = redisTemplate.opsForValue().get(dailyKey(userId, today));
            if (expected != null && !expected.equals(String.valueOf(dto.getQuestionId()))) {
                return ResponseResult.errorResult(400, "今日题目已更新，请刷新页面后重新作答");
            }
            if (txService.findDailyRecord(userId, today) != null) {
                return ResponseResult.errorResult(400, "今日一题已作答，明天再来");
            }
            // 防抖锁：双击/并发下只放行一次写入
            String lockKey = ANSWER_LOCK_PREFIX + userId + ":" + today;
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(locked)) {
                return ResponseResult.errorResult(429, "操作过于频繁，请稍后重试");
            }
            try {
                record = txService.saveAnswer(userId, question,
                    writeJson(userAnswers), correct, elapsedSeconds, true);
            } catch (IllegalStateException e) {
                return ResponseResult.errorResult(400, "今日一题已作答，明天再来");
            } finally {
                redisTemplate.delete(lockKey);
            }
        } else {
            record = txService.saveAnswer(userId, question,
                writeJson(userAnswers), correct, elapsedSeconds, false);
        }

        // 当日一题答对：计入逐日等级 + 触发幂等打卡（两处均 fail-open，不影响判分结果返回）
        int scoreAwarded = 0;
        Integer continuousDays = null;
        if (isDaily && correct) {
            scoreAwarded = recordLevelScore(userId, question.getId());
            if (scoreAwarded > 0) {
                ApCodingAnswerRecord scoreUpdate = new ApCodingAnswerRecord();
                scoreUpdate.setId(record.getId());
                scoreUpdate.setScoreAwarded(scoreAwarded);
                try {
                    recordMapper.updateById(scoreUpdate);
                } catch (Exception e) {
                    log.warn("回填作答得分失败（不影响判分）, recordId={}", record.getId(), e);
                }
            }
            continuousDays = completeCheckin(userId);
        }

        CodingAnswerVO vo = new CodingAnswerVO();
        vo.setQuestionId(question.getId());
        vo.setIsCorrect(correct);
        vo.setCorrectAnswer(correctAnswers);
        vo.setExplanation(question.getExplanation());
        vo.setScoreAwarded(scoreAwarded);
        vo.setContinuousDays(continuousDays);
        fillSourceArticle(vo, question.getSourceArticleId());
        vo.setAnswerCount(nvl(question.getAnswerCount()) + (isDaily ? 1 : 0));
        vo.setCorrectCount(nvl(question.getCorrectCount()) + (isDaily && correct ? 1 : 0));
        return ResponseResult.okResult(vo);
    }

    @Override
    public ResponseResult ranking(String period, Integer currentUserId) {
        LocalDate today = LocalDate.now();
        String normalized = period;
        LocalDate start;
        if ("week".equalsIgnoreCase(period)) {
            normalized = "week";
            start = today.with(DayOfWeek.MONDAY);
        } else if ("month".equalsIgnoreCase(period)) {
            normalized = "month";
            start = today.withDayOfMonth(1);
        } else {
            normalized = "day";
            start = today;
        }
        List<Map<String, Object>> rows = recordMapper.selectRanking(Date.valueOf(start), RANKING_LIMIT);
        Map<Long, Map<String, String>> userInfos = loadUserInfos(
            rows == null ? List.of() : rows.stream()
                .map(r -> toLong(r.get("userId")))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList()));

        List<CodingRankingVO> list = new ArrayList<>();
        if (rows != null) {
            int rank = 0;
            for (Map<String, Object> row : rows) {
                rank++;
                Long uid = toLong(row.get("userId"));
                Integer total = toInt(row.get("totalCount"));
                Integer correctCount = toInt(row.get("correctCount"));
                CodingRankingVO vo = new CodingRankingVO();
                vo.setRank(rank);
                vo.setUserId(uid == null ? null : uid.intValue());
                Map<String, String> info = uid == null ? null : userInfos.get(uid);
                vo.setNickname(info == null ? "" : info.getOrDefault("name", ""));
                vo.setAvatar(info == null ? "" : info.getOrDefault("avatar", ""));
                vo.setTotalCount(total == null ? 0 : total);
                vo.setCorrectCount(correctCount == null ? 0 : correctCount);
                vo.setAccuracy(total != null && total > 0 && correctCount != null
                    ? (int) Math.round(correctCount * 100.0 / total) : 0);
                vo.setAvgSeconds(toInt(row.get("avgSeconds")));
                vo.setIsSelf(currentUserId != null && uid != null
                    && uid.intValue() == currentUserId);
                list.add(vo);
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("period", normalized);
        data.put("list", list);
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult questions(Integer difficulty, Integer page, Integer size, Integer userId) {
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 || size > 50 ? 10 : size;

        LambdaQueryWrapper<ApCodingQuestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApCodingQuestion::getStatus, ApCodingQuestion.STATUS_PUBLISHED);
        if (isValidDifficulty(difficulty)) {
            wrapper.eq(ApCodingQuestion::getDifficulty, difficulty);
        }
        wrapper.orderByDesc(ApCodingQuestion::getId);
        IPage<ApCodingQuestion> result = questionMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);

        // 登录用户标记"练过"（匿名浏览不查询）
        Set<Long> answeredIds = new HashSet<>();
        List<ApCodingQuestion> records = result.getRecords();
        if (userId != null && records != null && !records.isEmpty()) {
            List<Long> questionIds = records.stream()
                .map(ApCodingQuestion::getId).filter(Objects::nonNull).collect(Collectors.toList());
            if (!questionIds.isEmpty()) {
                List<ApCodingAnswerRecord> answered = recordMapper.selectList(
                    new LambdaQueryWrapper<ApCodingAnswerRecord>()
                        .eq(ApCodingAnswerRecord::getUserId, userId)
                        .in(ApCodingAnswerRecord::getQuestionId, questionIds));
                answered.forEach(r -> answeredIds.add(r.getQuestionId()));
            }
        }

        List<CodingQuestionVO> list = new ArrayList<>();
        if (records != null) {
            for (ApCodingQuestion q : records) {
                CodingQuestionVO vo = buildQuestionVO(q, null);
                vo.setAnswered(answeredIds.contains(q.getId()));
                list.add(vo);
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        data.put("page", pageNo);
        data.put("size", pageSize);
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult myStat(Integer userId) {
        ApCodingUserStat stat = statMapper.selectOne(new LambdaQueryWrapper<ApCodingUserStat>()
            .eq(ApCodingUserStat::getUserId, userId));

        CodingStatVO vo = new CodingStatVO();
        vo.setTotalCount(stat == null ? 0 : nvl(stat.getTotalCount()));
        vo.setCorrectCount(stat == null ? 0 : nvl(stat.getCorrectCount()));
        vo.setPracticeCount(stat == null ? 0 : nvl(stat.getPracticeCount()));
        vo.setPracticeCorrectCount(stat == null ? 0 : nvl(stat.getPracticeCorrectCount()));
        vo.setAccuracy(percent(vo.getCorrectCount(), vo.getTotalCount()));
        vo.setPracticeAccuracy(percent(vo.getPracticeCorrectCount(), vo.getPracticeCount()));
        vo.setFirstAnswerDate(stat == null ? null : toDateString(stat.getFirstAnswerDate()));
        vo.setLastAnswerDate(stat == null ? null : toDateString(stat.getLastAnswerDate()));
        vo.setTagStats(parseTagStats(stat == null ? null : stat.getTagStats()));

        ApCodingAnswerRecord todayRecord = txService.findDailyRecord(
            userId, Date.valueOf(LocalDate.now()));
        vo.setTodayAnswered(todayRecord != null);
        if (todayRecord != null) {
            vo.setTodayCorrect(todayRecord.getIsCorrect() != null && todayRecord.getIsCorrect() == 1);
        }
        vo.setContinuousDays(currentContinuousDays(userId));
        return ResponseResult.okResult(vo);
    }

    // ==================== 内部方法 ====================

    private String dailyKey(Integer userId, Date today) {
        return DAILY_KEY_PREFIX + userId + ":" + today;
    }

    /** 读取缓存题目；缓存内容异常或题目已下架时返回 null（走重抽） */
    private ApCodingQuestion loadCachedQuestion(String key) {
        String cachedId = redisTemplate.opsForValue().get(key);
        if (cachedId == null || cachedId.isBlank()) {
            return null;
        }
        try {
            ApCodingQuestion question = questionMapper.selectById(Long.valueOf(cachedId.trim()));
            if (question != null && question.getStatus() != null
                && question.getStatus() == ApCodingQuestion.STATUS_PUBLISHED) {
                return question;
            }
            return null;
        } catch (NumberFormatException e) {
            log.warn("今日题缓存值异常: key={}, value={}", key, cachedId);
            return null;
        }
    }

    /** 抽题兜底链：指定难度未答 → 不限难度未答 → 指定难度任意 → 不限难度任意（题库小不断供） */
    private ApCodingQuestion pickQuestion(Integer userId, Integer difficulty) {
        ApCodingQuestion question = questionMapper.selectRandomUnanswered(userId, difficulty);
        if (question == null) {
            question = questionMapper.selectRandomUnanswered(userId, null);
        }
        if (question == null) {
            question = questionMapper.selectRandomAny(difficulty);
        }
        if (question == null) {
            question = questionMapper.selectRandomAny(null);
        }
        return question;
    }

    /**
     * 难度自适应：按历史正确率定档（样本不足按入门起步）。
     * 口径：每日一题与自由练习合并计算——练习同样是能力信号。
     */
    private int adaptiveDifficulty(Integer userId) {
        ApCodingUserStat stat = statMapper.selectOne(new LambdaQueryWrapper<ApCodingUserStat>()
            .eq(ApCodingUserStat::getUserId, userId));
        if (stat == null) {
            return ApCodingQuestion.DIFFICULTY_EASY;
        }
        int answered = nvl(stat.getTotalCount()) + nvl(stat.getPracticeCount());
        int correct = nvl(stat.getCorrectCount()) + nvl(stat.getPracticeCorrectCount());
        if (answered < ADAPTIVE_MIN_SAMPLES) {
            return ApCodingQuestion.DIFFICULTY_EASY;
        }
        double rate = correct * 1.0 / answered;
        if (rate < ADAPTIVE_EASY_MAX) {
            return ApCodingQuestion.DIFFICULTY_EASY;
        }
        return rate < ADAPTIVE_MEDIUM_MAX
            ? ApCodingQuestion.DIFFICULTY_MEDIUM : ApCodingQuestion.DIFFICULTY_HARD;
    }

    /** 记账逐日等级分；失败/上限已满返回 0（判分结果不受影响） */
    private int recordLevelScore(Integer userId, Long questionId) {
        try {
            Map<String, Object> result = levelService.recordActionWithLimit(userId.longValue(),
                LevelScoreActionCode.ANSWER_QUESTION, "每日一题答对，题目ID:" + questionId);
            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                Object score = result.get("score");
                if (score instanceof BigDecimal bd) {
                    return bd.intValue();
                }
                return score == null ? 0 : toInt(score);
            }
        } catch (Exception e) {
            log.warn("记录答题等级行为失败: userId={}, questionId={}", userId, questionId, e);
        }
        return 0;
    }

    /** 幂等打卡（reward 服务内部端点，fallback fail-open 返回 0）；返回最新连续天数 */
    private Integer completeCheckin(Integer userId) {
        try {
            ResponseResult result = rewardClient.completeCheckin(userId.longValue());
            if (result != null && result.getData() instanceof Map<?, ?> data
                && data.get("continuousDays") != null) {
                return toInt(data.get("continuousDays"));
            }
        } catch (Exception e) {
            log.warn("答题触发打卡失败（判分不受影响）: userId={}", userId, e);
        }
        return 0;
    }

    /** 查询最新连续天数（签到体系，不可用时降级 0） */
    private Integer currentContinuousDays(Integer userId) {
        try {
            ResponseResult result = rewardClient.getContinuousCheckinDays(userId.longValue());
            if (result != null && result.getData() instanceof Map<?, ?> data
                && data.get("continuousDays") != null) {
                return toInt(data.get("continuousDays"));
            }
        } catch (Exception e) {
            log.warn("获取连续签到天数失败: userId={}", userId, e);
        }
        return 0;
    }

    private CodingQuestionVO buildQuestionVO(ApCodingQuestion question, ApCodingAnswerRecord record) {
        CodingQuestionVO vo = new CodingQuestionVO();
        vo.setId(question.getId());
        vo.setStem(question.getStem());
        vo.setQuestionType(question.getQuestionType());
        vo.setOptions(parseStringList(question.getOptions()));
        vo.setDifficulty(question.getDifficulty());
        vo.setTags(parseTags(question.getTags()));
        vo.setSourceType(question.getSourceType());
        vo.setAnswerCount(nvl(question.getAnswerCount()));
        vo.setCorrectCount(nvl(question.getCorrectCount()));
        vo.setAnswered(record != null);
        fillSourceArticle(vo, question.getSourceArticleId());
        if (record != null) {
            vo.setUserAnswer(parseIntList(record.getUserAnswer()));
            vo.setIsCorrect(record.getIsCorrect() != null && record.getIsCorrect() == 1);
            vo.setCorrectAnswer(parseIntList(question.getAnswer()));
            vo.setExplanation(question.getExplanation());
            vo.setElapsedSeconds(record.getElapsedSeconds());
            vo.setScoreAwarded(record.getScoreAwarded());
        }
        return vo;
    }

    /** 来源文章只在"已发布"时回填（未发布/已删则不给跳转入口） */
    private void fillSourceArticle(CodingQuestionVO vo, Long articleId) {
        if (articleId == null) {
            return;
        }
        try {
            ApArticle article = articleMapper.selectById(articleId);
            if (article != null && article.isPublished()) {
                vo.setSourceArticleId(articleId);
                vo.setSourceArticleTitle(article.getTitle());
            }
        } catch (Exception e) {
            log.warn("加载题目来源文章失败, articleId={}", articleId, e);
        }
    }

    /** 作答结果 VO 的来源文章回填（CodingAnswerVO 与题目 VO 字段名一致，单独重载） */
    private void fillSourceArticle(CodingAnswerVO vo, Long articleId) {
        if (articleId == null) {
            return;
        }
        try {
            ApArticle article = articleMapper.selectById(articleId);
            if (article != null && article.isPublished()) {
                vo.setSourceArticleId(articleId);
                vo.setSourceArticleTitle(article.getTitle());
            }
        } catch (Exception e) {
            log.warn("加载题目来源文章失败, articleId={}", articleId, e);
        }
    }

    /** 批量取昵称头像（用户服务不可用时留空，不拖垮榜单） */
    private Map<Long, Map<String, String>> loadUserInfos(List<Long> userIds) {
        Map<Long, Map<String, String>> result = new HashMap<>();
        if (userClient == null || userIds == null || userIds.isEmpty()) {
            return result;
        }
        try {
            ResponseResult res = userClient.getBasicInfoBatch(userIds);
            if (res == null || res.getCode() == null || res.getCode() != 200
                || !(res.getData() instanceof Map<?, ?> data)) {
                return result;
            }
            for (Map.Entry<?, ?> entry : data.entrySet()) {
                Long uid = toLong(entry.getKey());
                if (uid == null || !(entry.getValue() instanceof Map<?, ?> info)) {
                    continue;
                }
                Map<String, String> pair = new HashMap<>();
                pair.put("name", info.get("nickname") == null ? "" : String.valueOf(info.get("nickname")));
                pair.put("avatar", info.get("avatar") == null ? "" : String.valueOf(info.get("avatar")));
                result.put(uid, pair);
            }
        } catch (Exception e) {
            log.warn("批量解析榜单用户信息失败, size={}", userIds.size(), e);
        }
        return result;
    }

    private Map<String, Object> parseTagStats(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析领域分布失败: {}", json);
            return new HashMap<>();
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("解析选项 JSON 失败: {}", json);
            return new ArrayList<>();
        }
    }

    private List<Integer> parseIntList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Integer> list = objectMapper.readValue(json, new TypeReference<List<Integer>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("解析答案下标 JSON 失败: {}", json);
            return new ArrayList<>();
        }
    }

    private List<String> parseTags(String tags) {
        List<String> list = new ArrayList<>();
        if (tags == null || tags.isBlank()) {
            return list;
        }
        for (String raw : tags.split(",")) {
            String tag = raw == null ? "" : raw.trim();
            if (!tag.isEmpty()) {
                list.add(tag);
            }
        }
        return list;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static boolean isValidDifficulty(Integer difficulty) {
        return difficulty != null && difficulty >= ApCodingQuestion.DIFFICULTY_EASY
            && difficulty <= ApCodingQuestion.DIFFICULTY_HARD;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static int percent(int part, int total) {
        return total <= 0 ? 0 : (int) Math.round(part * 100.0 / total);
    }

    private static String toDateString(java.util.Date date) {
        if (date == null) {
            return null;
        }
        if (date instanceof Date sqlDate) {
            return sqlDate.toLocalDate().toString();
        }
        return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
    }

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            // 四舍五入：榜单平均用时来自 SQL AVG（BigDecimal），截断会少 1 秒
            return (int) Math.round(number.doubleValue());
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}