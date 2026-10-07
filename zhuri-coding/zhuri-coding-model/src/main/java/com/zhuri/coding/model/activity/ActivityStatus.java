package com.zhuri.coding.model.activity;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 活动状态机（{@code ap_activity.status}）。
 *
 * <p><b>为什么状态是 5 个而不是建表注释里的 3 个</b>：建表时的 {@code upcoming/ongoing/ended}
 * 只描述了"活动处在时间轴的哪一段"，那是**由日期算出来的事实**，不是运营能决定的动作。
 * 而 CMS 真正要表达的是另外两件事 —— "这条活动还没对外发布"（{@code draft}）与
 * "这条活动被运营撤下来了"（{@code offline}）。两者原先无处表达，于是草稿和下线只能靠
 * 删数据/改库实现。加进来之后这 5 个取值分成两类：
 *
 * <ul>
 *   <li><b>运营决定的</b>：{@link #DRAFT}（草稿）、{@link #OFFLINE}（已下线）——
 *       与日期无关，只要运营不改，它就一直是这样；</li>
 *   <li><b>日期决定的</b>：{@link #UPCOMING}、{@link #ONGOING}、{@link #ENDED} ——
 *       合称「已发布」。它们由 {@link #phaseOf} 从 {@code start_date / end_date} 推出，
 *       不需要谁来手工设置，定时任务也只是把推出来的结果写回列里。</li>
 * </ul>
 *
 * <p>这个划分带来一条重要的简化：<b>"上线"与"下线"是两个动作，而"开始/结束"不是动作</b>。
 * 上线时用 {@link #phaseOf} 一次算出该落哪个阶段（一条早就过了结束日期的活动被重新上线，
 * 直接就是 {@code ended}，不会先闪一下 {@code upcoming}），之后交给定时任务按日历推进。
 *
 * <p><b>C 端可见性只有一条判据</b>：{@link #isPublished}。草稿与已下线一律不可见，
 * 其余三种可见（"已结束"仍然可见 —— 历史活动要在列表里留档，这是活动页的常规形态）。
 *
 * <p><b>为什么是常量类而不是枚举</b>：{@code status} 是可空 varchar，库里已经存在
 * 建表期写入的历史值，且这些值会被拼进 SQL 条件。用枚举就要在每个边界做一次
 * {@code parse}，而 {@code parse} 失败时的默认值选择本身是个需要解释的决策；
 * 常量 + {@link #normalize} 的写法让"未知取值"表现为"不在任何集合里"（fail-closed），
 * 不必给出一句"认不出时当作 X"。
 */
public final class ActivityStatus {

    private ActivityStatus() {
    }

    /** 草稿：运营已建档、尚未对外发布。C 端不可见。 */
    public static final String DRAFT = "draft";

    /** 即将开始：已发布，且当前时间早于 {@code start_date}。 */
    public static final String UPCOMING = "upcoming";

    /** 进行中：已发布，且当前时间落在 {@code [start_date, end_date]} 之间（含首尾两天）。 */
    public static final String ONGOING = "ongoing";

    /** 已结束：已发布，且当前时间已过 {@code end_date} 当天。 */
    public static final String ENDED = "ended";

    /** 已下线：运营主动撤下，或"草稿/上线"之外的第三种不可见状态。C 端不可见。 */
    public static final String OFFLINE = "offline";

    /**
     * 「已发布」= C 端可见 + 阶段由日期决定。
     *
     * <p>刻意用 {@link LinkedHashSet} 而不是 {@code Set.of}：{@code Set.of} 的迭代顺序
     * 在不同 JVM 运行间不稳定，而这里会出现在错误提示与测试断言里，需要可复现的顺序。
     */
    private static final Set<String> PUBLISHED = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(UPCOMING, ONGOING, ENDED)));

    /** 全部合法取值（顺序即错误提示里的展示顺序）。 */
    private static final Set<String> ALL = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(DRAFT, UPCOMING, ONGOING, ENDED, OFFLINE)));

    /** 已发布的三种状态（阶段，按时间先后排列） */
    public static Set<String> publishedStatuses() {
        return PUBLISHED;
    }

    /** 全部合法状态 */
    public static Set<String> allStatuses() {
        return ALL;
    }

    /**
     * 归一化：去空白 + 转小写。
     *
     * <p>大小写不敏感是刻意的：这个值会从 JSON、查询参数、以及手写 SQL 三处进来，
     * 让 {@code Ongoing} 与 {@code ongoing} 变成两个状态只会制造一种毫无价值的排查工作。
     *
     * @return 归一化后的编码；入参为 null/空白时返回 {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 是否是本系统认识的状态编码（大小写不敏感） */
    public static boolean isValid(String raw) {
        String code = normalize(raw);
        return code != null && ALL.contains(code);
    }

    /** 是否已发布（= C 端可见），入参未被识别时返回 false */
    public static boolean isPublished(String raw) {
        String code = normalize(raw);
        return code != null && PUBLISHED.contains(code);
    }

    /**
     * 是否对 C 端可见。
     *
     * <p>与 {@link #isPublished} 是同一个判据，单独留一个名字是为了让 C 端读路径的代码
     * 读起来在说"可见性"而不是在说"发布状态" —— 两处很容易被后来的人改成分开维护。
     */
    public static boolean isVisibleToClient(String raw) {
        return isPublished(raw);
    }

    /** 是否草稿 */
    public static boolean isDraft(String raw) {
        return DRAFT.equals(normalize(raw));
    }

    /** 是否已下线 */
    public static boolean isOffline(String raw) {
        return OFFLINE.equals(normalize(raw));
    }

    /**
     * 由日期推导活动阶段（{@link #UPCOMING} / {@link #ONGOING} / {@link #ENDED}）。
     *
     * <p><b>按"天"比较，而不是按时间点</b>：{@code start_date / end_date} 是 {@code DATE} 列，
     * 没有时刻。若直接拿"活动结束日是今天 00:00"去和"现在 14:00"比，当天结束的活动会被判成
     * 已结束 —— 而活动页上它明明还在进行。所以这里把两端都摊成整天：
     * 起点取当天 00:00，终点取当天 23:59:59.999，落在闭区间内即 {@link #ONGOING}。
     *
     * <p>用 {@link Calendar} 做当天边界而不是给日期加减 86400 秒：夏令时地区的某一天
     * 只有 23 小时，用固定毫秒数推进会整体错位一小时，进而把边界那天的活动算错。
     *
     * @param startDate 开始日期（{@code DATE} 列，通常为当天 00:00）
     * @param endDate   结束日期（同上）
     * @param now       参照时刻
     * @return 阶段编码；{@code startDate} 或 {@code endDate} 为 null 时返回 {@code null}
     *         （调用方在入库前必须已经校验过这两个字段必填，这里返回 null 表示"数据本身不完整"）
     */
    public static String phaseOf(Date startDate, Date endDate, Date now) {
        if (startDate == null || endDate == null || now == null) {
            return null;
        }
        if (now.before(startOfDay(startDate))) {
            return UPCOMING;
        }
        if (now.after(endOfDay(endDate))) {
            return ENDED;
        }
        return ONGOING;
    }

    /** 以"现在"为参照推导阶段 */
    public static String phaseOf(Date startDate, Date endDate) {
        return phaseOf(startDate, endDate, new Date());
    }

    /**
     * 能否从当前状态"上线"。
     *
     * <p>只有 {@link #DRAFT} 与 {@link #OFFLINE} 可以 —— 这两个状态的共同点是
     * "当前对外不可见"，上线是在改变可见性。已经是 {@code upcoming/ongoing/ended}
     * 的活动再点一次上线，是一句"我已经是了"的空动作，没有可写进审计的内容，
     * 因此被当作参数错误拒掉（与运营位配置"与现状一致就报错"同一口径）。
     */
    public static boolean canPublish(String from) {
        return isDraft(from) || isOffline(from);
    }

    /**
     * 能否从当前状态"下线"。
     *
     * <p>{@link #ENDED} 也算可以：把一条历史活动从列表里撤掉是真实需求，
     * 而且这个动作可逆（重新上线即可）。所以没有把 ended 当终态。
     */
    public static boolean canOffline(String from) {
        return isPublished(from);
    }

    /** 在错误提示里展示"当前状态"；认不出的取值原样带出，便于排查脏数据 */
    public static String describe(String raw) {
        String code = normalize(raw);
        if (code == null) {
            return "未设置";
        }
        if (DRAFT.equals(code)) {
            return "草稿";
        }
        if (UPCOMING.equals(code)) {
            return "即将开始";
        }
        if (ONGOING.equals(code)) {
            return "进行中";
        }
        if (ENDED.equals(code)) {
            return "已结束";
        }
        if (OFFLINE.equals(code)) {
            return "已下线";
        }
        return code;
    }

    /** 当天 00:00:00.000 */
    private static Date startOfDay(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    /** 当天 23:59:59.999 */
    private static Date endOfDay(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 999);
        return c.getTime();
    }
}
