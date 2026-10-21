package com.zhuri.coding.content.service.coding;

/**
 * 评估口径的共用纯函数（每日一题单题评估 与 模拟面试报告评估 共用）。
 *
 * <p>抽出来的理由：这两条链路对「等级怎么夹取、覆盖度怎么从清单换算成分」的口径
 * 必须一致，否则同一个用户在做题页和报告页看到的等级标准不一样。
 * 这是第二个消费者出现之后才抽的 —— 只有一个消费者时留在原地更简单。</p>
 */
public final class CodingEvaluation {

    /** 等级下限 */
    public static final int MIN_LEVEL = 1;
    /** 等级上限 */
    public static final int MAX_LEVEL = 5;

    private CodingEvaluation() {
    }

    /**
     * 等级夹取到 1-5；缺失按 3（基本合格）中性处理 —— 模型未给等级时不虚高也不误伤。
     */
    public static int clampLevel(Integer level) {
        return level == null ? 3 : Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    /**
     * 覆盖度等级：{@code covered/(covered+missing)} 比例映射 1-5。
     *
     * <p><b>服务端自己算，不采信模型自评的覆盖分</b>：清单可以逐条核对，分数只能信。
     * 没有任何考点信息时从低（1）。</p>
     */
    public static int coverageLevel(int covered, int missing) {
        int total = covered + missing;
        if (total <= 0) {
            return MIN_LEVEL;
        }
        double ratio = covered * 1.0 / total;
        if (ratio >= 0.8) {
            return 5;
        }
        if (ratio >= 0.6) {
            return 4;
        }
        if (ratio >= 0.4) {
            return 3;
        }
        if (ratio >= 0.2) {
            return 2;
        }
        return MIN_LEVEL;
    }

    /**
     * 综合等级 = 三维均值四舍五入后夹取。
     *
     * <p>三个维度权重相同是有意的：结构乱但内容对的回答，和结构清楚但讲错的回答，
     * 都不该拿高分，也不该被单一维度一票否决。</p>
     */
    public static int combinedLevel(Integer structure, Integer coverageScore, Integer accuracy) {
        int s = clampLevel(structure);
        int c = clampLevel(coverageScore);
        int a = clampLevel(accuracy);
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, (int) Math.round((s + c + a) / 3.0)));
    }
}
