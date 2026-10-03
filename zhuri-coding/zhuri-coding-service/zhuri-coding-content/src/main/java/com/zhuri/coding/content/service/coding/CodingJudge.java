package com.zhuri.coding.content.service.coding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 判题公共逻辑（Coding 延展：每日一题与能力测评共用，避免两套判分口径）
 *
 * <p>口径：用户答案下标集合与正确答案下标集合完全一致才算答对（单选/多选同一规则，
 * 单选由提交侧校验只允许一个下标）。JSON 解析容错：内容异常时返回空列表并告警，
 * 让判分自然得出"未答对"而不是抛错拖垮主流程。</p>
 */
@Slf4j
public final class CodingJudge {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CodingJudge() {
    }

    /** 解析 JSON 字符串数组（题目选项等）；异常返回空列表 */
    public static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("解析选项 JSON 失败: {}", json);
            return new ArrayList<>();
        }
    }

    /** 解析 JSON 整数数组（正确答案下标/用户答案等）；异常返回空列表 */
    public static List<Integer> parseIntList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Integer> list = OBJECT_MAPPER.readValue(json, new TypeReference<List<Integer>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("解析答案下标 JSON 失败: {}", json);
            return new ArrayList<>();
        }
    }

    /** 解析逗号分隔的题目标签（如 "Redis,缓存"）；空串返回空列表 */
    public static List<String> parseTags(String tags) {
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

    /**
     * 判分：用户答案集合与正确下标集合完全一致才算答对。
     * 调用方需先做下标范围与题型校验（每日一题提交、测评交卷均如此）。
     */
    public static boolean judge(List<Integer> correctAnswers, Set<Integer> userAnswers) {
        return userAnswers != null && userAnswers.equals(new HashSet<>(correctAnswers));
    }

    /** 判分（正确下标直接给 JSON 字符串，内部解析） */
    public static boolean judge(String correctAnswerJson, Set<Integer> userAnswers) {
        return judge(parseIntList(correctAnswerJson), userAnswers);
    }

    /** 序列化 JSON（作答明细等写库用）；异常返回空数组字面量 */
    public static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}