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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p><b>作答记录什么时候落库</b>：抽题不落库 —— 用户抽到不想做的可以一直换，换多少次都不留痕迹。
 * 点「开始作答」时才落一条 {@code status = 未作答} 的行，提交后改成 {@code 已作答}；
 * 中途退出或关掉页面，它就停在未作答，每日作答历史里看得见。
 * 所以这个功能不依赖 Redis —— 接着做、刷新看到同一道题、提交时校验题目归属，读的都是库里的那一行。</p>
 */
@Slf4j
@Service
public class CodingQuestionServiceImpl implements CodingQuestionService {

    /** 兜底方向：用户没选过、也没显式传时用它 */
    private static final String DEFAULT_DIRECTION = "Java 后端";
    /** 方向长度上限（与模拟面试 direction 同口径） */
    private static final int DIRECTION_MAX_LENGTH = 64;
    /** 池子里抽不到可用题时的提示 */
    private static final String POOL_EMPTY_MSG = "题库准备中，暂无可用题目，请稍后再来";

    /**
     * 业务日时区：显式钉在 Asia/Shanghai，与 CheckinTxService / AiQuotaServiceImpl 同口径。
     *
     * <p>不用裸 {@code LocalDate.now()} —— 那取的是 JVM 默认时区，容器跑在 UTC 时会比签到晚 8 小时跨天，
     * 出现「签到已算今天、每日一题还算昨天」的口径错位。</p>
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 今日题 ====================

    @Override
    public ResponseResult today(Integer userId, String direction, Boolean refresh) {
        Date today = Date.valueOf(LocalDate.now(ZONE));
        ApCodingAnswerRecord record = txService.findDailyRecord(userId, today);

        // 1. 已作答：回放完整结果（含等级与考点清单）。
        //    题目此刻是否仍在启用态不影响回放 —— 答过的结果不该因为运营下架就消失。
        if (record != null && ApCodingAnswerRecord.isAnswered(record)) {
            ApCodingDailyPool answered = poolMapper.selectById(record.getPoolId());
            if (answered == null) {
                log.warn("[CodingDaily] 已作答流水的题目不存在, userId={}, poolId={}",
                    userId, record.getPoolId());
                return ResponseResult.errorResult(400, POOL_EMPTY_MSG);
            }
            return ResponseResult.okResult(buildQuestionVO(answered, record));
        }

        // 2. 已经点了「开始作答」但还没提交，且这次不是换题：把正在做的那道再发一次，
        //    刷新页面不会把题目换掉。
        if (record != null && !Boolean.TRUE.equals(refresh)) {
            ApCodingDailyPool doing = poolMapper.selectById(record.getPoolId());
            if (isEnabled(doing)) {
                CodingDailyQuestionVO vo = buildQuestionVO(doing, null);
                // 他已经在写这道了：前端据此直接亮出答题区，而不是再要一次「开始作答」
                vo.setStarted(true);
                return ResponseResult.okResult(vo);
            }
            log.warn("[CodingDaily] 未作答流水的题目不可用，重新抽题, userId={}, poolId={}",
                userId, record.getPoolId());
        }

        // 3. 抽题是纯读：按方向抽一道返回，不写任何记录。
        //    用户抽到不想做的，再调一次就是换一道 —— 换多少次都不会留下流水。
        String resolved = resolveDirection(userId, direction);
        ApCodingDailyPool picked = pickQuestion(resolved);
        if (picked == null) {
            return ResponseResult.errorResult(400, POOL_EMPTY_MSG);
        }
        return ResponseResult.okResult(buildQuestionVO(picked, null));
    }

    @Override
    public ResponseResult startAnswer(Integer userId, Long poolId) {
        if (poolId == null) {
            return ResponseResult.errorResult(400, "题目ID不能为空");
        }
        ApCodingDailyPool question = poolMapper.selectById(poolId);
        if (!isEnabled(question)) {
            return ResponseResult.errorResult(400, "题目不存在或已下架");
        }
        Date today = Date.valueOf(LocalDate.now(ZONE));
        ApCodingAnswerRecord record = txService.findDailyRecord(userId, today);
        if (record != null && ApCodingAnswerRecord.isAnswered(record)) {
            return ResponseResult.errorResult(400, "今日一题已作答，明天再来");
        }
        // 开始作答才落库，且默认是「未作答」：用户中途退出或关掉页面，
        // 这条记录就停在未作答，每日作答历史里能看出他没交。
        if (record == null) {
            txService.issueQuestion(userId, today, poolId);
        } else if (!record.getPoolId().equals(poolId)) {
            // 又抽了一道新的才开始：改掉这条流水指向的题（同一天只允许一行）
            txService.reissueQuestion(record.getId(), poolId);
        }
        return ResponseResult.okResult();
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

        // 快路径校验：提交的必须是「他开始作答的那道」，且还没作答。
        // 这一步只为了给一句准确的提示，真正的并发闸门是下面 CAS 的状态条件。
        Date today = Date.valueOf(LocalDate.now(ZONE));
        ApCodingAnswerRecord record = txService.findDailyRecord(userId, today);
        if (record == null) {
            return ResponseResult.errorResult(400, "请先开始作答");
        }
        if (ApCodingAnswerRecord.isAnswered(record)) {
            return ResponseResult.errorResult(400, "今日一题已作答，明天再来");
        }
        if (!record.getPoolId().equals(dto.getPoolId())) {
            return ResponseResult.errorResult(400, "题目已更换，请重新开始作答");
        }

        // 第一段：先把作答 CAS 落库。抢不到说明并发里另一次提交已经赢了，
        // 这一次到此为止 —— 模型调用是最慢、最贵的一步，输的那次不该白跑。
        if (!txService.claimAnswer(record.getId(), question.getId(), answerText,
            normalizeElapsed(dto.getElapsedSeconds()))) {
            return ResponseResult.errorResult(400, "今日一题已作答，明天再来");
        }

        // 第二段：评估（模型调用，放事务外），再把结果补写到刚抢下的那一行。
        // 作答此刻已经落库，回写失败只降级：本次仍把评估结果返回给用户，不让他重写一遍答案。
        CodingDailyEvaluator.Result evaluation = evaluator.evaluate(question, answerText);
        try {
            txService.saveEvaluation(userId, record.getId(), question, today, evaluation);
        } catch (Exception e) {
            log.error("[CodingDaily] 评估结果回写失败（作答已落库）, userId={}, recordId={}",
                userId, record.getId(), e);
        }

        // 完成即记逐日分：简答没有对错，所以不按对错给分。
        // 「一天一次」由作答流水的 uk_user_date 保证，等级分侧的 daily_limit 对它永不触发，只是冗余兜底。
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

        ApCodingAnswerRecord todayRecord = txService.findDailyRecord(userId, Date.valueOf(LocalDate.now(ZONE)));
        // 开始作答但没提交也有流水，所以「今天答过没」要看状态，不能只看有没有行
        boolean answeredToday = ApCodingAnswerRecord.isAnswered(todayRecord);
        vo.setTodayAnswered(answeredToday);
        if (answeredToday) {
            vo.setTodayLevel(todayRecord.getLevel());
        }
        return ResponseResult.okResult(vo);
    }

    // ==================== 内部方法 ====================

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

    /** 抽题：方向池优先；方向池暂时没有可用题时兜底到全池，不让用户看到空页 */
    private ApCodingDailyPool pickQuestion(String direction) {
        ApCodingDailyPool question = poolMapper.selectOneByDirection(direction);
        if (question == null) {
            log.info("[CodingDaily] 方向池暂无可用题，降级到全池抽题: direction={}", direction);
            question = poolMapper.selectOneAny();
        }
        return question;
    }

    /** 题目是否还在启用态（下架后不再继续发，但已作答的回放不受影响） */
    private static boolean isEnabled(ApCodingDailyPool question) {
        return question != null && question.getStatus() != null
            && question.getStatus() == ApCodingDailyPool.STATUS_ENABLED;
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
        // 只有"回放已答"与"抽到新题"走这里，都不是"正在写"的状态
        vo.setStarted(false);
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
