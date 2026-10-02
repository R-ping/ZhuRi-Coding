package com.zhuri.coding.content.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PromptStamp} 单测：签名格式稳定性（与 Map 实现/顺序无关）与空值语义。
 *
 * <p>格式稳定性是缓存正确性的前提——同一组版本必须永远产出同一字符串，
 * 否则会造成「无谓失效」（把仍然有效的缓存误判为版本变更而删除）。
 */
@DisplayName("PromptStamp（语义缓存版本签名）")
class PromptStampTest {

    @Test
    @DisplayName("单 key：输出 key@version")
    void singleKey() {
        assertEquals("ai_ask_system@3", PromptStamp.of(Map.of("ai_ask_system", 3)));
    }

    @Test
    @DisplayName("多 key：按字典序拼接，与入参顺序无关")
    void multipleKeysSorted() {
        Map<String, Integer> insertionOrdered = new LinkedHashMap<>();
        insertionOrdered.put("b_key", 2);
        insertionOrdered.put("a_key", 1);
        assertEquals("a_key@1|b_key@2", PromptStamp.of(insertionOrdered));
        // 逆序插入同一组数据 → 签名必须一致
        Map<String, Integer> reversed = new LinkedHashMap<>();
        reversed.put("a_key", 1);
        reversed.put("b_key", 2);
        assertEquals(PromptStamp.of(insertionOrdered), PromptStamp.of(reversed));
    }

    @Test
    @DisplayName("null / 空 Map → 空串（缓存层视为不校验版本）")
    void nullOrEmptyMap() {
        assertEquals("", PromptStamp.of(null));
        assertEquals("", PromptStamp.of(Map.of()));
    }

    @Test
    @DisplayName("version 为 null → 记 0（与代码兜底版口径一致）")
    void nullVersionTreatedAsZero() {
        Map<String, Integer> withNull = new HashMap<>();
        withNull.put("ai_ask_system", null);
        assertEquals("ai_ask_system@0", PromptStamp.of(withNull));
    }
}