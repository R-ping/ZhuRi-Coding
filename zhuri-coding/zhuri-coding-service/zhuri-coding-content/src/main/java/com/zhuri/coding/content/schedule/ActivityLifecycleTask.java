package com.zhuri.coding.content.schedule;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhuri.coding.content.mapper.activity.ApActivityMapper;
import com.zhuri.coding.model.activity.ActivityStatus;
import com.zhuri.coding.model.activity.pojos.ApActivity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;

/**
 * 活动生命周期推进任务：把"已发布"的活动按日历推到正确的阶段。
 *
 * <p><b>为什么需要它</b>：{@link ActivityStatus} 把活动状态分成两类 ——
 * 运营决定的可见性（草稿 / 已下线 / 已发布）与日期决定的阶段（即将开始 / 进行中 / 已结束）。
 * 后者是**由 {@code start_date / end_date} 算出来的事实**，但 C 端的按状态筛选
 * （以及 {@code idx_status_date} 这个索引）读的是列里的值。于是这个事实必须有人定期写回列里，
 * 否则一条 9 月 1 日开始的活动会在 10 月仍然显示"即将开始"。
 *
 * <p>写回而不是"每次查询时现算"是刻意的：现算意味着 SQL 里要出现
 * {@code CASE WHEN NOW() BETWEEN start_date AND end_date}，任何索引都会失效，
 * 活动列表退化成全表扫。用一列冗余状态换来可索引的筛选条件，代价是这个任务。
 *
 * <p><b>只动 {@code status} 一列</b>：参与人数、阅读量这些统计列由别的链路维护，
 * 这里碰它们会引发"到底谁写的"这种没人能回答的问题。{@code updated_time} 会跟着变，
 * 那是列上的 {@code ON UPDATE CURRENT_TIMESTAMP} 行为，正好表达"这行刚被系统改过"。
 *
 * <p><b>两条 UPDATE 的顺序不能颠倒</b>：先"即将开始 → 进行中"（{@code start_date <= 今天}），
 * 再"进行中 → 已结束"（{@code end_date < 今天}）。反过来的话，一条早已过期但仍标着
 * {@code upcoming} 的活动（比如应用停机期间错过的窗口）会在第一条 UPDATE 里直接变成
 * {@code ongoing}，而第二条此刻已经跑过了 —— 它要等到下一个 15 分钟才会被修正，
 * 中间这段时间它就是错的。按现在这个顺序，一次扫描就能落到终态。
 *
 * <p><b>按"天"比较</b>：{@code start_date / end_date} 是 {@code DATE} 列，没有时刻。
 * 传入的参照值是今天 00:00，于是 {@code end_date = 今天} 不会被判成已结束
 * （今天是它最后一天，它还该在"进行中"）；若拿"此刻"去比，今天 00:00 就小于此刻了，
 * 当天的活动会被提前结束一整天。
 *
 * <p><b>并发安全与幂等</b>：两条 UPDATE 都带 {@code AND status = 旧值}，所以重复执行、
 * 多实例同时执行、或与运营此刻正在"上线/下线"的请求撞上，都只会有一方生效 ——
 * 另一方影响 0 行，被影响的行不会被覆盖成错误的状态。草稿与已下线在 {@code eq(status, ...)}
 * 之外，永远不会被这个任务碰到。
 *
 * <p><b>不加批量上限</b>：{@code ap_activity} 是人手维护的活动表（当前二十余行），
 * 一次能改动的行数天然很小；加一个 {@code LIMIT} 只会让"某次扫到一半"这种状态成为可能，
 * 而它换不来任何实际保护。
 */
@Slf4j
@Component
public class ActivityLifecycleTask {

    @Autowired
    private ApActivityMapper apActivityMapper;

    /**
     * 每 15 分钟一轮。
     *
     * <p>用 cron 而不是 {@code fixedDelay}：阶段切换有明确的时刻（今天 00:00），
     * {@code fixedDelay} 的相位取决于应用启动时间，会让切换时刻随机漂移；
     * 而 15 分钟的网格包含整点，所以"今天开始"的活动会在 00:00 那一轮被翻过来。
     * 选 15 分钟而不是"每天 00:00 跑一次"是为了自愈：应用在 00:00 停机的话，
     * 下一次启动后最多 15 分钟就能把错过的窗口补上，不必等到第二天。
     */
    @Scheduled(cron = "0 0/15 * * * ?")
    public void advanceStatus() {
        Date today = startOfToday();
        int started = markOngoing(today);
        int ended = markEnded(today);
        if (started > 0 || ended > 0) {
            log.info("活动生命周期推进：{} 条进入进行中，{} 条进入已结束", started, ended);
        }
    }

    /**
     * 即将开始 → 进行中：已到开始日期。
     *
     * <p>异常在本方法内消化（返回 0），不向外抛也不影响下一步 —— 两段推进互不拖累：
     * 一条 SQL 失败（连接抖动、锁等待）不该让另一半已经该完成的状态推进也停摆。
     * 定时任务抛异常最终也只是被调度器记一笔日志，下一轮照样会重跑同一批行，
     * 丢掉的是本次另一半的推进机会。
     */
    private int markOngoing(Date today) {
        try {
            return apActivityMapper.update(null, new LambdaUpdateWrapper<ApActivity>()
                .set(ApActivity::getStatus, ActivityStatus.ONGOING)
                .eq(ApActivity::getStatus, ActivityStatus.UPCOMING)
                .le(ApActivity::getStartDate, today));
        } catch (Exception e) {
            log.error("活动状态推进失败（即将开始 -> 进行中），本轮跳过，下一轮重试", e);
            return 0;
        }
    }

    /** 进行中 → 已结束：已过结束日期（结束当天仍算进行中）；异常处理同 {@link #markOngoing(Date)} */
    private int markEnded(Date today) {
        try {
            return apActivityMapper.update(null, new LambdaUpdateWrapper<ApActivity>()
                .set(ApActivity::getStatus, ActivityStatus.ENDED)
                .eq(ApActivity::getStatus, ActivityStatus.ONGOING)
                .lt(ApActivity::getEndDate, today));
        } catch (Exception e) {
            log.error("活动状态推进失败（进行中 -> 已结束），本轮跳过，下一轮重试", e);
            return 0;
        }
    }

    /** 今天 00:00:00.000（按 JVM 默认时区，与 JDBC 连接的 serverTimezone 一致） */
    private static Date startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }
}
