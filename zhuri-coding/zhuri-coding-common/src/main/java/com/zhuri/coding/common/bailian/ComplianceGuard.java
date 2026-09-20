package com.zhuri.coding.common.bailian;

import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 输出护栏 —— LLM 响应顺从短语检测（Layer 3）
 *
 * <p>检测模型是否在响应中"承认"已遵从注入指令（如"我将忽略之前的指令"、"我已成为翻译…"）。
 * 这类短语通常是提示词注入成功的标志，命中则应阻断响应（由调用方决定降级/置空）。
 *
 * <p>同时供旧链路（{@link DashScopeClient#callGeneration}）与 Spring AI 新链路
 * （AiAsk / PublishAssistant 等）共用，避免正则规则两处漂移。
 */
@Slf4j
@Component
public class ComplianceGuard {

    // 中文顺从短语：如 "好的，我将忽略之前的指令" / "我现在将扮演一个翻译助手" / "新的角色已切换"
    private static final Pattern COMPLIANCE_PHRASE_CN = Pattern.compile(
        "(好的|可以|没问题|明白|收到|理解)[，,。.]?(我)?(已经|将|会|正在)?(忽略|忘记|无视|跳过|遵守|执行)(之前的|上面的|所有的)?(指令|指示|要求|规则|设定|命令)"
            + "|(我(?:现在|刚才|接下来)?(?:将|会|正在)?(?:成为|作为|扮演)(?:一个|一名|一位)?(?:翻译|助手|自由|不同|角色))"
            + "|(新的角色|已切换角色|角色已变更)",
        Pattern.CASE_INSENSITIVE
    );

    // 英文顺从短语
    private static final Pattern COMPLIANCE_PHRASE_EN = Pattern.compile(
        "(i('ll| will| have| am)( now)? (act as|become|behave as|serve as|work as)( a| an| the)?)"
            + "|(sure, (i will|i'll|let me) (ignore|forget|disregard))"
            + "|(forget all previous instructions|ignoring previous instructions|override all instructions)"
            + "|(i('ll| will) (switch|change|shift) (my role|to a))",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 检测响应是否包含顺从短语（注入成功迹象）。
     *
     * @param response LLM 响应文本
     * @return true=命中顺从短语，应阻断该响应
     */
    public boolean isComplianceResponse(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        if (COMPLIANCE_PHRASE_CN.matcher(response).find()) {
            log.warn("Compliance phrase detected (CN) in response: {}",
                truncate(response, 100));
            return true;
        }
        if (COMPLIANCE_PHRASE_EN.matcher(response).find()) {
            log.warn("Compliance phrase detected (EN) in response: {}",
                truncate(response, 100));
            return true;
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}