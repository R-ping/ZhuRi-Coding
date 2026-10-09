package com.zhuri.coding.content.service.coding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.common.bailian.PromptSanitizer;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 模拟面试的 JSON 列契约与配套解析 / 文本 / 取值小工具（Coding 延展第三层 · Stage A）。
 *
 * <p>{@code ap_coding_interview} 一行里存了三段 JSON：{@code plan_snapshot}（提纲）、
 * {@code turns}（对话流水）、{@code report}（面试报告）。这些结构原先作为内部类挂在
 * {@code CodingInterviewServiceImpl} 里，报告链路拆出 {@link CodingReportService} 后两边都要读写同一批结构 ——
 * <b>结构类型不该挂在任一服务的实现类上</b>，于是集中到这里；顺带把"turns 列 → prompt 文本"的
 * 格式化与截断也收进来，因为这两件事本来就是同一列的正反面。</p>
 *
 * <p>解析口径：<b>提纲与报告是模型产出，一律容错</b>——关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}、
 * 剥离 markdown 围栏、失败返回 null / 空列表交给调用方降级（宁可降级，不可抛异常打断链路）；
 * 对话流水是服务端自己写的，解析失败只告警。</p>
 *
 * <p>末尾的"域内共用小工具"是开面/轮次服务与报告服务都要用的几件小东西（空值兜底、难度标签、
 * 对话行格式化），放在这里是为了让两个服务共享同一份口径，而不是各自复制一遍。</p>
 */
@Slf4j
public final class CodingInterviewJson {

    /**
     * 提纲 / 报告 / 流水解析用。
     * 关掉 FAIL_ON_UNKNOWN_PROPERTIES：模型偶尔多返回一两个字段（如给主题补充理由），
     * 若因此判为"提纲不合格"而拒绝开面，用户会白跑一次 LLM，得不偿失。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 共享的容错 ObjectMapper（同域解析复用，避免每个服务各建一份同配置实例） */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private CodingInterviewJson() {
    }

    // ==================== JSON 结构 ====================

    /** 提纲主题（仅服务端可见，含 keyPoints） */
    public static class PlanTopic {
        public String topic;
        public String mainQuestion;
        public List<String> keyPoints;
        /** 主题来源：resume=简历深挖 / direction=方向通用（服务端归一后必为二者之一） */
        public String source;
    }

    /** 对话流水（turns JSON 元素） */
    public static class TurnRecord {
        public String role;
        public String type;
        public String content;
        public Integer topicIndex;
        public Long ts;
    }

    /** 面试报告（解析成功时写库；结构化失败时 report 列存模型原文） */
    public static class ReportData {
        public List<ReportItemData> items;
        public String overall;
        public List<String> suggestions;
    }

    /** 报告的单主题点评 */
    public static class ReportItemData {
        public String topic;
        public Integer structure;
        public CoverageData coverage;
        public Integer accuracy;
        public String comment;
        /** 服务端按 covered/(covered+missing) 计算的覆盖度等级（1-5） */
        public Integer coverageScore;
        /** 该主题未生成评估结果（分批失败占位）：不参与综合等级计算，前端标「未评估」 */
        public Boolean pending;
    }

    /** 覆盖考点清单（覆盖度先行的依据） */
    public static class CoverageData {
        public List<String> covered;
        public List<String> missing;
    }

    // ==================== 解析 ====================

    /**
     * 解析并校验提纲（生成校验用：缺主题名 / 缺主问题 / 无有效考点一律丢弃，不合格返回 null）。
     * 存储重载请用 {@link #parsePlanList(String)}。
     *
     * <p>只做"形状"层面的校验与规整，<b>不碰业务语义</b>——{@code source} 字段原样保留，
     * 由调用方按"有没有简历"归一（见 {@code CodingInterviewServiceImpl}）。</p>
     */
    public static List<PlanTopic> parsePlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJsonArray(raw);
        if (json == null) {
            return null;
        }
        List<PlanTopic> list;
        try {
            list = MAPPER.readValue(json, new TypeReference<List<PlanTopic>>() { });
        } catch (Exception e) {
            log.warn("[CodingInterview] 提纲 JSON 解析失败: {}", e.getMessage());
            return null;
        }
        if (list == null) {
            return null;
        }
        List<PlanTopic> valid = new ArrayList<>();
        for (PlanTopic t : list) {
            if (t == null || isBlank(t.topic) || isBlank(t.mainQuestion) || t.keyPoints == null) {
                continue;
            }
            List<String> keyPoints = t.keyPoints.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toList();
            if (keyPoints.isEmpty()) {
                continue;
            }
            t.topic = t.topic.trim();
            t.mainQuestion = t.mainQuestion.trim();
            t.keyPoints = keyPoints;
            valid.add(t);
        }
        return valid;
    }

    /** 存储提纲重载（快照为服务端写出，异常时返回空列表 = 不可用） */
    public static List<PlanTopic> parsePlanList(String json) {
        List<PlanTopic> list = parsePlan(json);
        return list == null ? new ArrayList<>() : list;
    }

    /** 对话流水（turns 列）；解析失败返回空列表（少一段历史比抛异常打断面试强） */
    public static List<TurnRecord> parseTurns(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<TurnRecord> list = MAPPER.readValue(json, new TypeReference<List<TurnRecord>>() { });
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("[CodingInterview] 对话流水解析失败");
            return new ArrayList<>();
        }
    }

    /** 取模型输出里的 JSON 数组片段（剥围栏 + 取首尾方括号）；取不到返回 null */
    public static String extractJsonArray(String raw) {
        String t = stripFences(raw);
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return t.substring(start, end + 1);
    }

    /** 取模型输出里的 JSON 对象片段（剥围栏 + 取首尾花括号）；取不到返回 null */
    public static String extractJsonObject(String raw) {
        String t = stripFences(raw);
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return t.substring(start, end + 1);
    }

    /** 剥掉 markdown 代码块围栏与首尾空白 */
    private static String stripFences(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.startsWith("```")) {
            int firstLine = t.indexOf('\n');
            t = firstLine < 0 ? "" : t.substring(firstLine + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) {
                t = t.substring(0, fence);
            }
        }
        return t.trim();
    }

    // ==================== prompt 文本工具 ====================

    /**
     * 追加一行对话（拼 prompt 用）。
     *
     * @param limit 单条保留长度（字符）；{@code <=0} 表示不截断。
     *              超长时补<b>显式语言标记</b>而不是省略号 —— 模型看到"已截断"才知道后面没了，
     *              否则会顺着半句话"续写"，把截断当成候选人话没说完。
     */
    public static void appendDialogueLine(StringBuilder sb, TurnRecord turn, int limit,
                                          PromptSanitizer sanitizer) {
        boolean fromUser = "user".equals(turn.role);
        String content = turn.content == null ? "" : turn.content;
        if (fromUser && sanitizer != null) {
            content = sanitizer.sanitize(content);
        }
        sb.append(fromUser ? "候选人：" : "面试官：");
        sb.append(truncate(content, limit)).append('\n');
    }

    /** 截断（超长补显式标记；limit<=0 表示不截断） */
    public static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (limit <= 0 || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "...（内容过长，已截断）";
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ==================== 域内共用小工具 ====================

    /** 空值兜底：DB 可空整数列按 0 处理（开面/轮次与报告两侧同口径） */
    public static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    /** 难度 → prompt 用中文标签（1 入门 / 2 进阶 / 3 挑战） */
    public static String difficultyLabel(int difficulty) {
        switch (difficulty) {
            case 1:
                return "入门";
            case 3:
                return "挑战";
            default:
                return "进阶";
        }
    }
}
