package com.heima.content.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本分块器 —— 父子分块检索（small-to-big）中「子块」的生产器。
 *
 * <p>切分策略（逐级降级，保证块长可控）：
 * <ol>
 *   <li>优先按空行分段；</li>
 *   <li>单段超长 → 按换行聚合；</li>
 *   <li>仍超长 → 按句末标点聚合；</li>
 *   <li>无标点超长文本（压缩代码等）→ 按定长硬切兜底。</li>
 * </ol>
 * 相邻块保留尾部重叠（尽量落在句子边界，避免半句语义断裂）；过短碎片并入前一块或丢弃；
 * 块数封顶，防止长文产生海量 embedding 调用。
 */
public final class TextChunker {

    /** 目标块长（字符）：约 500 字 ≈ 一段完整技术论述 */
    public static final int DEFAULT_TARGET = 500;
    /** 相邻块重叠字符数：保证跨块的句子不被割裂 */
    public static final int DEFAULT_OVERLAP = 80;
    /** 单篇最大分块数：控 embedding 调用成本 */
    public static final int DEFAULT_MAX_CHUNKS = 30;
    /** 低于该长度的碎片不单独入向量库（语义信息不足，易噪声召回） */
    public static final int MIN_CHUNK_CHARS = 60;

    private static final Pattern PARAGRAPH = Pattern.compile("\n\\s*\n");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？!?；;])");

    private TextChunker() {
    }

    /** 默认参数分块 */
    public static List<String> split(String raw) {
        return split(raw, DEFAULT_TARGET, DEFAULT_OVERLAP, DEFAULT_MAX_CHUNKS);
    }

    /**
     * 分块主流程。
     *
     * @param raw          原文（建议先经 MarkdownUtils.normalizeContent 归一）
     * @param targetChars  目标块长（字符）
     * @param overlapChars 相邻块重叠字符数（自动收敛到不超过块长一半）
     * @param maxChunks    最大块数（超出直接截断）
     */
    public static List<String> split(String raw, int targetChars, int overlapChars, int maxChunks) {
        List<String> chunks = new ArrayList<>();
        if (raw == null || raw.isBlank() || targetChars <= 0 || maxChunks <= 0) {
            return chunks;
        }
        int overlap = Math.max(0, Math.min(overlapChars, targetChars / 2));
        String text = raw.replace("\r\n", "\n").trim();
        List<String> blocks = toBlocks(text, targetChars);

        StringBuilder cur = new StringBuilder();
        for (String block : blocks) {
            if (chunks.size() >= maxChunks) {
                break;
            }
            // 装不下且当前块已有内容 → 收口当前块，下一块带重叠前缀
            if (cur.length() > 0 && cur.length() + block.length() > targetChars) {
                chunks.add(cur.toString().trim());
                if (chunks.size() >= maxChunks) {
                    cur.setLength(0);
                    break;
                }
                String tail = tailBoundary(cur.toString(), overlap);
                cur.setLength(0);
                if (!tail.isEmpty()) {
                    cur.append(tail).append('\n');
                }
            }
            cur.append(block).append('\n');
        }
        if (chunks.size() < maxChunks && cur.length() > 0) {
            chunks.add(cur.toString().trim());
        }
        return dropTinyFragments(chunks);
    }

    /** 逐级降级切分为「不超过 target 的语义片段」 */
    private static List<String> toBlocks(String text, int target) {
        List<String> blocks = new ArrayList<>();
        for (String para : PARAGRAPH.split(text)) {
            String p = para.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= target) {
                addBlock(blocks, p, target);
                continue;
            }
            for (String byLine : regroup(p.split("\n"), target)) {
                if (byLine.length() <= target) {
                    addBlock(blocks, byLine, target);
                    continue;
                }
                for (String bySentence : regroup(SENTENCE.split(byLine), target)) {
                    addBlock(blocks, bySentence, target);
                }
            }
        }
        return blocks;
    }

    /** 片段超长（如无标点的压缩代码）→ 按定长硬切，保证单块不超限；否则原样收下 */
    private static void addBlock(List<String> blocks, String s, int target) {
        String v = s.trim();
        if (v.isEmpty()) {
            return;
        }
        if (v.length() <= target * 2) {
            blocks.add(v);
            return;
        }
        for (int i = 0; i < v.length(); i += target) {
            blocks.add(v.substring(i, Math.min(v.length(), i + target)).trim());
        }
    }

    /** 把若干小片段贪心重组成不超过 target 的块 */
    private static List<String> regroup(String[] parts, int target) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String s = part == null ? "" : part.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0 && sb.length() + s.length() > target) {
                out.add(sb.toString().trim());
                sb.setLength(0);
            }
            sb.append(s).append('\n');
        }
        if (sb.length() > 0) {
            out.add(sb.toString().trim());
        }
        return out;
    }

    /**
     * 取尾部重叠文本，尽量从句子边界开始 —— 避免重叠区是半句话。
     * 边界恰好落在块尾（截出来为空/过短）时退回按字符截取，保证重叠一定生效。
     */
    private static String tailBoundary(String s, int overlap) {
        if (overlap <= 0 || s == null || s.length() <= overlap) {
            return "";
        }
        int from = s.length() - overlap;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == ';' || c == '\n') {
                String cut = s.substring(i + 1).trim();
                if (cut.length() >= overlap / 2) {
                    return cut;
                }
                break;
            }
        }
        return s.substring(from).trim();
    }

    /** 过短碎片处理：末块过短则并入前一块；其余仍过短的丢弃（单块结果保留，短文本整篇即一块） */
    private static List<String> dropTinyFragments(List<String> chunks) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        List<String> out = new ArrayList<>(chunks);
        int last = out.size() - 1;
        if (out.get(last).length() < MIN_CHUNK_CHARS) {
            String merged = out.get(last - 1) + "\n" + out.get(last);
            out.set(last - 1, merged);
            out.remove(last);
        }
        List<String> filtered = new ArrayList<>(out.size());
        for (String c : out) {
            if (c.length() >= MIN_CHUNK_CHARS) {
                filtered.add(c);
            }
        }
        return filtered.isEmpty() ? out : filtered;
    }
}
