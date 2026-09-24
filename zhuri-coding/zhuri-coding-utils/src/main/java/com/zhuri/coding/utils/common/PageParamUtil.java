package com.zhuri.coding.utils.common;

/**
 * 分页参数收敛工具。
 *
 * <p><b>为什么需要</b>：分页接口若把 {@code size} 原样交给 SQL / 内存循环，调用方传
 * {@code size=2000000000} 即可让单次请求拉全表或耗尽内存（匿名可达的公开只读接口尤其危险）。
 * 此前各 service 各写各的 {@code Math.min(size, 50)}，口径分散且易漏；统一收口到本类。
 *
 * <p><b>约定</b>：默认单页上限 {@link #DEFAULT_MAX_SIZE} = 50，与 {@code ApArticleServiceImpl.MAX_PAGE_SIZE}
 * 保持一致；调用方可显式传入更小的上限。
 */
public final class PageParamUtil {

    /** 默认单页最大条数（与现有主流口径一致） */
    public static final int DEFAULT_MAX_SIZE = 50;

    /** 默认页码（从 1 开始） */
    private static final int DEFAULT_PAGE = 1;

    private PageParamUtil() {
    }

    /**
     * 归一化页码：小于 1 一律归为第 1 页。
     *
     * @param page 原始页码
     * @return 合法页码（&gt;= 1）
     */
    public static int normalizePage(int page) {
        return page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
    }

    /**
     * 归一化单页条数：小于 1 取默认值，大于上限则截断到上限（防止一次拉全表）。
     *
     * @param size 原始条数
     * @return 合法条数（1 ~ {@link #DEFAULT_MAX_SIZE}）
     */
    public static int normalizeSize(int size) {
        return normalizeSize(size, DEFAULT_MAX_SIZE);
    }

    /**
     * 归一化单页条数（自定义上限）。
     *
     * @param size 原始条数
     * @param max  允许的最大条数
     * @return 合法条数（1 ~ max）
     */
    public static int normalizeSize(int size, int max) {
        int upper = max < 1 ? DEFAULT_MAX_SIZE : max;
        if (size < 1) {
            return upper;
        }
        return Math.min(size, upper);
    }

    /**
     * 计算安全的偏移量。
     *
     * <p>直接用 {@code (page - 1) * size} 在 page/size 都很大时会 int 溢出为负数，
     * 拼进 SQL 后得到非法的 {@code LIMIT -x,-y} 并抛错；此处用 long 运算并夹取到 int 区间。
     *
     * @param page 已归一化页码
     * @param size 已归一化条数
     * @return 非负偏移量
     */
    public static int offset(int page, int size) {
        long raw = (long) (normalizePage(page) - 1) * normalizeSize(size);
        if (raw < 0) {
            return 0;
        }
        return raw > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) raw;
    }
}
