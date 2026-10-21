package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.content.constants.LevelScoreActionCode;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingDailyPoolMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.coding.CodingAnswerTxService;
import com.zhuri.coding.content.service.coding.CodingDailyEvaluator;
import com.zhuri.coding.content.service.coding.CodingJson;
import com.zhuri.coding.content.service.coding.CodingQuestionService;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.coding.dtos.CodingDailyAnswerDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingDailyPool;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingDailyAnswerVO;
import com.zhuri.coding.model.coding.vos.CodingDailyQuestionVO;
import com.zhuri.coding.model.coding.vos.CodingStatVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 每日一题服务实现（Coding 延展第一层 · 简答）
 *
 * <p><b>题型为什么是简答</b>：选择题只能判断"记不记得一个事实"，
 * 而这一层要判断的是"能不能把一件事讲清楚"。换成简答之后，判分从"答案集合比对"
 * 变成"对照关键考点判断讲到没讲到"，评分锚点因此成为这一层的生命线。</p>
 *
 * <p><b>题目为什么来自题目池而不是模型现生成</b>：见 {@link CodingDailyEvaluator}。
 * 一句话 —— 让模型既出题又判分，等于自己出题自己批，锚点不可信。</p>
 *
 * <p><b>缓存为什么换了 key 前缀</b>：旧缓存 {@code coding:daily:} 存的是
 * {@code ap_coding_question} 的 id，而新链路读的是 {@code ap_coding_daily_pool} 的 id。
 * 沿用旧前缀会在 26h 内把两种 id 混起来，直接串题。故改用 {@code coding:daily2:}，
 * 让旧缓存自然过期。</p>
 */
@Slf4j
@Service
public class CodingQuestionServiceImpl implements CodingQuestionService {

    /** 今日题缓存（key 带日期，跨天自然失效）；前缀带 2 是为了不与旧的选择题缓存串号 */
    private static final String DAILY_KEY_PREFIX = "coding:daily2:";
    /** 今日题提交防抖锁（防双击并发写入两条记录） */
    private static final String ANSWER_LOCK_PREFIX = "coding:answer:lock:";
    /** 今日题缓存 TTL：26h 覆盖全天（key 带日期，不会跨天串题） */
    private static final long DAILY_CACHE_TTL_HOURS = 26L;
    /** 兜底方向：用户没选过、也没显式传时用它 */
    private static final String DEFAULT_DIRECTION = "Java 后端";
    /** 方向长度上限（与模拟面试 direction 同口径） */
    private static final int DIRECTION_MAX_LENGTH = 64;

    @Autowired
    private ApCodingDailyPoolMapper poolMapper;

    @Autowired
    private ApCodingAnswerRecordMapper recordMapper;

    @Autowired
    private ApCodingUserStatMapper statMapper;

    @Autowired
    private CodingAnswerTxService txService;

    @Autowired
    private CodingDailyEvaluator evaluator;

    @Autowired
    private LevelService levelService;

    @Autowired
    private IRewardClient rewardClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 今日题 ====================

    @Override
    public ResponseResult today(Integer userId, String direction) {
        Date today = Date.valueOf(LocalDate.now());

        // 1. 今天已答：回放完整结果（含等级与考点清单），不再抽题
        ApCodingAnswerRecord record = txService.findDailyRecord(userId, today);
        if (record != null) {
            ApCodingDailyPool answered = poolMapper.selectById(record.getPoolId());
            if (answered != null) {
                return ResponseResult.okResult(buildQuestionVO(answered, record));
            }
            // 题目被删（理论不发生）：按未答继续抽题，保证不断供
            log.warn("[CodingDaily] 作答记录指向的题目已不存在, userId={}, poolId={}", userId, record.getPoolId());
        }

        // 2. 命中缓存：同一用户同一天固定一题；显式切换方向时重抽
        String resolved = resolveDirection(userId, direction);
        String key = dailyKey(userId, today);
        ApCodingDailyPool question = loadCachedQuestion(key, resolved);

        // 3. 未命中：按方向抽题并缓存
        if (question == null) {
            question = pickQuestion(resolved);
            if (question == null) {
                return ResponseResult.errorResult(400, "题库准备中，暂无可用题目，请稍后再来");
            }
            redisTemplate.opsForValue().set(key, String.valueOf(question.getId()),
                DAILY_CACHE_TTL_HOURS, TimeUnit.HOURS);
            try {
                poolMapper.incrementUseCount(question.getId());
            } catch (Exception e) {
                // 轮转计数是优化项，失败不影响出题
                log.warn("[CodingDaily] 轮转计数失败, poolId={}", question.getId(), e);
            }
        }
        return ResponseResult.okResult(buildQuestionVO(question, null));
    }

