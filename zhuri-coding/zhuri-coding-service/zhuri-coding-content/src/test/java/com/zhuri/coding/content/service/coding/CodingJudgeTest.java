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
 * CodingJudge 单元测试（每日一题与测评共用判题逻辑）
 *
 * 覆盖：JSON 解析容错（选项/答案下标/标签）、集合判分（单选/多选/错答/未答）。
 */
class CodingJudgeTest {

    @Test
    @DisplayName("解析 - 选项/答案下标正常解析，非法 JSON 容错为空列表")
    void testParse() {
        assertEquals(List.of("A选项", "B选项"), CodingJudge.parseStringList("[\"A选项\",\"B选项\"]"));
        assertEquals(List.of(0, 2), CodingJudge.parseIntList("[0,2]"));
        assertTrue(CodingJudge.parseStringList(null).isEmpty());
        assertTrue(CodingJudge.parseStringList("not-json").isEmpty());
        assertTrue(CodingJudge.parseIntList("{bad}").isEmpty());
        assertTrue(CodingJudge.parseIntList("").isEmpty());
    }

    @Test
    @DisplayName("解析 - 标签按逗号切分并去除空白，空串返回空列表")
    void testParseTags() {
        assertEquals(List.of("Redis", "缓存"), CodingJudge.parseTags("Redis, 缓存"));
        assertEquals(List.of("Redis"), CodingJudge.parseTags(" Redis "));
        assertTrue(CodingJudge.parseTags(null).isEmpty());
        assertTrue(CodingJudge.parseTags("  ").isEmpty());
    }

    @Test
    @DisplayName("判分 - 单选/多选集合一致为对，少选/多选/未答为错")
    void testJudge() {
        Set<Integer> single = new LinkedHashSet<>(List.of(1));
        assertTrue(CodingJudge.judge(List.of(1), single));
        assertFalse(CodingJudge.judge(List.of(0), single));
        assertFalse(CodingJudge.judge(List.of(1, 2), single));

        Set<Integer> multi = new LinkedHashSet<>(List.of(0, 2));
        assertTrue(CodingJudge.judge(List.of(0, 2), multi));
        assertTrue(CodingJudge.judge(List.of(2, 0), multi)); // 顺序无关
        assertFalse(CodingJudge.judge(List.of(0), multi));
        assertFalse(CodingJudge.judge(List.of(0, 1, 2), multi));

        assertFalse(CodingJudge.judge(List.of(1), new LinkedHashSet<>())); // 未答
        assertFalse(CodingJudge.judge(List.of(1), null)); // 空保护
    }

    @Test
    @DisplayName("序列化 - 写出 JSON 字符串")
    void testWriteJson() {
        assertEquals("[0,1]", CodingJudge.writeJson(List.of(0, 1)));
    }
}