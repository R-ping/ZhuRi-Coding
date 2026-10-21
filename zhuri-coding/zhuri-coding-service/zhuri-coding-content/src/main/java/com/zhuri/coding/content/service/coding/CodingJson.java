package com.zhuri.coding.content.service.coding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Coding 域共用 JSON 小工具（选项/考点/标签解析、任意对象序列化）。
 *
 * <p>容错口径：内容异常时返回空结果并告警，而不是抛错拖垮主流程 ——
 * 解析失败顶多让一题没有选项/考点，不该让请求挂掉。</p>
 *
 * <p><b>名字为什么叫 Json 而不是 Judge</b>：选择题判分（答案集合比对）随
 * 每日一题换简答、能力测评整层下线而消失，这个类只剩 JSON 解析与序列化。
 * 判分口径改由 {@link CodingEvaluation} 承担（等级夹取 / 覆盖度换算 / 综合等级）。</p>
 */
@Slf4j
public final class CodingJson {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CodingJson() {
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

    /** 序列化 JSON（作答明细等写库用）；异常返回空数组字面量 */
    public static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}