    @Override
    public ResponseResult answer(Integer userId, CodingDailyAnswerDTO dto) {
        if (dto == null || dto.getPoolId() == null) {
            return ResponseResult.errorResult(400, "题目ID不能为空");
        }
        String answerText = dto.getAnswerText() == null ? "" : dto.getAnswerText().trim();
        if (answerText.isEmpty()) {
            return ResponseResult.errorResult(400, "请先写下你的答案");
        }
        if (answerText.length() > CodingDailyEvaluator.ANSWER_MAX_LENGTH) {
            return ResponseResult.errorResult(400,
                "答案过长（最多 " + CodingDailyEvaluator.ANSWER_MAX_LENGTH + " 字）");
        }
        ApCodingDailyPool question = poolMapper.selectById(dto.getPoolId());
        if (question == null || question.getStatus() == null
            || question.getStatus() != ApCodingDailyPool.STATUS_ENABLED) {
            return ResponseResult.errorResult(400, "题目不存在或已下架");
        }

        Date today = Date.valueOf(LocalDate.now());
        if (txService.findDailyRecord(userId, today) != null) {
            return ResponseResult.errorResult(400, "今日一题已作答，明天再来");
        }

        // 防抖锁：双击/并发下只放行一次写入。真正的唯一性由 uk_user_date 保证，这里只是拦重复。
        String lockKey = ANSWER_LOCK_PREFIX + userId + ":" + today;
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            return ResponseResult.errorResult(429, "操作过于频繁，请稍后重试");
        }

        // 评估放事务外：模型调用慢且可能失败，不该占着数据库事务
        CodingDailyEvaluator.Result evaluation = evaluator.evaluate(question, answerText);

        ApCodingAnswerRecord record;
        try {
            record = txService.saveAnswer(userId, question, answerText,
                normalizeElapsed(dto.getElapsedSeconds()), evaluation);
        } catch (IllegalStateException e) {
            return ResponseResult.errorResult(400, "今日一题已作答，明天再来");
        } finally {
            redisTemplate.delete(lockKey);
        }

        // 完成即记逐日分：简答没有对错，所以不按对错给分（等级分链路自带日上限 1 次）
        int scoreAwarded = recordLevelScore(userId, question.getId());
        if (scoreAwarded > 0) {
            ApCodingAnswerRecord scoreUpdate = new ApCodingAnswerRecord();
            scoreUpdate.setId(record.getId());
            scoreUpdate.setScoreAwarded(scoreAwarded);
            try {
                recordMapper.updateById(scoreUpdate);
            } catch (Exception e) {
                log.warn("回填作答得分失败（不影响作答结果）, recordId={}", record.getId(), e);
            }
        }

