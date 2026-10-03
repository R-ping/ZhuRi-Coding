package com.zhuri.coding.content.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArticleRevisionDiff 单元测试：4-gram Jaccard 差异率
 *
 * 覆盖：完全相同 / 小改动（低于阈值）/ 大改动 / 空文本边界 / Markdown 归一化。
 */
class ArticleRevisionDiffTest {

    /** 一段足够长的基准正文，保证单字改动不会触发实质更新阈值（默认 0.15） */
    private static final String BASE =
            "Java集合框架是Java编程语言中非常重要的组成部分，它提供了一套性能优良、使用方便的接口和类，"
            + "让开发者可以更加高效地处理数据集合。本文将从整体上介绍集合框架的设计思想与常用接口，"
            + "并给出遍历、查找、排序等常见操作的示例代码与最佳实践建议，帮助读者系统掌握这一核心能力。";

    @Test
    @DisplayName("完全相同 → 差异率 0")
    void testIdentical() {
        assertEquals(0.0, ArticleRevisionDiff.diffRatio(BASE, BASE), 1e-9);
    }

    @Test
    @DisplayName("两文本都为空 → 差异率 0")
    void testBothEmpty() {
        assertEquals(0.0, ArticleRevisionDiff.diffRatio("", ""), 1e-9);
        assertEquals(0.0, ArticleRevisionDiff.diffRatio(null, null), 1e-9);
    }

    @Test
    @DisplayName("一侧为空一侧非空 → 差异率 1")
    void testOneEmpty() {
        assertEquals(1.0, ArticleRevisionDiff.diffRatio("", BASE), 1e-9);
        assertEquals(1.0, ArticleRevisionDiff.diffRatio(BASE, null), 1e-9);
    }

    @Test
    @DisplayName("小改动（单字修订）→ 差异率大于0且低于实质更新阈值0.15")
    void testSmallEdit() {
        String edited = BASE.replace("性能优良", "性能优秀");
        double ratio = ArticleRevisionDiff.diffRatio(BASE, edited);
        assertTrue(ratio > 0.0, "小改动应产生非零差异");
        assertTrue(ratio < 0.15, "小改动不应达到实质更新阈值, ratio=" + ratio);
    }

    @Test
    @DisplayName("大改动（整段替换）→ 差异率显著偏高")
    void testLargeEdit() {
        String replaced =
                "红烧肉是一道经典家常菜，需要选用肥瘦相间的五花肉，先用冷水下锅焯去血沫，"
                + "再以冰糖炒出糖色，加入生抽老抽料酒与香料，转小火慢炖至软糯收汁即可享用。";
        double ratio = ArticleRevisionDiff.diffRatio(BASE, replaced);
        assertTrue(ratio > 0.5, "整段替换应产生较大差异, ratio=" + ratio);
    }

    @Test
    @DisplayName("仅图片URL签名参数变化 → 归一化后无差异")
    void testImageSignatureIgnored() {
        String oldText = "正文内容\n\n![图片](http://x.com/p.png?sig=aaa)";
        String newText = "正文内容\n\n![图片](http://x.com/p.png?sig=bbb)";
        assertEquals(0.0, ArticleRevisionDiff.diffRatio(oldText, newText), 1e-9);
    }
}