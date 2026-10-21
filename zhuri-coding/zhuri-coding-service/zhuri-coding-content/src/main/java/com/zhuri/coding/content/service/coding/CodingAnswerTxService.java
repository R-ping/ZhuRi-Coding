package com.zhuri.coding.content.service.coding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingDailyPoolMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingDailyPool;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日一题作答落库事务（Coding 延展第一层 · 简答）
 *
 * <p>提交作答拆成两段：{@link #claimAnswer} 先用一条带条件的 UPDATE 把答案与状态落下，
 * {@link #saveEvaluation} 再把评估结果补写到同一行。模型调用夹在两段中间，
 * 于是并发的第二个请求在第一段就被挡住，不会白跑一次评估 —— 这是把评估挪到落库之后的原因。</p>
 *
 * <p>主流程里的等级分跨服务调用仍在事务外，失败只降级不拖垮落库结果。</p>
 *
 * <p>与旧版相比少了两件事：不再有「当日一题 / 自由练习」的分叉，
 * 也不再有「题目热度计数」—— 那两样都是选择题时代的产物。</p>
 */
@Slf4j
@Service
public class CodingAnswerTxService {

    /** 领域分布里单题最多取前 N 个标签，避免标签噪声把分布拉散 */
    private static final int MAX_TAGS_PER_QUESTION = 3;

    @Autowired
    private ApCodingAnswerRecordMapper recordMapper;

    @Autowired
    private ApCodingUserStatMapper statMapper;

    @Autowired
    private ApCodingDailyPoolMapper poolMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 当天是否已作答（也是"今日题回放"的判定依据） */
    public ApCodingAnswerRecord findDailyRecord(Integer userId, Date answerDate) {
        return recordMapper.selectOne(new LambdaQueryWrapper<ApCodingAnswerRecord>()
            .eq(ApCodingAnswerRecord::getUserId, userId)
            .eq(ApCodingAnswerRecord::getAnswerDate, answerDate)
            .last("LIMIT 1"));
    }

    /**
     * 开始作答时落一条 {@code status = 未作答} 的流水。
     *
     * <p>抽题不落库 —— 用户可能连换十几道才挑中一道。落进库的是「他开始写的那一道」：
     * 中途退出或关掉页面，这条记录就停在「未作答」，每日作答历史里看得见没交。
     *
     * <p>同一天只允许一行（{@code uk_user_date}）。并发/重入撞唯一键不报错：
     * <b>回读并返回已存在的那条</b>，调用方以返回值为准。
     *
     * @return 今天这条流水（可能是本次新建的，也可能是别处已经建好的）
     */
    @Transactional(rollbackFor = Exception.class)
    public ApCodingAnswerRecord issueQuestion(Integer userId, Date answerDate, Long poolId) {
        ApCodingAnswerRecord record = new ApCodingAnswerRecord();
        record.setUserId(userId);
        record.setPoolId(poolId);
        record.setAnswerDate(answerDate);
        // user_answer 是 NOT NULL，未作答时用空串占位（空串在提交侧会被拒，不会是有效作答）
        record.setUserAnswer("");
        record.setScoreAwarded(0);
        record.setStatus(ApCodingAnswerRecord.STATUS_ISSUED);
        record.setCreatedTime(new Date());
        try {
            recordMapper.insert(record);
            // 轮转计数不在这里加：它算的是「真做过的次数」，换题反复抽不该把它推高
            return record;
        } catch (DuplicateKeyException e) {
            ApCodingAnswerRecord existing = findDailyRecord(userId, answerDate);
            if (existing == null) {
                // 撞了唯一键却读不到行：不该发生，不吞
                throw e;
            }
            return existing;
        }
    }

    /**
     * 换题重新开始：改掉今天这条流水指向的题（只在「未作答」时生效）。
     *
     * <p>不能改成"再插一条"—— {@code uk_user_date} 一天只允许一行。
     *
     * @return 是否改成功（false = 已经作答或行不存在，调用方应改用原题）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean reissueQuestion(Long recordId, Long poolId) {
        ApCodingAnswerRecord update = new ApCodingAnswerRecord();
        update.setId(recordId);
        update.setPoolId(poolId);
        return recordMapper.update(update, new LambdaUpdateWrapper<ApCodingAnswerRecord>()
            .eq(ApCodingAnswerRecord::getId, recordId)
            .eq(ApCodingAnswerRecord::getStatus, ApCodingAnswerRecord.STATUS_ISSUED)) > 0;
    }

    /**
     * 累加被做次数（轮转依据）。
     *
     * <p>只由 {@link #claimAnswer} 调用 —— 计数算的是「确认作答」的次数，
     * 抽题和换题都不动它，否则用户连点几次换题就能把一批题的计数推高。
     * 计数只是轮转优化项，失败不影响出题，所以吞掉只记日志。</p>
     */
    private void bumpUseCount(Long poolId) {
        try {
            poolMapper.incrementUseCount(poolId);
        } catch (Exception e) {
            log.warn("[CodingDaily] 轮转计数失败, poolId={}", poolId, e);
        }
    }

    /**
     * 第一段 —— 抢占这次作答：把答案、用时与状态跳变写成一条带条件的 UPDATE。
     *
     * <p><b>为什么是 UPDATE 而不是 INSERT</b>：行在「开始作答」时就已经落库了。
     * 条件里的 {@code status = 未作答} 就是「抢」的全部含义：并发的第二个请求影响 0 行，
     * 拿到 {@code false} 直接出局。唯一性不依赖 Redis 防抖锁，也不依赖任何锁。
     *
     * <p><b>为什么先抢再评估</b>：评估是整条链路最慢的一步。抢在前头，
     * 输的那次根本走不到模型那一步；换过来先评估后抢占，两个并发请求就会各跑一遍 LLM。
     *
     * <p>抢到才算「这道题真做过」，轮转计数也在这里加 —— 抽题、换题都不加。</p>
     *
     * @param poolId 抢下的这道题，用于累加被做次数
     * @return 是否抢到；false = 没开始作答、已经作答、或在并发里被人抢先
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean claimAnswer(Long recordId, Long poolId, String answerText, Integer elapsedSeconds) {
        boolean claimed = recordMapper.update(null, new LambdaUpdateWrapper<ApCodingAnswerRecord>()
            .eq(ApCodingAnswerRecord::getId, recordId)
            .eq(ApCodingAnswerRecord::getStatus, ApCodingAnswerRecord.STATUS_ISSUED)
            .set(ApCodingAnswerRecord::getUserAnswer, answerText)
            .set(ApCodingAnswerRecord::getElapsedSeconds, elapsedSeconds)
            .set(ApCodingAnswerRecord::getStatus, ApCodingAnswerRecord.STATUS_ANSWERED)) > 0;
        if (claimed) {
            bumpUseCount(poolId);
        }
        return claimed;
    }

    /**
     * 第二段 —— 把评估结果补写到已经作答的那一行，并累加用户统计。
     *
     * <p>只补写，不碰状态：状态在第一段就已经是「已作答」了。所以这段失败不影响
     * 「作答成立」—— 用户下一次刷新看到的是答案在、评估空，而不是答案没了。
     * 调用方把异常降级成日志即可，不必也不该让整个提交失败。</p>
     *
     * @param evaluation 评估结果；{@code pending=true} 时等级写空，其余字段照写
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveEvaluation(Integer userId, Long recordId, ApCodingDailyPool question,
                               Date answerDate, CodingDailyEvaluator.Result evaluation) {
        int updated = recordMapper.update(null, new LambdaUpdateWrapper<ApCodingAnswerRecord>()
            .eq(ApCodingAnswerRecord::getId, recordId)
            .eq(ApCodingAnswerRecord::getStatus, ApCodingAnswerRecord.STATUS_ANSWERED)
            // level 为 null 表示"这次没评出来"：显式写空，别让上一轮的等级残留
            .set(ApCodingAnswerRecord::getLevel, evaluation.pending ? null : evaluation.level)
            .set(ApCodingAnswerRecord::getFeedback, evaluation.comment)
            .set(ApCodingAnswerRecord::getCovered, CodingJson.writeJson(evaluation.covered))
            .set(ApCodingAnswerRecord::getMissing, CodingJson.writeJson(evaluation.missing)));
        if (updated == 0) {
            // 第一段必然已把状态改成已作答，命中 0 行只可能是流水被删：统计照加，不抛
            log.warn("[CodingDaily] 评估回写未命中已作答流水, recordId={}", recordId);
        }
        upsertUserStat(userId, question, answerDate, evaluation);
    }

    /**
     * 用户统计 upsert：读改写合并 {@code tag_stats}；唯一键 {@code uk_user} 兜底并发首插。
     *
     * <p>{@code tag_stats} 累计的是「等级和」而非答对数：{@code {"Redis":{"total":3,"levelSum":11}}}。
     * 未评估（pending）的作答计入 total 但不计入 levelSum —— 没评出来不该污染平均等级。</p>
     */
    private void upsertUserStat(Integer userId, ApCodingDailyPool question, Date today,
                                CodingDailyEvaluator.Result evaluation) {
        ApCodingUserStat stat = statMapper.selectOne(new LambdaQueryWrapper<ApCodingUserStat>()
            .eq(ApCodingUserStat::getUserId, userId));
        if (stat == null) {
            stat = new ApCodingUserStat();
            stat.setUserId(userId);
            stat.setTotalCount(0);
            stat.setFirstAnswerDate(today);
            stat.setLastAnswerDate(today);
            stat.setCreatedTime(new Date());
            stat.setUpdatedTime(new Date());
            applyCounts(stat, question, evaluation);
            try {
                statMapper.insert(stat);
                return;
            } catch (DuplicateKeyException e) {
                // 并发下另一请求已完成首插：回读后走更新分支
                stat = statMapper.selectOne(new LambdaQueryWrapper<ApCodingUserStat>()
                    .eq(ApCodingUserStat::getUserId, userId));
                if (stat == null) {
                    log.warn("用户编码统计并发 upsert 回读失败, userId={}", userId);
                    return;
                }
            }
        }
        // 注意：读改写分支可能与并发请求相互覆盖。统计属可容忍最终一致的聚合值，
        // 且 tag_stats 需要基于既有 JSON 合并，无法用单条原子 SQL 表达（见 Mapper 注释）
        stat.setLastAnswerDate(today);
        applyCounts(stat, question, evaluation);
        stat.setUpdatedTime(new Date());
        statMapper.updateById(stat);
    }

    /** 累加计数与领域分布（不负责 first/last 日期赋值与落库） */
    private void applyCounts(ApCodingUserStat stat, ApCodingDailyPool question,
                             CodingDailyEvaluator.Result evaluation) {
        stat.setTotalCount(nvl(stat.getTotalCount()) + 1);
        // 方向随作答一起落库：抽的是哪个方向的题，就是用户当前的方向偏好。
        // 这样下一次抽题不必再让用户选（today 的 resolveDirection 会读到它）。
        if (question.getDirection() != null && !question.getDirection().isBlank()) {
            stat.setDirection(question.getDirection());
        }
        stat.setTagStats(mergeTagStats(stat.getTagStats(), question.getTags(),
            evaluation.pending ? null : evaluation.level));
    }

    /**
     * 合并领域答题分布：{@code {"Redis":{"total":3,"levelSum":11}}}。
     *
     * <p>单题最多取前 3 个标签，避免标签噪声把分布拉散。
     * {@code level} 为 null（未评估）时只加 total。</p>
     */
    private String mergeTagStats(String existingJson, String tags, Integer level) {
        if (tags == null || tags.isBlank()) {
            return existingJson;
        }
        Map<String, Map<String, Integer>> stats = new LinkedHashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                Map<String, Map<String, Integer>> parsed = objectMapper.readValue(
                    existingJson, new TypeReference<Map<String, Map<String, Integer>>>() {});
                if (parsed != null) {
                    stats.putAll(parsed);
                }
            } catch (Exception e) {
                log.warn("解析领域分布 JSON 失败，按空分布重建: {}", existingJson);
            }
        }
        int used = 0;
        for (String raw : tags.split(",")) {
            String tag = raw == null ? "" : raw.trim();
            if (tag.isEmpty() || tag.length() > 30) {
                continue;
            }
            Map<String, Integer> item = stats.computeIfAbsent(tag, k -> new LinkedHashMap<>());
            item.put("total", nvl(item.get("total")) + 1);
            if (level != null) {
                item.put("levelSum", nvl(item.get("levelSum")) + level);
            }
            if (++used >= MAX_TAGS_PER_QUESTION) {
                break;
            }
        }
        try {
            return objectMapper.writeValueAsString(stats);
        } catch (Exception e) {
            log.warn("写回领域分布 JSON 失败", e);
            return existingJson;
        }
    }

    /** 最近若干次作答（统计/档案用，按作答日期倒序） */
    public List<ApCodingAnswerRecord> listRecent(Integer userId, int limit) {
        return recordMapper.selectList(new LambdaQueryWrapper<ApCodingAnswerRecord>()
            .eq(ApCodingAnswerRecord::getUserId, userId)
            .orderByDesc(ApCodingAnswerRecord::getAnswerDate)
            .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