        return ResponseResult.okResult(buildAnswerVO(question.getId(), evaluation, scoreAwarded));
    }

    @Override
    public ResponseResult myStat(Integer userId) {
        ApCodingUserStat stat = statMapper.selectOne(new LambdaQueryWrapper<ApCodingUserStat>()
            .eq(ApCodingUserStat::getUserId, userId));

        CodingStatVO vo = new CodingStatVO();
        vo.setTotalCount(stat == null ? 0 : nvl(stat.getTotalCount()));
        vo.setDirection(stat == null ? null : stat.getDirection());
        vo.setFirstAnswerDate(stat == null ? null : toDateString(stat.getFirstAnswerDate()));
        vo.setLastAnswerDate(stat == null ? null : toDateString(stat.getLastAnswerDate()));
        vo.setTagStats(parseTagStats(stat == null ? null : stat.getTagStats()));
        vo.setAvgLevel(stat == null ? null : recordMapper.avgLevel(userId));
        vo.setContinuousDays(currentContinuousDays(userId));

        ApCodingAnswerRecord todayRecord = txService.findDailyRecord(userId, Date.valueOf(LocalDate.now()));
        vo.setTodayAnswered(todayRecord != null);
        if (todayRecord != null) {
            vo.setTodayLevel(todayRecord.getLevel());
        }
        return ResponseResult.okResult(vo);
    }

    // ==================== 内部方法 ====================

    private String dailyKey(Integer userId, Date today) {
        return DAILY_KEY_PREFIX + userId + ":" + today;
    }

    /**
     * 解析抽题方向：显式传入 &gt; 用户上次设定 &gt; 默认方向。
     * 显式传入时会被持久化（在作答落库时随统计 upsert 一起写入），下次自动沿用。
     */
    private String resolveDirection(Integer userId, String direction) {
        if (direction != null && !direction.isBlank()) {
            String trimmed = direction.trim();
            return trimmed.length() > DIRECTION_MAX_LENGTH
                ? trimmed.substring(0, DIRECTION_MAX_LENGTH) : trimmed;
        }
        ApCodingUserStat stat = statMapper.selectOne(new LambdaQueryWrapper<ApCodingUserStat>()
            .eq(ApCodingUserStat::getUserId, userId));
        if (stat != null && stat.getDirection() != null && !stat.getDirection().isBlank()) {
            return stat.getDirection();
        }
        return DEFAULT_DIRECTION;
    }

    /**
     * 读缓存题目。缓存值异常、题目已停用、或与当前方向不一致时返回 null（走重抽）。
     *
     * <p>方向校验是必要的：用户显式切了方向，不能因为缓存把他锁死在旧方向上。</p>
     */
    private ApCodingDailyPool loadCachedQuestion(String key, String direction) {
        String cachedId = redisTemplate.opsForValue().get(key);
        if (cachedId == null || cachedId.isBlank()) {
            return null;
        }
        ApCodingDailyPool question;
        try {
            question = poolMapper.selectById(Long.valueOf(cachedId.trim()));
        } catch (NumberFormatException e) {
            log.warn("[CodingDaily] 缓存值异常: key={}, value={}", key, cachedId);
            return null;
        }
        if (question == null || question.getStatus() == null
            || question.getStatus() != ApCodingDailyPool.STATUS_ENABLED) {
            return null;
        }
        if (direction != null && !direction.equals(question.getDirection())) {
            return null;
        }
        return question;
    }

    /** 抽题兜底链：方向命中 → 不限方向（用户填了个冷门方向时也不至于断供） */
    private ApCodingDailyPool pickQuestion(String direction) {
        ApCodingDailyPool question = poolMapper.selectOneByDirection(direction);
        if (question == null) {
            log.info("[CodingDaily] 方向无题，降级到不限方向抽题: direction={}", direction);
            question = poolMapper.selectOneAny();
        }
        return question;
    }

    /** 作答用时归一化：负数丢弃，超过一天截断 */
    private static Integer normalizeElapsed(Integer elapsedSeconds) {
        if (elapsedSeconds == null || elapsedSeconds < 0) {
            return null;
        }
        return Math.min(elapsedSeconds, 24 * 3600);
    }

    /** 记逐日等级分；失败或日上限已满返回 0（作答结果不受影响） */
    private int recordLevelScore(Integer userId, Long poolId) {
        try {
            Map<String, Object> result = levelService.recordActionWithLimit(userId.longValue(),
                LevelScoreActionCode.ANSWER_QUESTION, "每日一题完成，题目ID:" + poolId);
            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                Object score = result.get("score");
                if (score instanceof BigDecimal bd) {
                    return bd.intValue();
                }
                return score == null ? 0 : toInt(score);
            }
        } catch (Exception e) {
            log.warn("记录答题等级行为失败: userId={}, poolId={}", userId, poolId, e);
        }
        return 0;
    }

    /** 连续签到天数（签到体系唯一来源，不可用时降级 0） */
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

    /** 题面组装。record 非空 = 今日已答回放，此时才带上等级与考点清单 */
    private CodingDailyQuestionVO buildQuestionVO(ApCodingDailyPool question, ApCodingAnswerRecord record) {
        CodingDailyQuestionVO vo = new CodingDailyQuestionVO();
        vo.setId(question.getId());
        vo.setStem(question.getStem());
        vo.setDirection(question.getDirection());
        vo.setDifficulty(question.getDifficulty());
        vo.setTags(CodingJson.parseTags(question.getTags()));
        vo.setAnswered(record != null);
        if (record != null) {
            vo.setUserAnswer(record.getUserAnswer());
            vo.setLevel(record.getLevel());
            vo.setFeedback(record.getFeedback());
            vo.setCovered(CodingJson.parseStringList(record.getCovered()));
            vo.setMissing(CodingJson.parseStringList(record.getMissing()));
            vo.setElapsedSeconds(record.getElapsedSeconds());
            vo.setScoreAwarded(record.getScoreAwarded());
        }
        return vo;
    }

    private CodingDailyAnswerVO buildAnswerVO(Long poolId, CodingDailyEvaluator.Result evaluation, int scoreAwarded) {
        CodingDailyAnswerVO vo = new CodingDailyAnswerVO();
        vo.setPoolId(poolId);
        vo.setPending(evaluation.pending);
        vo.setLevel(evaluation.level);
        vo.setStructure(evaluation.structure);
        vo.setCoverageScore(evaluation.coverageScore);
        vo.setAccuracy(evaluation.accuracy);
        vo.setCovered(evaluation.covered);
        vo.setMissing(evaluation.missing);
        vo.setFeedback(evaluation.comment);
        vo.setScoreAwarded(scoreAwarded);
        return vo;
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

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
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
            // 四舍五入：来自 SQL AVG 的是 BigDecimal，截断会少 1
            return (int) Math.round(number.doubleValue());
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
