package com.heima.content.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 答案引用解析器（纯逻辑，无 Spring 依赖，便于单测）。
 *
 * <p>职责：① 把 RAG 答案切成句子并抽取每句的 `[n]` 引用序号；② 从检索上下文（docsText）里
 * 还原 `[n] -> 来源正文`，供忠实度校验比对；③ 判定句子是否为"实质内容"（过短/纯标点不算）。
 */
public final class CitationParser {

    /** 引用标记：支持 [1]、[1][2]、[1,2]、[1、2] */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+(?:\\s*[,、]\\s*\\d+)*)]");

    /** 句末终止符（不切分冒号、破折号，避免把 "如下：" 切开） */
    private static final Pattern TERMINATOR = Pattern.compile("[。！？!?；;\\n]");

    /** 实质内容最小长度（去掉引用标记与标点后的字符数） */
    private static final int MIN_SUBSTANTIVE_LEN = 8;

    private static final Pattern STRIP_FOR_LEN = Pattern.compile("[\\s\\[\\]()（）,，.。、；;:：\"'“”‘’*#>`-]");

    private CitationParser() {
    }

    /** 答案中的一句话 + 该句引用的来源序号（1-based，对应 sources 下标+1） */
    public static final class Sentence {

        private final String text;

        private final List<Integer> citations;

        public Sentence(String text, List<Integer> citations) {
            this.text = text;
            this.citations = citations;
        }

        public String getText() {
            return text;
        }

        public List<Integer> getCitations() {
            return citations;
        }

        public boolean hasCitation() {
            return citations != null && !citations.isEmpty();
        }
    }

    /**
     * 切句并抽取引用。终止符归属前一句；空句与纯标点句丢弃。
     */
    public static List<Sentence> splitSentences(String answer) {
        List<Sentence> out = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < answer.length(); i++) {
            char c = answer.charAt(i);
            if (TERMINATOR.matcher(String.valueOf(c)).matches()) {
                addSentence(out, answer.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < answer.length()) {
            addSentence(out, answer.substring(start));
        }
        return out;
    }

    private static void addSentence(List<Sentence> out, String raw) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return;
        }
        List<Integer> citations = extractCitations(text);
        // 过短且无引用 → 视为语气/标记性文字，不参与校验
        if (citations.isEmpty() && !isSubstantive(text)) {
            return;
        }
        out.add(new Sentence(text, citations));
    }

    /** 抽取句子中的全部引用序号（去重、保持出现顺序） */
    public static List<Integer> extractCitations(String text) {
        List<Integer> idx = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return idx;
        }
        Matcher m = CITATION.matcher(text);
        while (m.find()) {
            for (String part : m.group(1).split("[,、]")) {
                String v = part.trim();
                if (v.isEmpty()) {
                    continue;
                }
                try {
                    int n = Integer.parseInt(v);
                    if (n > 0 && !idx.contains(n)) {
                        idx.add(n);
                    }
                } catch (NumberFormatException ignore) {
                    // 非数字不处理
                }
            }
        }
        return idx;
    }

    /** 是否含实质内容（去掉引用标记、空白与标点后是否够长） */
    public static boolean isSubstantive(String text) {
        if (text == null) {
            return false;
        }
        String stripped = STRIP_FOR_LEN.matcher(CITATION.matcher(text).replaceAll("")).replaceAll("");
        return stripped.length() >= MIN_SUBSTANTIVE_LEN;
    }

    /**
     * 从检索上下文还原 `序号 -> 来源文本`。
     *
     * <p>上下文由 retrieveAndAssemble 按固定格式拼装：
     * {@code [n] 标题：xxx；作者：yyy\n正文…\n----\n}，此处按 ---- 分块后取块首的 [n]。
     * 解析失败返回空 Map（调用方据空 Map 跳过依赖来源比对的检查，fail-open）。
     */
    public static Map<Integer, String> parseSourceBlocks(String docsText) {
        Map<Integer, String> blocks = new LinkedHashMap<>();
        if (docsText == null || docsText.isBlank()) {
            return blocks;
        }
        for (String chunk : docsText.split("-{3,}")) {
            String text = chunk.trim();
            if (text.isEmpty()) {
                continue;
            }
            List<Integer> nums = extractCitations(text.substring(0, Math.min(20, text.length())));
            if (nums.isEmpty()) {
                continue;
            }
            blocks.putIfAbsent(nums.get(0), text);
        }
        return blocks;
    }

    /** 去掉答案里的引用标记（用于给模型看纯净句子） */
    public static String stripCitations(String text) {
        return text == null ? "" : CITATION.matcher(text).replaceAll("").trim();
    }
}
