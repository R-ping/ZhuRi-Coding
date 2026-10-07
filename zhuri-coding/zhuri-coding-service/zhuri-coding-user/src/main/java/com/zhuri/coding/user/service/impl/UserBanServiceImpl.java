package com.zhuri.coding.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.common.admin.AdminContext;
import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminUserBanVO;
import com.zhuri.coding.model.admin.vos.AdminUserDispositionRecordVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.user.admin.LocalAdminRoleResolver;
import com.zhuri.coding.user.admin.UserAdminAuditRecorder;
import com.zhuri.coding.user.admin.UserDispositionNotifier;
import com.zhuri.coding.user.mapper.ApAdminAuditLogMapper;
import com.zhuri.coding.user.mapper.ApUserMapper;
import com.zhuri.coding.user.service.UserBanChecker;
import com.zhuri.coding.user.service.UserBanService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营侧用户处置实现。
 *
 * <p>三条贯穿全篇的取舍，逐条对应一个容易踩的坑：
 *
 * <p><b>① 封禁另开字段，不复用 {@code status}</b>。{@code status=0} 在本系统的语义是"已注销/已锁定"，
 * {@code getValidUserIds} 按它把账号从批量投递名单里剔除。复用会让被封账号看起来跟自愿注销一样，
 * 运营分不清也就无从解封。所以封禁是 {@code ban_until > now} 这个独立的判定
 * （见 {@link ApUser#isBannedAt}），与 {@code status} 正交。
 *
 * <p><b>② 清空列必须走 UpdateWrapper</b>。本项目的 MyBatis-Plus 配了
 * {@code update-strategy: not_null}，{@code updateById} 会<b>跳过值为 null 的字段</b> ——
 * 用它解封会得到一句"操作成功"，而 {@code ban_until} 原封不动，用户还是登不进来。
 * 因此解封一律用 {@code LambdaUpdateWrapper#set(field, null)} 显式写 null。
 *
 * <p><b>③ 封禁可重复设置，解封才是单向闸口</b>。封禁的目标态是"封到某个时间"，
 * 再点一次只是把时间改掉，天然幂等，加条件更新反而会让"延长封禁"失败；
 * 而解封的目标态是"没封"，与当前态互斥，必须用 {@code WHERE ban_until > now} 卡住并发 ——
 * 两个运营同时解封，第二个应当收到"当前未被封禁"，而不是一条假成功。
 *
 * <p><b>重复处置与漏通知的定量说明</b>：{@code ban} 不做条件更新，所以并发下两个运营
 * 可能各写一次，最终值是后写的那个；两次都留了台账，谁先谁后查得到。
 * 封禁通知是 best-effort（理由见 {@link UserDispositionNotifier}），失败只记日志与出参，
 * 不要指望它一定送达 —— 被封账号的权威告知渠道是登录报错里的封禁原因。
 */
@Slf4j
@Service
public class UserBanServiceImpl implements UserBanService {

    /**
     * 永久封禁写入的截止时间。
     *
     * <p>写"遥远未来"而不是留空，是为了让"是否封禁中"永远只是一次比较（{@code ban_until > now}），
     * 从而<b>不需要一个定时任务去把到期账号挨个解开</b> —— 少一个会漏跑、会重复跑的组件。
     * 取 MySQL DATETIME 的上界（9999-12-31 23:59:59），同时被
     * {@link UserBanChecker#PERMANENT_YEAR_THRESHOLD} 认定为永久。
     */
    private static final LocalDateTime PERMANENT_UNTIL = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    /** 台账里回显时间用，只到分钟 */
    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Autowired
    private ApUserMapper userMapper;

    @Autowired
    private ApAdminAuditLogMapper auditLogMapper;

    @Autowired
    private UserAdminAuditRecorder auditSink;

    @Autowired
    private UserDispositionNotifier notifier;

    /** 只为在台账里标注"被处置的账号持有运营角色"，提醒后来人注意这行记录的分量 */
    @Autowired
    private LocalAdminRoleResolver adminRoleResolver;

    // ==================== 查询 ====================

    @Override
    public ResponseResult page(Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;

        Date now = new Date();
        LambdaQueryWrapper<ApUser> wrapper = new LambdaQueryWrapper<ApUser>()
            .select(ApUser::getId, ApUser::getNickname, ApUser::getImage, ApUser::getBanUntil,
                ApUser::getBanReason, ApUser::getBanTime, ApUser::getBanOperatorId)
            // "仍在封禁中" = 截止时间还没到；已到期的记录不出现在名单里（历史在台账里）
            .gt(ApUser::getBanUntil, now)
            // 最近封的排前面；末尾带 id 兜底，避免同一时间批量封禁时分页错行
            .orderByDesc(ApUser::getBanTime)
            .orderByDesc(ApUser::getId);

        IPage<ApUser> result = userMapper.selectPage(new Page<>(p, s), wrapper);
        List<AdminUserBanVO> list = result.getRecords().stream().map(this::toBanVO).toList();
        fillOperatorNicknames(list);

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        data.put("page", p);
        data.put("size", s);
        data.put("serverTime", now);
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult records(Integer userId, Integer page, Integer size) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "缺少账号ID");
        }
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;

        IPage<ApAdminAuditLog> result = auditLogMapper.selectPage(new Page<>(p, s),
            new LambdaQueryWrapper<ApAdminAuditLog>()
                // 显式投影：处置记录页不需要 ip / error_msg
                .select(ApAdminAuditLog::getId, ApAdminAuditLog::getAction, ApAdminAuditLog::getReason,
                    ApAdminAuditLog::getDetail, ApAdminAuditLog::getResult,
                    ApAdminAuditLog::getUserId, ApAdminAuditLog::getCreatedTime)
                .eq(ApAdminAuditLog::getModule, ApAdminAuditLog.MODULE_USER)
                .eq(ApAdminAuditLog::getTargetType, TARGET_USER)
                .eq(ApAdminAuditLog::getTargetId, String.valueOf(userId))
                .orderByDesc(ApAdminAuditLog::getId));

        List<AdminUserDispositionRecordVO> list = new ArrayList<>();
        for (ApAdminAuditLog row : result.getRecords()) {
            AdminUserDispositionRecordVO vo = new AdminUserDispositionRecordVO();
            vo.setId(row.getId());
            vo.setAction(row.getAction());
            vo.setReason(row.getReason());
            vo.setDetail(row.getDetail());
            vo.setResult(row.getResult());
            vo.setOperatorId(row.getUserId());
            vo.setCreatedTime(row.getCreatedTime());
            list.add(vo);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        data.put("page", p);
        data.put("size", s);
        // 附带累计警告次数：运营在这个页面上的核心判断就是"警告够了没有，该不该封"
        data.put("warnCount", countWarnings(userId));
        return ResponseResult.okResult(data);
    }

    // ==================== 警告 ====================

    /**
     * 警告账号。
     *
     * <p><b>通知 fail-closed，台账后写</b>：警告的全部效果就是当事人收到那条通知，
     * 通知发不出去这次警告就等于没发生。所以顺序是"先投递、成功了再落台账" ——
     * 审计表里最不能有的就是一条"看起来警告过、其实没人知道"的记录。反过来，
     * 极少数情况下通知已送达而落台账失败（事务提交失败），代价是重复一次警告，比前一种可接受。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult warn(Integer userId, String reason) {
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_USER, ACTION_WARN,
            TARGET_USER, String.valueOf(userId), reason);
        Integer operatorId = currentOperatorId();
        if (operatorId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        ApUser target = loadTarget(userId);
        if (target == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "账号不存在");
        }
        Date now = new Date();
        if (target.isBannedAt(now)) {
            // 已在封禁中的账号再警告没有意义，且会让"警告→封禁"的升级线索变模糊
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "该账号正在封禁中，无需重复警告");
        }

        int warnCount = countWarnings(userId) + 1;
        try {
            notifier.notify(String.valueOf(userId), UserDispositionNotifier.HANDLE_WARN,
                warnText(target, reason, warnCount));
        } catch (Exception e) {
            auditSink.recordFailure(audit, "警告通知投递失败: " + e.getMessage());
            log.error("[UserDisposition] 警告通知投递失败，本次警告未生效, userId={}", userId, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "警告通知发送失败，请稍后重试");
        }

        audit.setDetail("第 " + warnCount + " 次警告");
        auditSink.recordSuccess(audit);
        log.info("[UserDisposition] 已警告账号, userId={}, 第{}次, operator={}", userId, warnCount, operatorId);

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("warnCount", warnCount);
        return ResponseResult.okResult(data);
    }

    // ==================== 封禁 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult ban(Integer userId, String reason, Integer days) {
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_USER, ACTION_BAN,
            TARGET_USER, String.valueOf(userId), reason);
        try {
            return doBan(userId, reason, days, audit);
        } catch (Exception e) {
            // 业务事务注定回滚，失败记录走独立事务才留得下来
            auditSink.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doBan(Integer userId, String reason, Integer days, ApAdminAuditLog audit) {
        Integer operatorId = currentOperatorId();
        if (operatorId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (operatorId.equals(userId)) {
            // 自己的账号是唯一能把自己解开的账号，封了就是死结；这条同时挡住误点与脚本传错 id
            auditSink.recordFailure(audit, "不能封禁自己的账号");
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "不能封禁自己的账号");
        }
        ApUser target = loadTarget(userId);
        if (target == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "账号不存在");
        }

        Date now = new Date();
        boolean permanent = days == null;
        Date until = permanent ? toDate(PERMANENT_UNTIL) : Date.from(now.toInstant().plusSeconds(days * 86_400L));
        Date previousUntil = target.getBanUntil();

        // 不做条件更新：封禁的目标态是"封到某时刻"，重设只是改这个值，本身幂等。
        // 加闸口反而会让"把 7 天延长到 30 天"这种正常诉求失败。
        userMapper.update(null, new LambdaUpdateWrapper<ApUser>()
            .set(ApUser::getBanUntil, until)
            .set(ApUser::getBanReason, reason)
            .set(ApUser::getBanTime, now)
            .set(ApUser::getBanOperatorId, operatorId)
            .eq(ApUser::getId, userId));

        audit.setDetail(banDetail(target, previousUntil, until, permanent));
        auditSink.recordSuccess(audit);
        log.info("[UserDisposition] 已封禁账号, userId={}, until={}, permanent={}, operator={}",
            userId, until, permanent, operatorId);

        // 通知 best-effort：账号状态已经改完，通知服务抖动不该把它回滚。
        boolean notified = notifyQuietly(String.valueOf(userId), UserDispositionNotifier.HANDLE_BAN,
            banText(reason, until, permanent));
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("banUntil", until);
        data.put("permanent", permanent);
        data.put("notified", notified);
        return ResponseResult.okResult(data);
    }

    // ==================== 解封 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult unban(Integer userId, String reason) {
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_USER, ACTION_UNBAN,
            TARGET_USER, String.valueOf(userId), reason);
        try {
            return doUnban(userId, reason, audit);
        } catch (Exception e) {
            auditSink.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doUnban(Integer userId, String reason, ApAdminAuditLog audit) {
        Integer operatorId = currentOperatorId();
        if (operatorId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (operatorId.equals(userId)) {
            // 同理：被封的账号手里可能还有一张未过期的 accToken（见 UserBanChecker 的边界说明），
            // 不挡的话它就能给自己解封
            auditSink.recordFailure(audit, "不能解封自己的账号");
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "不能解封自己的账号");
        }
        ApUser target = loadTarget(userId);
        if (target == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "账号不存在");
        }

        Date now = new Date();
        if (!target.isBannedAt(now)) {
            // 先给一个可读的原因；真正卡并发的是下面的条件更新
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "该账号当前未被封禁，请刷新名单");
        }
        String released = releasedSnapshot(target);

        // 解封是单向闸口，必须条件更新：两个运营同时点，只有一个能改到这一行
        int affected = userMapper.update(null, new LambdaUpdateWrapper<ApUser>()
            .set(ApUser::getBanUntil, null)
            .set(ApUser::getBanReason, null)
            .set(ApUser::getBanTime, null)
            .set(ApUser::getBanOperatorId, null)
            .eq(ApUser::getId, userId)
            .gt(ApUser::getBanUntil, now));
        if (affected == 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "该账号刚刚已被其他运营解封，请刷新名单");
        }

        // 解封后的四列一律清空，而不是留着理由当"历史" —— 「ban_until IS NULL = 没被封过」
        // 这个不变量比"少查一次台账"值钱得多。封禁历史完整留在 ap_admin_audit_log 里。
        audit.setDetail("已解封。" + released);
        auditSink.recordSuccess(audit);
        log.info("[UserDisposition] 已解封账号, userId={}, 原{}", userId, released);

        boolean notified = notifyQuietly(String.valueOf(userId), UserDispositionNotifier.HANDLE_UNBAN,
            "你的账号已解除封禁，可以正常登录与使用了。原因：" + reason + "。给你带来的不便敬请谅解。");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("notified", notified);
        return ResponseResult.okResult(data);
    }

    // ==================== 通知 ====================

    /** best-effort 投递：失败只记日志，不抛给上层（调用方已在台账里记了这次处置） */
    private boolean notifyQuietly(String targetUserId, String handleType, String message) {
        try {
            notifier.notify(targetUserId, handleType, message);
            return true;
        } catch (Exception e) {
            log.error("[UserDisposition] 处置通知投递失败（处置已生效，不回滚）, to={}, handleType={}",
                targetUserId, handleType, e);
            return false;
        }
    }

    /** 警告文案：把"第几次"写进去，重复违规的人才会意识到自己在升级 */
    private String warnText(ApUser target, String reason, int warnCount) {
        return "你的账号因违反社区规范收到第 " + warnCount + " 次警告。原因：" + reason
            + "。请规范发布内容，再次违规将可能被限制账号功能。";
    }

    /** 封禁文案：必须写清是"临时"还是"永久"以及什么时候恢复，否则当事人连"等一等"还是"去申诉"都选不了 */
    private String banText(String reason, Date until, boolean permanent) {
        String period = permanent ? "已被永久封禁"
            : "已被封禁至 " + AUDIT_TIME.format(toLocalDateTime(until));
        return "你的账号" + period + "，原因：" + reason
            + "。封禁期间无法登录与发布内容。如有异议可通过申诉渠道提交复核。";
    }

    // ==================== 台账明细 ====================

    /**
     * 落进审计 {@code detail} 的变更摘要：只写"改了什么"，不写昵称之外的任何用户信息。
     *
     * <p>带上前一次的截止时间，是为了让"延长封禁"与"重复提交"在事后可区分 ——
     * 只看新值的话，两次记录长得一模一样。
     */
    private String banDetail(ApUser target, Date previousUntil, Date until, boolean permanent) {
        StringBuilder sb = new StringBuilder();
        sb.append("nickname=").append(nullToEmpty(target.getNickname()));
        sb.append(", banUntil: ").append(display(previousUntil)).append(" -> ").append(display(until));
        if (permanent) {
            sb.append("(永久)");
        }
        // 处置一个持有运营角色的账号，记录的分量不同，标注出来便于事后复核
        List<String> targetRoles = adminRoleResolver.roleCodesOf(target.getId());
        if (!targetRoles.isEmpty()) {
            sb.append(", targetRoles=").append(String.join("/", targetRoles));
        }
        return sb.toString();
    }

    /** 解封时把被释放的那次封禁快照进台账 —— 四列清空之后，这是唯一还能看到原封禁信息的本地位置 */
    private String releasedSnapshot(ApUser target) {
        return "原封禁: until=" + display(target.getBanUntil())
            + ", reason=" + nullToEmpty(target.getBanReason())
            + ", banTime=" + display(target.getBanTime())
            + ", operator=" + target.getBanOperatorId();
    }

    // ==================== 内部工具 ====================

    /**
     * 取处置对象。只查处置会用到的列 —— 尤其是<b>绝不把 password 带出来</b>，
     * 运营后台的任何一个对象都不该在内存里存着别人的密码哈希。
     */
    private ApUser loadTarget(Integer userId) {
        if (userId == null) {
            return null;
        }
        List<ApUser> rows = userMapper.selectList(new LambdaQueryWrapper<ApUser>()
            .select(ApUser::getId, ApUser::getNickname, ApUser::getImage, ApUser::getBanUntil,
                ApUser::getBanReason, ApUser::getBanTime, ApUser::getBanOperatorId)
            .eq(ApUser::getId, userId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 累计<b>成功</b>警告次数。失败的尝试（result=0）不算 —— 当事人没收到就不该被计入升级依据 */
    private int countWarnings(Integer userId) {
        if (userId == null) {
            return 0;
        }
        Long count = auditLogMapper.selectCount(new LambdaQueryWrapper<ApAdminAuditLog>()
            .eq(ApAdminAuditLog::getModule, ApAdminAuditLog.MODULE_USER)
            .eq(ApAdminAuditLog::getAction, ACTION_WARN)
            .eq(ApAdminAuditLog::getTargetType, TARGET_USER)
            .eq(ApAdminAuditLog::getTargetId, String.valueOf(userId))
            .eq(ApAdminAuditLog::getResult, ApAdminAuditLog.RESULT_SUCCESS));
        return count == null ? 0 : count.intValue();
    }

    private AdminUserBanVO toBanVO(ApUser user) {
        AdminUserBanVO vo = new AdminUserBanVO();
        vo.setUserId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getImage());
        vo.setBanUntil(user.getBanUntil());
        vo.setPermanent(UserBanChecker.isPermanent(user.getBanUntil()));
        vo.setBanReason(user.getBanReason());
        vo.setBanTime(user.getBanTime());
        vo.setBanOperatorId(user.getBanOperatorId());
        return vo;
    }

    /** 批量回填操作人昵称，避免名单里每条都显示一串数字 id（也避免每条一次查询） */
    private void fillOperatorNicknames(List<AdminUserBanVO> list) {
        if (list.isEmpty()) {
            return;
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (AdminUserBanVO vo : list) {
            if (vo.getBanOperatorId() != null) {
                ids.add(vo.getBanOperatorId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Integer, String> names = new HashMap<>();
        for (ApUser op : userMapper.selectList(new LambdaQueryWrapper<ApUser>()
            .select(ApUser::getId, ApUser::getNickname)
            .in(ApUser::getId, new ArrayList<>(ids)))) {
            names.put(op.getId(), op.getNickname());
        }
        for (AdminUserBanVO vo : list) {
            vo.setBanOperatorNickname(names.get(vo.getBanOperatorId()));
        }
    }

    /**
     * 操作人ID：优先取当前登录用户（与拦截器写入的身份同源），兜底用运营身份里的账号ID。
     *
     * <p>两者都取不到时返回 null，由调用方按"需要登录"处理 —— 不再往下发一个 {@code operator=null}
     * 的封禁，那会写出一个查不到操作人的台账。
     */
    private Integer currentOperatorId() {
        ApUser current = AppThreadLocalUtil.getUser();
        if (current != null && current.getId() != null) {
            return current.getId();
        }
        AdminIdentity identity = AdminContext.get();
        return identity == null ? null : identity.getUserId();
    }

    private static Date toDate(LocalDateTime dateTime) {
        return Date.from(dateTime.atZone(ZONE).toInstant());
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZONE).toLocalDateTime();
    }

    private static String display(Date date) {
        return date == null ? "未封禁" : AUDIT_TIME.format(toLocalDateTime(date));
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
