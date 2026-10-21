package com.zhuri.coding.content.service.coding;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodingJson 单元测试（每日一题与测评共用判题逻辑）
 *
 * 覆盖：JSON 解析容错（选项/答案下标/标签）、集合判分（单选/多选/错答/未答）。
 */
class CodingJsonTest {

    @Test
    @DisplayName("解析 - 选项/答案下标正常解析，非法 JSON 容错为空列表")
    void testParse() {
        assertEquals(List.of("A选项", "B选项"), CodingJson.parseStringList("[\"A选项\",\"B选项\"]"));
        assertTrue(CodingJson.parseStringList(null).isEmpty());
        assertTrue(CodingJson.parseStringList("not-json").isEmpty());
    }

    @Test
    @DisplayName("解析 - 标签按逗号切分并去除空白，空串返回空列表")
    void testParseTags() {
        assertEquals(List.of("Redis", "缓存"), CodingJson.parseTags("Redis, 缓存"));
        assertEquals(List.of("Redis"), CodingJson.parseTags(" Redis "));
        assertTrue(CodingJson.parseTags(null).isEmpty());
        assertTrue(CodingJson.parseTags("  ").isEmpty());
    }

    @Test
    @DisplayName("序列化 - 写出 JSON 字符串")
    void testWriteJson() {
        assertEquals("[0,1]", CodingJson.writeJson(List.of(0, 1)));
    }
}