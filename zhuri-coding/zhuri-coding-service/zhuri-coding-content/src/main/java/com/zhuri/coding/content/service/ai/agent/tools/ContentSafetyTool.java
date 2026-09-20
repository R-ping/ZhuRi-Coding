package com.zhuri.coding.content.service.ai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.service.ai.agent.AgentTool;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具：内容安全预检（本地规则引擎，确定性、可离线）。
 *
 * <p>按类别命中违规关键词并返回原因；技术文章中的客观讨论（安全研究/医学/行业报道）含豁免语境词时不误伤。
 */
@Slf4j
@Component
public class ContentSafetyTool implements AgentTool {

    private static final List<Rule> RULES = new ArrayList<>();
    static {
        RULES.add(new Rule("色情低俗",
            new String[]{"色情视频", "裸聊", "一夜情", "援交", "约炮", "黄色网站", "成人影片", "露骨"},
            "包含色情低俗内容描述"));
        RULES.add(new Rule("违法赌博",
            new String[]{"赌博平台", "六合彩特码", "澳门赌场", "真人荷官", "彩票内幕", "稳赚不赔", "时时彩", "赌球平台"},
            "涉及赌博或违法博彩推广"));
        RULES.add(new Rule("诈骗引流",
            new String[]{"刷单兼职", "高额返利", "资金盘", "杀猪盘", "付费解锁群", "拉人头", "金字塔骗局"},
            "疑似诈骗或传销引流"));
        RULES.add(new Rule("毒品违禁",
            new String[]{"冰毒", "海洛因", "大麻购买", "迷药", "摇头丸"},
            "涉及毒品等违禁品"));
        RULES.add(new Rule("暴力恐怖",
            new String[]{"自制炸弹", "恐袭教程", "扬言伤人", "血腥肢解"},
            "包含暴力恐怖内容"));
    }
    /** 技术客观讨论的豁免语境词 */
    private static final String[] EXEMPT_WORDS =
        {"科普", "论文", "研究", "案例分析", "影视", "小说", "新闻", "历史", "教育", "辟谣", "安全研究", "检测", "法律法规", "骗局揭秘", "反诈"};

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "content_safety_check";
    }

    @Override
    public String description() {
        return "对标题与正文做内容安全预检（色情/赌博/诈骗/毒品/暴力等）。参数: {\"title\":\"标题\",\"content\":\"正文\"}。"
            + "返回 {\"is_violation\":true/false,\"violation_type\":\"\",\"violation_reason\":\"\"}。"
            + "客观的技术/科普/新闻讨论不会被误判。";
    }

    @Override
    public String execute(String args) {
        try {
            JsonNode node = objectMapper.readTree(args);
            String title = node.path("title").asText("");
            String content = node.path("content").asText("");
            String text = title + "\n" + content;
            boolean exempt = false;
            for (String w : EXEMPT_WORDS) {
                if (text.contains(w)) {
                    exempt = true;
                    break;
                }
            }
            if (!exempt) {
                for (Rule r : RULES) {
                    for (String kw : r.keywords) {
                        if (text.contains(kw)) {
                            return "{\"is_violation\":true,\"violation_type\":\"" + r.type
                                + "\",\"violation_reason\":\"" + r.reason + "（命中词：" + kw + "）\"}";
                        }
                    }
                }
            }
            return "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\"}";
        } catch (Exception e) {
            return "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\"}";
        }
    }

    private static class Rule {
        final String type;
        final String[] keywords;
        final String reason;

        Rule(String type, String[] keywords, String reason) {
            this.type = type;
            this.keywords = keywords;
            this.reason = reason;
        }
    }
}
