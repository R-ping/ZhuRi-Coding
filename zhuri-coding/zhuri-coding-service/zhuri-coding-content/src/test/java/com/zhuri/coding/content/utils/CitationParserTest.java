package com.zhuri.coding.content.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用解析单测：切句 / 抽取引用（含多引用与组合写法）/ 越界识别靠调用方比对 / 来源块还原 / 实质句判定。
 */
class CitationParserTest {

    @Test
    @DisplayName("按句末标点切句，引用标记归属前一句")
    void splitsSentencesWithCitations() {
        String answer = "Redis 分布式锁要用唯一 value 防误删[1]。可以考虑 Redisson 的看门狗续期[2]。";
        List<CitationParser.Sentence> sents = CitationParser.splitSentences(answer);

        assertEquals(2, sents.size());
        assertEquals(List.of(1), sents.get(0).getCitations());
        assertEquals(List.of(2), sents.get(1).getCitations());
        assertTrue(sents.get(1).getText().contains("看门狗"));
    }

    @Test
    @DisplayName("支持多引用与逗号/顿号组合写法")
    void supportsMultipleCitationForms() {
        assertEquals(List.of(1, 2), CitationParser.extractCitations("结论一致[1][2]。"));
        assertEquals(List.of(1, 3), CitationParser.extractCitations("两者都提到[1,3]。"));
        assertEquals(List.of(2, 4), CitationParser.extractCitations("交叉验证[2、4]。"));
        assertEquals(List.of(1), CitationParser.extractCitations("重复引用只记一次[1][1]。"));
        assertTrue(CitationParser.extractCitations("没有任何引用。").isEmpty());
    }

    @Test
    @DisplayName("过短语气句被丢弃；达阈值的填充句保留但无引用（只进 noCitation，不计 unsupported）")
    void skipsTrivialSentences() {
        String answer = "好的。以上就是我的回答。Redis 分布式锁需要唯一 value 防误删[1]。";
        List<CitationParser.Sentence> sents = CitationParser.splitSentences(answer);

        // "好的。"（去标点后 2 字）被丢弃；"以上就是我的回答。"（8 字）达实质阈值 → 保留但无引用
        assertEquals(2, sents.size());
        assertFalse(sents.get(0).hasCitation());
        assertTrue(sents.get(1).hasCitation());
    }

    @Test
    @DisplayName("含实质内容但没引用的句子会被保留（供上层标记未溯源）")
    void keepsSubstantiveSentenceWithoutCitation() {
        String answer = "总的来说建议优先使用 Redisson 而不是自己手写实现。";
        List<CitationParser.Sentence> sents = CitationParser.splitSentences(answer);

        assertEquals(1, sents.size());
        assertFalse(sents.get(0).hasCitation());
        assertTrue(CitationParser.isSubstantive(sents.get(0).getText()));
    }

    @Test
    @DisplayName("从检索上下文还原 [n] -> 来源正文")
    void parsesSourceBlocks() {
        String docs = "[1] 标题：Redis 锁实践；作者：张三\n正文一：唯一 value 防误删\n----\n"
            + "[2] 标题：Redisson 入门；作者：李四\n正文二：看门狗自动续期\n----\n";

        Map<Integer, String> blocks = CitationParser.parseSourceBlocks(docs);

        assertEquals(2, blocks.size());
        assertTrue(blocks.get(1).contains("唯一 value"));
        assertTrue(blocks.get(2).contains("看门狗"));
    }

    @Test
    @DisplayName("空答案 / 空上下文不抛异常")
    void handlesBlankInput() {
        assertTrue(CitationParser.splitSentences(null).isEmpty());
        assertTrue(CitationParser.splitSentences("   ").isEmpty());
        assertTrue(CitationParser.parseSourceBlocks(null).isEmpty());
        assertFalse(CitationParser.isSubstantive("  "));
        assertEquals("", CitationParser.stripCitations(null));
    }

    @Test
    @DisplayName("stripCitations 去掉标记但保留正文")
    void stripsCitationMarkers() {
        assertEquals("Redis 锁要防误删。", CitationParser.stripCitations("Redis 锁要防误删[1][2]。"));
    }
}
