package com.zhuri.coding.content.service.coding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingDailyPool;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import java.time.LocalDate;
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
 * <p>把「作答流水 + 用户统计」两处写入收进同一事务，与 {@code CheckinTxService} 同构：
 * 主流程（LLM 评估、等级分跨服务调用）放事务外，失败只降级不拖垮落库结果。</p>
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 当天是否已作答（也是"今日题回放"的判定依据） */
    public ApCodingAnswerRecord findDailyRecord(Integer userId, Date answerDate) {
        return recordMapper.selectOne(new LambdaQueryWrapper<ApCodingAnswerRecord>()
            .eq(ApCodingAnswerRecord::getUserId, userId)
            .eq(ApCodingAnswerRecord::getAnswerDate, answerDate)
            .last("LIMIT 1"));
    }

    /**
     * 落库一次作答。
     *
     * <p>重复提交有两道防线：事务内预查询（快路径）+ 唯一键 {@code uk_user_date}（并发兜底）。
     * 两者抛出的是同一个 {@link IllegalStateException}，调用方不必区分。</p>
     *
     * @param evaluation 评估结果；{@code pending=true} 时等级为空，流水照常落库
     * @throws IllegalStateException 今日已作答（事务回滚，由调用方转成友好错误）
     */
    @Transactional(rollbackFor = Exception.class)
    public ApCodingAnswerRecord saveAnswer(Integer userId, ApCodingDailyPool question, String answerText,
                                           Integer elapsedSeconds, CodingDailyEvaluator.Result evaluation) {
        Date today = java.sql.Date.valueOf(LocalDate.now());
        if (findDailyRecord(userId, today) != null) {
            throw new IllegalStateException("今日一题已作答");
        }

        ApCodingAnswerRecord record = new ApCodingAnswerRecord();
        record.setUserId(userId);
        record.setPoolId(question.getId());
        record.setAnswerDate(today);
        record.setUserAnswer(answerText);
        record.setLevel(evaluation.pending ? null : evaluation.level);
        record.setFeedback(evaluation.comment);
        record.setCovered(CodingJson.writeJson(evaluation.covered));
        record.setMissing(CodingJson.writeJson(evaluation.missing));
        record.setElapsedSeconds(elapsedSeconds);
        record.setScoreAwarded(0);
        record.setCreatedTime(new Date());
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 兜底第二道：预查询是"锁 + 查询"，锁过期或事务拉长时两个并发都可能通过预检查，
            // 这时由 uk_user_date 拦下后到的那条。转成与预检查一致的语义，调用方无需区分。
            throw new IllegalStateException("今日一题已作答");
        }

        upsertUserStat(userId, question, today, evaluation);
        return record;
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
