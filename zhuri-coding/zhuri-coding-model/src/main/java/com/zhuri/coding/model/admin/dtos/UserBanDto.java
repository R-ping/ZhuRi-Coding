package com.zhuri.coding.model.admin.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 封禁入参。
 *
 * <p>{@code reason} 继承自 {@link AdminActionDto}，必填；这里额外给一个封禁时长。
 * <b>时长用"天数"而不是截止时间</b>：让前端去算一个绝对时间点，它就得先知道服务器的当前时间，
 * 客户端时钟偏一点就会封成"已过期"或"多封三天"。只传天数，截止时间由服务端算，只有一个时钟。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserBanDto extends AdminActionDto {

    private static final long serialVersionUID = 1L;

    /**
     * 封禁天数，允许范围见 {@link #MIN_DAYS}~{@link #MAX_DAYS}。
     *
     * <p>null 或 <=0 表示<b>永久封禁</b>（写入远期时间）。用"不传即永久"而不是加一个
     * {@code permanent} 布尔位：布尔位与天数会出现"permanent=true 但 days=3"这类自相矛盾的入参，
     * 还得在服务端定谁优先。
     */
    private Integer days;

    /** 最短封禁 1 天：写 0 会被当成永久，这不是一个"短封"的合理表达，拦住它 */
    public static final int MIN_DAYS = 1;

    /** 最长 10 年：再往上等同于永久，不如直接留空用永久——免得出现"封到 3026 年"这种数据 */
    public static final int MAX_DAYS = 3650;

    /** 是否请求永久封禁 */
    public boolean permanent() {
        return days == null || days < MIN_DAYS;
    }
}
