package com.zhuri.coding.content.service.coding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import java.time.LocalDate;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 作答落库事务服务（Coding 延展第一层）
 *
 * <p>把"作答记录 + 题目计数 + 用户统计"三处写入收进同一事务，
 * 与 {@code CheckinTxService} 同构：主流程（判分编排、等级与签到跨服务调用）
 * 放事务外，失败只降级不拖垮落库结果。</p>
 */
@Slf4j
@Service
public class CodingAnswerTxService {

    @Autowired
    private ApCodingAnswerRecordMapper recordMapper;

    @Autowired
    private ApCodingQuestionMapper questionMapper;

    @Autowired
    private ApCodingUserStatMapper statMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 当日一题是否已作答（同一事务内防重，兼作"今日题回放"的判定依据）。
     */
    public ApCodingAnswerRecord findDailyRecord(Integer userId, Date answerDate) {
        return recordMapper.selectOne(new LambdaQueryWrapper<ApCodingAnswerRecord>()
            .eq(ApCodingAnswerRecord::getUserId, userId)
            .eq(ApCodingAnswerRecord::getAnswerDate, answerDate)
            .eq(ApCodingAnswerRecord::getIsDaily, 1)
            .last("LIMIT 1"));
    }

    /**
     * 落库一次作答：记录流水 → 题目计数累加（仅当日一题）→ 用户统计 upsert。
     *
     * @param userAnswersJson 用户答案 JSON（落库前已在服务层校验下标范围）
     * @throws IllegalStateException 当日一题重复提交（事务回滚，由调用方转成友好错误）
     */
    @Transactional(rollbackFor = Exception.class)
    public ApCodingAnswerRecord saveAnswer(Integer userId, ApCodingQuestion question,
                                           String userAnswersJson, boolean correct,
                                           Integer elapsedSeconds, boolean isDaily) {
        Date today = java.sql.Date.valueOf(LocalDate.now());
        if (isDaily && findDailyRecord(userId, today) != null) {
            throw new IllegalStateException("今日一题已作答");
        }

        ApCodingAnswerRecord record = new ApCodingAnswerRecord();
        record.setUserId(userId);
        record.setQuestionId(question.getId());
        record.setAnswerDate(today);
        record.setUserAnswer(userAnswersJson);
        record.setIsCorrect(correct ? 1 : 0);
        record.setElapsedSeconds(elapsedSeconds);
        record.setIsDaily(isDaily ? 1 : 0);
        record.setScoreAwarded(0);
        record.setCreatedTime(new Date());
        recordMapper.insert(record);

        // 题目热度计数只服务"当日一题"（练习不参与，避免热度被重复刷）
        if (isDaily) {
            questionMapper.incrementAnswerStats(question.getId(), correct ? 1 : 0);
        }

        upsertUserStat(userId, question, correct, isDaily, today);
        return record;
    }

    /** 用户统计 upsert：读改写合并 tag_stats；唯一键 uk_user 兜底并发首插 */
    private void upsertUserStat(Integer userId, ApCodingQuestion question, boolean correct,
                                boolean isDaily, Date today) {
        ApCodingUserStat stat = statMapper.selectOne(new LambdaQueryWrapper<ApCodingUserStat>()
            .eq(ApCodingUserStat::getUserId, userId));
        if (stat == null) {
            stat = new ApCodingUserStat();
            stat.setUserId(userId);
            stat.setTotalCount(0);
            stat.setCorrectCount(0);
            stat.setPracticeCount(0);
            stat.setPracticeCorrectCount(0);
            stat.setFirstAnswerDate(today);
            stat.setLastAnswerDate(today);
            stat.setCreatedTime(new Date());
            stat.setUpdatedTime(new Date());
            applyCounts(stat, question, correct, isDaily);
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
        // 注意：读改写分支可能与并发请求相互覆盖，统计属可容忍最终一致的聚合值；
        // tag_stats 需要基于既有 JSON 合并，无法用单条原子 SQL 表达（见 Mapper 注释）
        stat.setLastAnswerDate(today);
        applyCounts(stat, question, correct, isDaily);
        stat.setUpdatedTime(new Date());
        statMapper.updateById(stat);
    }

    /** 累加计数与领域分布（不负责 first/last 日期赋值与落库） */
    private void applyCounts(ApCodingUserStat stat, ApCodingQuestion question,
                             boolean correct, boolean isDaily) {
        if (isDaily) {
            stat.setTotalCount(nvl(stat.getTotalCount()) + 1);
            if (correct) {
                stat.setCorrectCount(nvl(stat.getCorrectCount()) + 1);
            }
        } else {
            stat.setPracticeCount(nvl(stat.getPracticeCount()) + 1);
            if (correct) {
                stat.setPracticeCorrectCount(nvl(stat.getPracticeCorrectCount()) + 1);
            }
        }
        stat.setTagStats(mergeTagStats(stat.getTagStats(), question.getTags(), correct));
    }

    /**
     * 合并领域答题分布：{"Redis":{"total":3,"correct":2}}。
     * 单题最多取前 3 个标签，避免标签噪声把分布拉散。
     */
    private String mergeTagStats(String existingJson, String tags, boolean correct) {
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
            Map<String, Integer> item = stats.computeIfAbsent(tag, k -> new HashMap<>());
            item.put("total", nvl(item.get("total")) + 1);
            if (correct) {
                item.put("correct", nvl(item.get("correct")) + 1);
            }
            if (++used >= 3) {
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

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}