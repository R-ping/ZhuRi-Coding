package com.heima.common.bailian;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词注入防御 —— Layer 1：输入净化
 *
 * 在用户文本进入 prompt 之前，用确定性规则（正则）做清洗，替换为中性占位符。
 * 净化只针对直接拼接点，不做全局净化，避免误杀合法内容。
 *
 * 四组正则规则：
 * 1. 行首角色标记 —— 匹配 ^system:、^user:、^assistant: 等行首角色标记
 * 2. 注入短语 —— 匹配 "忽略之前的指令"、"ignore previous instructions" 等完整短语
 * 3. 分隔符伪造 —— 匹配 ---xxx内容开始/结束--- 等伪造分隔符
 * 4. 边界标签伪造 —— 匹配 <data-boundary> 标签
 *
 * 同时提供 UUID 动态分隔符包裹方法，防止攻击者伪造边界标签。
 */
@Slf4j
@Component
public class PromptSanitizer {

    // ==================== 1. 行首角色标记 ====================
    // 匹配出现在行首的 system:、user:、assistant: 等角色标记
    // 用 ^ 锚定行首，避免误杀句中出现的 "system design" 等合法内容
    private static final Pattern LINE_START_ROLE_MARKER = Pattern.compile(
        "^(system|user|assistant|human|ai):.*$",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    );

    // ==================== 2. 注入短语 ====================
    // 匹配完整的注入短语，而不是单独匹配常见词，降低误杀率
    // 中文注入短语
    private static final Pattern INJECTION_PHRASE_CN = Pattern.compile(
        "(忽略|忘记|无视)(之前的|前面的|以上)?(所有)?(指令|指示|要求|规则|设定|命令|约束)" +
        "|(你现在|从今以后|从现在开始|接下来)(是|成为|作为|扮演)(一个|一名|一位)?(翻译|助手|自由|不同)" +
        "|(新的角色|新角色|新规则|新指令)[：:].*" +
        "|system[：:].*",
        Pattern.CASE_INSENSITIVE
    );

    // 英文注入短语
    private static final Pattern INJECTION_PHRASE_EN = Pattern.compile(
        "(ignore|forget|disregard|override)(\\s+(all|the|previous|above|prior))?(\\s+(instructions|commands|rules|directives|prompts|constraints))?" +
        "|(you are now|from now on|act as|you are a|i want you to)(\\s+(a|an|the))?" +
        "|(new role|new instruction|new rule)[\\s\\S]{0,20}",
        Pattern.CASE_INSENSITIVE
    );

    // ==================== 3. 分隔符伪造 ====================
    // 匹配 ---xxx内容开始--- 或 ---xxx内容结束--- 等伪造分隔符
    private static final Pattern DELIMITER_FAKE = Pattern.compile(
        "---[\\u4e00-\\u9fa5\\w]+(内容|数据|文本)?(开始|结束|start|end)---",
        Pattern.CASE_INSENSITIVE
    );

    // ==================== 4. 边界标签伪造 ====================
    // 匹配 <data-boundary-xxx> 或 </data-boundary-xxx> 标签
    private static final Pattern BOUNDARY_TAG_FAKE = Pattern.compile(
        "<\\/?data-boundary-[a-f0-9-]+>",
        Pattern.CASE_INSENSITIVE
    );

    // ==================== 占位符 ====================
    private static final String PLACEHOLDER_ROLE_MARKER = "[filtered-role-marker]";
    private static final String PLACEHOLDER_INJECTION = "[filtered]";
    private static final String PLACEHOLDER_DELIMITER = "[filtered-delimiter]";
    private static final String PLACEHOLDER_BOUNDARY = "[filtered-boundary]";

    /**
     * 对用户文本进行输入净化
     *
     * 四组正则依次匹配，命中的替换为中性占位符，同时记录注入尝试日志。
     *
     * @param text 原始用户输入文本
     * @return 净化后的文本
     */
    public String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;
        boolean hasInjection = false;

        // 1. 清洗行首角色标记
        Matcher roleMatcher = LINE_START_ROLE_MARKER.matcher(result);
        if (roleMatcher.find()) {
            hasInjection = true;
            log.warn("Prompt injection detected: line-start role marker, matched='{}'",
                truncate(roleMatcher.group(), 80));
            result = roleMatcher.replaceAll(PLACEHOLDER_ROLE_MARKER);
        }

        // 2. 清洗注入短语（中文）
        Matcher cnMatcher = INJECTION_PHRASE_CN.matcher(result);
        if (cnMatcher.find()) {
            hasInjection = true;
            log.warn("Prompt injection detected: CN injection phrase, matched='{}'",
                truncate(cnMatcher.group(), 80));
            result = cnMatcher.replaceAll(PLACEHOLDER_INJECTION);
        }

        // 3. 清洗注入短语（英文）
        Matcher enMatcher = INJECTION_PHRASE_EN.matcher(result);
        if (enMatcher.find()) {
            hasInjection = true;
            log.warn("Prompt injection detected: EN injection phrase, matched='{}'",
                truncate(enMatcher.group(), 80));
            result = enMatcher.replaceAll(PLACEHOLDER_INJECTION);
        }

        // 4. 清洗分隔符伪造
        Matcher delimMatcher = DELIMITER_FAKE.matcher(result);
        if (delimMatcher.find()) {
            hasInjection = true;
            log.warn("Prompt injection detected: delimiter fake, matched='{}'",
                truncate(delimMatcher.group(), 80));
            result = delimMatcher.replaceAll(PLACEHOLDER_DELIMITER);
        }

        // 5. 清洗边界标签伪造
        Matcher boundaryMatcher = BOUNDARY_TAG_FAKE.matcher(result);
        if (boundaryMatcher.find()) {
            hasInjection = true;
            log.warn("Prompt injection detected: boundary tag fake, matched='{}'",
                truncate(boundaryMatcher.group(), 80));
            result = boundaryMatcher.replaceAll(PLACEHOLDER_BOUNDARY);
        }

        if (hasInjection) {
            log.info("Prompt sanitization applied, original length={}, sanitized length={}",
                text.length(), result.length());
        }

        return result;
    }

    /**
     * 用 UUID 动态分隔符包裹用户数据
     *
     * 每次调用生成随机 UUID 片段作为标签的一部分，攻击者无法预知值，无法伪造关闭标签。
     * 和 CSRF Token 的思路一样——用不可预测性对抗伪造。
     *
     * 输出格式：
     * <data-boundary-{uuid}-{label}>
     * 用户数据...
     * </data-boundary-{uuid}-{label}>
     *
     * @param label 数据标签（如 "article", "comment", "pins"）
     * @param content 用户数据内容
     * @return 包裹后的文本
     */
    public String wrapWithDelimiters(String label, String content) {
        if (content == null) {
            return "";
        }
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String openTag = PromptSecurityConstants.DATA_BOUNDARY_PREFIX + uuid + "-" + label + PromptSecurityConstants.DATA_BOUNDARY_SUFFIX;
        String closeTag = PromptSecurityConstants.DATA_BOUNDARY_CLOSE_PREFIX + uuid + "-" + label + PromptSecurityConstants.DATA_BOUNDARY_SUFFIX;
        return openTag + "\n" + content + "\n" + closeTag;
    }

    /**
     * 便捷方法：先净化再包裹
     *
     * @param label 数据标签
     * @param content 用户数据内容
     * @return 净化并包裹后的文本
     */
    public String sanitizeAndWrap(String label, String content) {
        String sanitized = sanitize(content);
        return wrapWithDelimiters(label, sanitized);
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}