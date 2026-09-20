package com.heima.content.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分块器单测：覆盖 长文多块 / 相邻块重叠 / 短文本单块 / 空内容 / 块数上限 / 无标点硬切。
 */
class TextChunkerTest {

    private static final int TARGET = 200;
    private static final int OVERLAP = 40;
    private static final int MAX_CHUNKS = 10;

    @Test
    @DisplayName("长文按目标长度切成多块，且单块不超过容差")
    void splitLongTextIntoChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("第").append(i).append("段：这是用于验证分块逻辑的测试段落，"
                + "内容长度需要足够长才能触发切分，因此重复描述该技术点的背景、原因与结论。\n\n");
        }
        List<String> chunks = TextChunker.split(sb.toString(), TARGET, OVERLAP, MAX_CHUNKS);

        assertTrue(chunks.size() > 1, "长文应切成多块");
        assertTrue(chunks.size() <= MAX_CHUNKS, "块数不得超过上限");
        for (String c : chunks) {
            assertTrue(c.length() <= TARGET * 2, "单块不得远超目标长度: " + c.length());
        }
    }

    @Test
    @DisplayName("相邻块存在尾部重叠，避免语义被割裂")
    void adjacentChunksOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("场景").append(i).append("：Redis 分布式锁在集群模式下需要考虑锁续期与误删问题。"
                + "这里描述具体做法与边界，用于撑长段落以触发分块。\n\n");
        }
        List<String> chunks = TextChunker.split(sb.toString(), TARGET, OVERLAP, MAX_CHUNKS);

        assertTrue(chunks.size() >= 2, "应至少切出两块");
        // 后一块的前缀应能在前一块尾部找到（重叠生效）
        String prev = chunks.get(0);
        String next = chunks.get(1);
        String prefix = next.length() > 20 ? next.substring(0, 20) : next;
        assertTrue(prev.contains(prefix), "重叠文本应来自上一块尾部");
    }

    @Test
    @DisplayName("短文本整体作为单块，不做切分")
    void shortTextKeptAsSingleChunk() {
        String shortText = "一句话说明：pgvector 用余弦距离做近邻检索。";
        List<String> chunks = TextChunker.split(shortText, TARGET, OVERLAP, MAX_CHUNKS);

        assertEquals(1, chunks.size());
        assertEquals(shortText, chunks.get(0));
    }

    @Test
    @DisplayName("空内容与空白内容返回空列表")
    void blankContentReturnsEmpty() {
        assertTrue(TextChunker.split(null).isEmpty());
        assertTrue(TextChunker.split("").isEmpty());
        assertTrue(TextChunker.split("   \n\n  ").isEmpty());
    }

    @Test
    @DisplayName("块数上限生效，超长文本被截断而非无限分块")
    void maxChunksCapApplied() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            sb.append("第").append(i).append("段：用于验证块数上限的填充内容，长度足够触发切分。\n\n");
        }
        List<String> chunks = TextChunker.split(sb.toString(), TARGET, OVERLAP, 5);

        assertEquals(5, chunks.size());
    }

    @Test
    @DisplayName("无标点超长文本按定长硬切，产出仍可入向量库")
    void codeWithoutPunctuationIsHardSplit() {
        String code = "a".repeat(1000);
        List<String> chunks = TextChunker.split(code, TARGET, OVERLAP, MAX_CHUNKS);

        assertFalse(chunks.isEmpty(), "无标点文本也应产出块");
        for (String c : chunks) {
            assertTrue(c.length() <= TARGET * 2, "硬切后单块不得超限");
        }
    }
}
