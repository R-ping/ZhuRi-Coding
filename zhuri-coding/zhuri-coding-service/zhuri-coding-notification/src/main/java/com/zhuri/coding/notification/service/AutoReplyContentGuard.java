package com.zhuri.coding.notification.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 自动私信文案的内容校验。
 *
 * <p>本类承担两件事：<b>结构校验</b>（长度、换行、全符号）+ <b>导流拦截</b>（外链、手机号、拉私域话术）。
 * 两者都是确定性规则，可在保存时同步给出结论。
 *
 * <p><b>接入真实内容审核服务的位置</b>：本类只覆盖「确定违规」的形态，判断不了语义违规。
 * 后续接入文本审核后，在 {@link #check(String)} 里叠加一次远程调用，把结论写回
 * {@code audit_status}；调用方（保存链路）不需要改。审核不可用时按 fail-closed 处理 ——
 * 这条消息是以用户本人身份发出的，宁可漏发不可错发。
 */
@Component
public class AutoReplyContentGuard {

    /** 文案长度上限（按字符计，emoji 算 1 个） */
    public static final int MAX_LENGTH = 100;

    /** 换行上限 */
    public static final int MAX_LINES = 3;

    private static final Pattern URL = Pattern.compile(
        "(https?://|www\\.|\\b[a-zA-Z0-9-]+\\.(com|cn|net|org|top|xyz|io)\\b)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 拉私域话术：自动回复是导流高发位，关键词直接拦 */
    private static final Pattern CONTACT = Pattern.compile(
        "(加|联系|私)(微信|微|vx|v信|q|扣扣)|微信号|weixin|wechat|\\bqq\\b", Pattern.CASE_INSENSITIVE);

    /** 至少要有字母、数字或中日韩文字之一，纯标点/纯 emoji 没有信息量 */
    private static final Pattern MEANINGFUL = Pattern.compile("[\\p{L}\\p{N}]");

    /**
     * @param passed   是否通过
     * @param reason   未通过原因（可直接展示给用户）
     * @param blocking true=格式不合法，拒绝保存；false=内容不合规，保存但审核驳回
     */
    public record Result(boolean passed, String reason, boolean blocking) {
        public static Result ok() {
            return new Result(true, null, false);
        }

        /** 格式问题：用户改一下就能过，直接拒绝保存 */
        public static Result rejectFormat(String reason) {
            return new Result(false, reason, true);
        }

        /** 内容问题：进入审核驳回态，保留上一版生效文案 */
        public static Result rejectPolicy(String reason) {
            return new Result(false, reason, false);
        }
    }

    public Result check(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Result.rejectFormat("文案不能为空");
        }
        // 先压掉首尾空白，避免「开头顶几行空行」绕过换行数量限制
        String content = raw.strip();

        int length = content.codePointCount(0, content.length());
        if (length > MAX_LENGTH) {
            return Result.rejectFormat("文案最多 " + MAX_LENGTH + " 字，当前 " + length + " 字");
        }
        long lines = content.chars().filter(c -> c == '\n').count() + 1;
        if (lines > MAX_LINES) {
            return Result.rejectFormat("文案最多 " + MAX_LINES + " 行");
        }
        if (!MEANINGFUL.matcher(content).find()) {
            return Result.rejectFormat("文案不能只有符号或表情");
        }
        if (URL.matcher(content).find()) {
            return Result.rejectPolicy("文案不能包含链接");
        }
        if (PHONE.matcher(content).find()) {
            return Result.rejectPolicy("文案不能包含手机号");
        }
        if (CONTACT.matcher(content).find()) {
            return Result.rejectPolicy("文案不能包含微信、QQ 等联系方式");
        }
        if (hasRunawayRepeat(content)) {
            return Result.rejectPolicy("请勿重复同一字符刷屏");
        }
        return Result.ok();
    }

    /** 同一字符连续出现超过 10 次视为刷屏（emoji 由 code point 判定，避免代理对误判） */
    private boolean hasRunawayRepeat(String content) {
        int repeat = 1;
        int prev = -1;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            if (cp == prev) {
                if (++repeat > 10) {
                    return true;
                }
            } else {
                repeat = 1;
                prev = cp;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
