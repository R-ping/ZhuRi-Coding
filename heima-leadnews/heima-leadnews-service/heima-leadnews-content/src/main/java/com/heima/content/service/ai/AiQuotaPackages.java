package com.heima.content.service.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 额度包目录（定价演示；正式定价可挪配置）
 */
public final class AiQuotaPackages {

    private AiQuotaPackages() {
    }

    /** code → {quota(次数), priceFen(分)} */
    public static final Map<String, int[]> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put("q200", new int[]{200, 199});    // ¥1.99 / 200 次
        CATALOG.put("q1000", new int[]{1000, 899});  // ¥8.99 / 1000 次
        CATALOG.put("q5000", new int[]{5000, 3999}); // ¥39.99 / 5000 次
    }

    public static boolean isValid(String code) {
        return code != null && CATALOG.containsKey(code);
    }

    /** 到账次数 */
    public static int quotaOf(String code) {
        int[] v = CATALOG.get(code);
        return v == null ? 0 : v[0];
    }

    /** 实付金额（分） */
    public static int priceFenOf(String code) {
        int[] v = CATALOG.get(code);
        return v == null ? 0 : v[1];
    }
}
