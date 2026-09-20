package com.zhuri.coding.content.service.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 额度包目录（定价演示；正式定价可挪配置）
 *
 * <p><b>双轨到账</b>：每档同时给「次数」与「tokens」——
 * <ul>
 *   <li>次数：历史口径，兼容老前端展示与存量逻辑；</li>
 *   <li>tokens：新计费口径（用量按 token 结算），不同功能的真实成本差 10 倍以上，
 *       按 token 计费才是公平且可控的（详见 {@code AiTokenMeter} / {@code AiModelRouter#costReport}）。</li>
 * </ul>
 * tokens 额度按"档位价格 ≈ 覆盖模型成本 + 毛利"反推：以 qwen-plus（0.0008/0.002 元每千 token）
 * 估算一次问答约 3k token（输入为主）≈ 0.006 元，故 ¥1.99 档给 500k tokens 仍有余量。
 */
public final class AiQuotaPackages {

    private AiQuotaPackages() {
    }

    /** code → {quota(次数), priceFen(分), tokenQuota(tokens)} */
    public static final Map<String, long[]> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put("q200", new long[]{200, 199, 500_000L});      // ¥1.99 / 200 次 或 50 万 tokens
        CATALOG.put("q1000", new long[]{1000, 899, 3_000_000L});  // ¥8.99 / 1000 次 或 300 万 tokens
        CATALOG.put("q5000", new long[]{5000, 3999, 20_000_000L}); // ¥39.99 / 5000 次 或 2000 万 tokens
    }

    public static boolean isValid(String code) {
        return code != null && CATALOG.containsKey(code);
    }

    /** 到账次数 */
    public static int quotaOf(String code) {
        long[] v = CATALOG.get(code);
        return v == null ? 0 : (int) v[0];
    }

    /** 实付金额（分） */
    public static int priceFenOf(String code) {
        long[] v = CATALOG.get(code);
        return v == null ? 0 : (int) v[1];
    }

    /** 到账 token 额度（新计费口径） */
    public static long tokenQuotaOf(String code) {
        long[] v = CATALOG.get(code);
        return v == null ? 0L : v[2];
    }
}
