package com.zhuri.coding.content.service.ai.qa;

/**
 * 语义缓存作用域键。
 *
 * <p>缓存必须同时区分模式与锚点：同一问题在 fast/deep、带/不带锚点、不同锚点文章下答案不同。
 * 单篇范围不写缓存，故不生成 article 作用域键。
 */
public final class AiCacheScope {

    /** 改造前旧数据；迁移时会被清理，新链路不会命中。 */
    public static final String LEGACY = "legacy";

    private AiCacheScope() {
    }

    public static String global(String mode, Long anchorArticleId) {
        String m = mode == null || mode.isBlank() ? "fast" : mode;
        String anchor = anchorArticleId == null ? "none" : String.valueOf(anchorArticleId);
        return "global:" + m + ":anchor:" + anchor;
    }
}
