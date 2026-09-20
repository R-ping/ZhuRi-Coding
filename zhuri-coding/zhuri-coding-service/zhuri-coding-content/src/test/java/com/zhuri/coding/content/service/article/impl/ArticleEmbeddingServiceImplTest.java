package com.heima.content.service.article.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ArticleEmbeddingServiceImpl 的「向量新鲜度」纯函数单测（P0-1）。
 *
 * <p>这两个判定是 RAG 数据新鲜度的核心：
 * <ol>
 *   <li>{@code contentHash}：内容指纹，用于判断"向量对应的内容是否已变"；</li>
 *   <li>{@code isStale}：过期判定 —— 决定回填/轮转任务要不要重算 embedding（花不花这份钱）。</li>
 * </ol>
 * 判定写错的代价是双向的：漏判 → 召回旧内容（RAG 答错）；误判 → 每次全量重算（烧 embedding 成本）。
 */
class ArticleEmbeddingServiceImplTest {

    private static final String CONTENT_A = "Redis 分布式锁通过 SETNX 实现互斥。";
    private static final String CONTENT_B = "Redis 分布式锁通过 SETNX 与 Lua 脚本实现互斥。";

    // ==================== contentHash ====================

    @Test
    @DisplayName("contentHash：同内容稳定同值（回填比对的前提）")
    void testHashStable() {
        assertEquals(ArticleEmbeddingServiceImpl.contentHash(CONTENT_A),
                ArticleEmbeddingServiceImpl.contentHash(CONTENT_A));
    }

    @Test
    @DisplayName("contentHash：内容微改（多几个字）即产生不同指纹 → 触发重算")
    void testHashSensitiveToContentChange() {
        assertNotEquals(ArticleEmbeddingServiceImpl.contentHash(CONTENT_A),
                ArticleEmbeddingServiceImpl.contentHash(CONTENT_B));
    }

    @Test
    @DisplayName("contentHash：返回 64 位十六进制（SHA-256）")
    void testHashFormat() {
        String h = ArticleEmbeddingServiceImpl.contentHash(CONTENT_A);
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("contentHash：null/空内容返回 null（表示算不出当前版本，不参与过期判定）")
    void testHashNullSafe() {
        assertNull(ArticleEmbeddingServiceImpl.contentHash(null));
        assertNull(ArticleEmbeddingServiceImpl.contentHash(""));
    }

    // ==================== isStale ====================

    @Test
    @DisplayName("isStale：存量向量无指纹（改造前写入）→ 过期，需补写一次")
    void testStaleWhenStoredHashMissing() {
        assertTrue(ArticleEmbeddingServiceImpl.isStale(null, CONTENT_A));
        assertTrue(ArticleEmbeddingServiceImpl.isStale("", CONTENT_A));
    }

    @Test
    @DisplayName("isStale：指纹一致 → 不过期（不做无谓 embedding 调用，省成本）")
    void testNotStaleWhenHashMatches() {
        String h = ArticleEmbeddingServiceImpl.contentHash(CONTENT_A);
        assertFalse(ArticleEmbeddingServiceImpl.isStale(h, h));
    }

    @Test
    @DisplayName("isStale：指纹不一致（文章被编辑）→ 过期，重算向量")
    void testStaleWhenContentChanged() {
        String oldHash = ArticleEmbeddingServiceImpl.contentHash(CONTENT_A);
        String newHash = ArticleEmbeddingServiceImpl.contentHash(CONTENT_B);
        assertTrue(ArticleEmbeddingServiceImpl.isStale(oldHash, newHash));
    }

    @Test
    @DisplayName("isStale：当前正文算不出指纹（无正文）→ 不过期，避免把历史向量误清空")
    void testNotStaleWhenCurrentHashUnknown() {
        assertFalse(ArticleEmbeddingServiceImpl.isStale("anyStoredHash", null));
        assertFalse(ArticleEmbeddingServiceImpl.isStale(null, null));
    }
}
