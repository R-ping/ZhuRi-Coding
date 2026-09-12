package com.heima.content.utils;

/**
 * pgvector 文本字面量工具。
 *
 * <p>pgvector 接受 {@code '[v1,v2,...]'} 形式的文本输入（配合 {@code CAST(? AS vector)} 使用），
 * 相比 JDBC {@code createArrayOf} 无需 ConnectionCallback，写法更简单且避免大数组装箱开销。
 * 统一使用 {@code Double.toString/Float.toString} 输出，保证不受 Locale 影响（不会出现 1,5 这种小数逗号）。
 */
public final class PgVectorUtil {

    private PgVectorUtil() {
    }

    public static String toLiteral(double[] v) {
        if (v == null || v.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(v.length * 12 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Double.toString(v[i]));
        }
        return sb.append(']').toString();
    }

    public static String toLiteral(float[] v) {
        if (v == null || v.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(v.length * 12 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(v[i]));
        }
        return sb.append(']').toString();
    }
}
