package com.zhuri.coding.content.utils;

import java.util.HashSet;
import java.util.Set;

/**
 * 文章修订差异判定工具
 *
 * <p>用于判断「已发布文章修订」的正文改动幅度是否达到"实质更新"阈值。
 * 采用字符级 4-gram Jaccard 差异率：{@code 1 - |交集| / |并集|}，
 * 取值区间 [0,1]，0 表示完全一致，越接近 1 表示改动越大。</p>
 *
 * <p>比较前会做简单归一化：去掉 Markdown 标记（代码块/行内代码/图片/链接/标题符号/HTML 标签）
 * 并把连续空白折叠为单个空格，避免纯排版或签名参数变化被误判为实质更新。</p>
 */
public final class ArticleRevisionDiff {

    /** n-gram 的 n（字符级） */
    private static final int N = 4;

    private ArticleRevisionDiff() {
    }

    /**
     * 计算新旧正文的差异率（1 - Jaccard 相似度）。
     *
     * @param oldText 旧正文（可为 null）
     * @param newText 新正文（可为 null）
     * @return 差异率，范围 [0,1]；两文本都为空或归一化后相同返回 0，一侧为空另一侧非空返回 1
     */
    public static double diffRatio(String oldText, String newText) {
        String a = normalize(oldText);
        String b = normalize(newText);

        // 边界：两文本归一化后完全一致（含都为空）→ 无差异
        if (a.equals(b)) {
            return 0.0;
        }
        Set<String> ga = grams(a);
        Set<String> gb = grams(b);
        // 一侧为空（无 gram）而另一侧非空 → 视为完全差异
        if (ga.isEmpty() || gb.isEmpty()) {
            return 1.0;
        }

        int intersection = 0;
        for (String gram : ga) {
            if (gb.contains(gram)) {
                intersection++;
            }
        }
        int union = ga.size() + gb.size() - intersection;
        if (union <= 0) {
            return 0.0;
        }
        return 1.0 - (double) intersection / union;
    }

    /**
     * 归一化：去 Markdown 标记与多余空白后返回（不改变大小写，保留中文语义）。
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = text;
        // 代码块 ```...```
        s = s.replaceAll("(?s)```[\\s\\S]*?```", " ");
        // 图片 ![alt](url)
        s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ");
        // 链接 [text](url) —— 保留链接文字
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // 行内代码 `code`
        s = s.replaceAll("`[^`]*`", " ");
        // 标题/引用/列表前缀符号（行首的 #、>、-、*、+ 及有序列表 1.）
        s = s.replaceAll("(?m)^\\s{0,3}[#>\\-*+]+\\s*", " ");
        s = s.replaceAll("(?m)^\\s{0,3}\\d+\\.\\s*", " ");
        // HTML 标签
        s = s.replaceAll("<[^>]+>", " ");
        // 折叠空白
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /**
     * 把文本切成字符级 4-gram 集合；文本长度不足 4 时以整串作为一个 gram。
     */
    private static Set<String> grams(String s) {
        Set<String> set = new HashSet<>();
        if (s.isEmpty()) {
            return set;
        }
        if (s.length() <= N) {
            set.add(s);
            return set;
        }
        for (int i = 0; i + N <= s.length(); i++) {
            set.add(s.substring(i, i + N));
        }
        return set;
    }
}