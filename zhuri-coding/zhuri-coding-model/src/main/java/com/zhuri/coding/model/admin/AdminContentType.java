package com.zhuri.coding.model.admin;

/**
 * 可被运营折叠的内容类型。
 *
 * <p>折叠（{@code is_hidden}）目前有两张表共用同一套语义 —— 文章评论 {@code ap_comment}
 * 与沸点评论 {@code ap_pins_comment}。两表的折叠列定义完全一致
 * （{@code TINYINT NOT NULL DEFAULT 0}，0 正常 / 1 已折叠），但**可见范围不同**：
 * 文章评论折叠后评论者本人仍能看到（带"已折叠"标记），沸点评论折叠是全局隐藏。
 * 这个差异不在本枚举里，而在各自的列表查询里，别在运营侧统一表述。
 *
 * <p><b>为什么用枚举而不是 String</b>：运营接口要把类型透传到 service、审计、通知三处，
 * 用字符串就得在每处再校验一次，漏一处就出现"审计写了个拼错的类型、排查时按类型查不到"。
 * 解析失败一律返回 {@code null}（fail-closed），由调用方拒绝请求。
 */
public enum AdminContentType {

    /** 文章评论（ap_comment） */
    COMMENT("文章评论"),

    /** 沸点评论（ap_pins_comment） */
    PINS_COMMENT("沸点评论");

    private final String desc;

    AdminContentType(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }

    /** 解析类型编码；无法识别返回 {@code null}（调用方应拒绝请求，不要默认取一个值） */
    public static AdminContentType parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
