package com.zhuri.coding.content.schedule;

import com.zhuri.coding.content.mapper.coding.ApCodingInterviewMapper;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Coding 场次滞留收尾任务（模拟面试 + 能力测评）。
 *
 * <p><b>为什么需要</b>：面试与测评的"超时即废"目前只有<b>懒过期</b>——判定写在
 * {@code start / current / submit / finish} 里，只有用户下一次触达才会把 {@code status=1} 改成已过期。
 * 用户中途关掉页面再也不回来时，这一行就永远停在"进行中"：每日场次上限、后台统计与运营看板
 * 都会被这类僵尸场次污染。审核链路有 {@code ArticleAuditRecoveryTask} 做滞留补偿，这里补齐 Coding 侧同一环。</p>
 *
 * <p><b>与审核滞留补偿的差别</b>：那边需要"提交超过 N 分钟仍未推进"这样一个额外阈值来压并发窗口；
 * 这里不需要——{@code deadline_time} 本身就是开面/开卷时写死的截止时刻（含 45 / 15 分钟时长），
 * 超过它即确定无疑地已超时。</p>
 *
 * <p><b>幂等与并发安全</b>：UPDATE 带 {@code status = 1} 条件，与懒过期、以及用户此刻正在交卷/结束的
 * 并发请求天然互斥——已完成/已过期的行不会被覆盖；重复扫描只会命中仍未过期的行，第二次自然影响 0 行。
 * 这与既有 {@code expireOngoing} 是同一口径，两条路径可安全并存。</p>
 *
 * <p><b>只改 status，不动其它列</b>：与懒过期写入的列集合保持一致，避免"两条路径对同一行写出不同旁路数据"
 * 这种难排查的差异。</p>
 *
 * <p><b>索引</b>：{@code WHERE status = 1 AND (deadline_time IS NULL OR deadline_time < ?)} 靠
 * {@code idx_status} 定位。该索引选择性虽低（3 个取值），但"进行中"的行数天然极少，
 * 不需要为它新造索引。</p>
 */
@Slf4j
@Component
public class CodingSessionRecoveryTask {

    @Autowired
    private ApCodingInterviewMapper interviewMapper;

    /** 单批处理上限（一次更新的最大行数，避免长事务/大批锁）；默认值同时作为单测无容器时的兜底 */
    @Value("${app.coding.recovery.batch:200}")
    private int batchSize = 200;

    @Scheduled(fixedDelayString = "${app.coding.recovery.interval-ms:300000}",
        initialDelayString = "${app.coding.recovery.initial-delay-ms:120000}")
    public void expireStaleSessions() {
        expireInterviews(new Date());
    }

    /** 面试侧收尾；异常不外抛（下一轮扫描会重试同一批行） */
    private void expireInterviews(Date now) {
        try {
            int rows = interviewMapper.expireStaleOngoing(now, batchSize);
            if (rows > 0) {
                log.info("Coding 场次滞留收尾：面试 {} 场已超时未结束，置为已过期", rows);
            }
        } catch (Exception e) {
            log.error("Coding 面试场次滞留收尾异常", e);
        }
    }
}
