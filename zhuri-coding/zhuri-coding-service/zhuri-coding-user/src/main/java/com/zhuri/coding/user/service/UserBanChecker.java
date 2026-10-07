package com.zhuri.coding.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.common.exception.BusinessException;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.user.mapper.ApUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * 封禁校验：**封禁真正生效的地方**。
 *
 * <p><b>为什么挂在这里</b>：{@code TokenServiceImpl#generateDualToken} 是所有入口的唯一汇聚点 ——
 * 手机验证码登录、密码登录、社交登录、公众号登录，以及 refresh_token 刷新（它内部也是调这个方法），
 * 全部经过它。在这一处拦一次，就覆盖了全部"拿到新 token"的路径；散在各个登录方式里
 * 必然会漏掉后来新增的那一种。
 *
 * <p><b>已知边界：已签发的 access_token 在过期前（≤1 小时）仍然有效</b>。
 * 本项目用的是无状态 JWT，服务端没有 token 台账，封禁无法逐个吊销已发出的 token。
 * 要彻底关掉这个窗口，需要的是"每请求校验的黑名单"（网关或拦截器逐次查 Redis），
 * 那是另一个量级的成本与故障面，不在本次范围。运营口径上要清楚：<b>封禁 = 拦新登录与续期，
 * 不是"立刻踢下线"</b>。因此封禁时必须把理由随登录报错一起返回（见 {@link #assertNotBanned}），
 * 否则被封的人只会看到一句无来由的失败。
 *
 * <p><b>查询异常按"未封禁"放行（fail-open）</b>：封禁校验是登录链路上的一个附加检查，
 * 它的失败不该让全站用户都登不进来。数据库真挂了是全局故障，由更上游的告警去发现，
 * 不必在这儿再制造一次雪崩。
 */
@Slf4j
@Component
public class UserBanChecker {

    /** 登录报错里回显的解封时间；只到分钟，秒对当事人没有意义 */
    private static final DateTimeFormatter UNTIL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 永久封禁的判定阈值：封禁截止年份 ≥ 该值即视为永久。
     *
     * <p>与写入侧 {@code UserBanServiceImpl#PERMANENT_UNTIL} 的"写遥远未来时间"手法配对使用
     * —— 那边负责写、这边负责认，阈值放在双方都引用的这个类里，避免各写一份后分叉。
     */
    public static final int PERMANENT_YEAR_THRESHOLD = 9000;

    @Autowired
    private ApUserMapper userMapper;

    /**
     * 校验账号未被封禁；命中则抛 {@link BusinessException}（携带原因与解封时间）。
     *
     * <p>只取判定所需的三列：登录与刷新都会走到这里，没必要把整行（含密码哈希）拉出来。
     *
     * @param userId 账号ID；为 null 时直接放行（后续流程自会因取不到用户而失败）
     */
    public void assertNotBanned(Integer userId) {
        if (userId == null) {
            return;
        }
        ApUser banned;
        try {
            banned = loadBanState(userId);
        } catch (Exception e) {
            log.error("封禁校验失败，按未封禁放行, userId={}", userId, e);
            return;
        }
        if (banned == null) {
            return;
        }
        Date now = new Date();
        if (!banned.isBannedAt(now)) {
            return;
        }
        log.warn("已封禁账号尝试获取登录凭证被拒, userId={}, banUntil={}", userId, banned.getBanUntil());
        throw new BusinessException(AppHttpCodeEnum.USER_BANNED.getCode(), bannedMessage(banned, now));
    }

    /**
     * 组装对当事人可见的封禁说明。
     *
     * <p>必须带上"封到什么时候"：只说"你被封了"会立刻引来一轮客服咨询，
     * 而当事人自己也不知道该"等一等"还是"去申诉"。这是被封账号唯一能收到的权威告知 ——
     * 他登不进来，站内信是看不到的。
     */
    private String bannedMessage(ApUser user, Date now) {
        StringBuilder sb = new StringBuilder("账号已被封禁");
        if (isPermanent(user.getBanUntil())) {
            sb.append("（永久）");
        } else {
            sb.append("，解封时间 ").append(UNTIL_FORMATTER.format(toLocalDateTime(user.getBanUntil())))
                .append("（约剩 ").append(remainingDays(user.getBanUntil(), now)).append(" 天）");
        }
        String reason = user.getBanReason();
        if (reason != null && !reason.isBlank()) {
            sb.append("。原因：").append(reason.trim());
        }
        sb.append("。如有异议可通过申诉渠道提交复核。");
        return sb.toString();
    }

    /** 剩余天数（向上取整：还剩 3 小时说"剩 1 天"比说"剩 0 天"更不容易被读成已解封） */
    private long remainingDays(Date until, Date now) {
        long millis = until.getTime() - now.getTime();
        return Math.max(1, (millis + 86_399_999L) / 86_400_000L);
    }

    /** 转系统时区的 LocalDateTime；格式化器带 HH:mm，传 LocalDate 会抛 UnsupportedTemporalTypeException */
    private LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** 是否永久封禁：按年份判断，与写入侧的"遥远未来时间"手法配对 */
    public static boolean isPermanent(Date banUntil) {
        if (banUntil == null) {
            return false;
        }
        return banUntil.toInstant().atZone(ZoneId.systemDefault()).getYear() >= PERMANENT_YEAR_THRESHOLD;
    }

    /**
     * 账号当前是否处于封禁态；给需要"按封禁状态区别对待"的业务用。
     *
     * <p>失败的取舍与 {@link #assertNotBanned} 一致：查不出来按未封禁处理。
     */
    public boolean isBanned(Integer userId) {
        if (userId == null) {
            return false;
        }
        try {
            ApUser user = loadBanState(userId);
            return user != null && user.isBannedAt(new Date());
        } catch (Exception e) {
            log.error("封禁状态查询失败，按未封禁处理, userId={}", userId, e);
            return false;
        }
    }

    /** 只取判定所需的三列；账号不存在返回 null */
    private ApUser loadBanState(Integer userId) {
        List<ApUser> rows = userMapper.selectList(new LambdaQueryWrapper<ApUser>()
            .select(ApUser::getId, ApUser::getBanUntil, ApUser::getBanReason)
            .eq(ApUser::getId, userId));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
