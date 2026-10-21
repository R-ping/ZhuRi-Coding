package com.zhuri.coding.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.notification.dtos.AutoReplySettingDto;
import com.zhuri.coding.model.notification.pojos.ImMessage;
import com.zhuri.coding.model.notification.pojos.ImSession;
import com.zhuri.coding.model.notification.pojos.UserAutoReply;
import com.zhuri.coding.notification.mapper.ImMessageMapper;
import com.zhuri.coding.notification.mapper.ImSessionMapper;
import com.zhuri.coding.notification.mapper.UserAutoReplyMapper;
import com.zhuri.coding.notification.service.AutoReplyContentGuard;
import com.zhuri.coding.notification.service.AutoReplyService;
import com.zhuri.coding.notification.service.ImSessionSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 关注自动私信实现。
 *
 * <p><b>发送条件</b>（全部满足才发，任一不满足静默跳过）：开关开启 → 存在已过审的生效文案 →
 * 双向无拉黑 → 不在冷却窗口 → 未触发速率上限。
 *
 * <p><b>为什么跳过而不是报错</b>：这条消息由粉丝的关注动作间接触发，粉丝并不知道自己"应该"
 * 收到什么，报错只会制造无意义的失败日志；真正需要排查的人（运营）看跳过原因即可。
 */
@Slf4j
@Service
public class AutoReplyServiceImpl implements AutoReplyService {

    /** 默认文案：新用户未设置时使用 */
    public static final String DEFAULT_CONTENT = "谢谢关注";

    /** 消息来源枚举，与 im_messages.source_type 对应 */
    public static final String SOURCE_TYPE = "FOLLOW_AUTO_REPLY";

    /** 前端会话预览前缀：让粉丝知道这不是真人正在打字 */
    private static final String PREVIEW_PREFIX = "[自动回复] ";

    /** 会话预览长度：与普通私信保持一致 */
    private static final int PREVIEW_MAX = 50;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 自增并保证首次写入就带上过期时间。
     *
     * <p>用 Lua 而不是「INCR 再 EXPIRE」有两层原因：一是两次调用之间进程挂掉会留下永不过期的计数键；
     * 二是本项目的连接实现没有覆盖 {@code pExpire}，Java 侧调 {@code expire} 会落到默认方法上自递归，
     * 直接 StackOverflow。走脚本只依赖 EVAL，绕开这条路径。
     */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
        "local v = redis.call('INCR', KEYS[1]) "
            + "if v == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "return v", Long.class);

    /** 写入带过期时间的标记（冷却窗口） */
    private static final DefaultRedisScript<Long> SET_WITH_TTL = new DefaultRedisScript<>(
        "redis.call('SET', KEYS[1], '1', 'EX', ARGV[1]) return 1", Long.class);

    /** 跳过原因：枚举化，禁止自由文本，否则漏斗分析做不了 */
    public enum SkipReason {
        INVALID_PARAM, DUPLICATE, DISABLED, NO_CONTENT, BLOCKED, COOLDOWN, RATE_LIMITED
    }

    @Value("${app.auto-reply.default-enabled:true}")
    private boolean defaultEnabled = true;

    /** 同一对用户两次自动私信的最小间隔；设为很长即等价「终身只发一次」 */
    @Value("${app.auto-reply.cooldown-seconds:604800}")
    private long cooldownSeconds = 604800L;

    @Value("${app.auto-reply.rate-per-minute:60}")
    private int ratePerMinute = 60;

    @Value("${app.auto-reply.daily-limit:2000}")
    private int dailyLimit = 2000;

    @Autowired
    private UserAutoReplyMapper autoReplyMapper;

    @Autowired
    private ImSessionMapper imSessionMapper;

    @Autowired
    private ImMessageMapper imMessageMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AutoReplyContentGuard contentGuard;

    /** 拉黑关系在用户服务；不可用时按「无拉黑」降级（PRD 标注为并行依赖） */
    @Autowired(required = false)
    private IUserClient userClient;

    /** 只包住「落库」的短事务；单测上下文未装配时为 null，直接执行 */
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    // ==================== 配置读写 ====================

    @Override
    public ResponseResult getSetting(Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        UserAutoReply row = autoReplyMapper.selectById(userId);
        return ResponseResult.okResult(view(row, userId));
    }

    @Override
    public ResponseResult saveSetting(Long userId, AutoReplySettingDto dto) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (dto == null || (dto.getEnabled() == null && dto.getContent() == null)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "没有需要保存的内容");
        }

        UserAutoReply row = autoReplyMapper.selectById(userId);
        boolean isNew = row == null;
        LocalDateTime now = LocalDateTime.now();
        if (isNew) {
            row = new UserAutoReply();
            row.setUserId(userId);
            row.setEnabled(defaultEnabled ? 1 : 0);
            row.setContent(DEFAULT_CONTENT);
            row.setContentVersion(1);
            row.setAuditStatus(1);
            row.setEffectiveContent(DEFAULT_CONTENT);
            row.setCreatedAt(now);
        }

        if (dto.getEnabled() != null) {
            row.setEnabled(dto.getEnabled() ? 1 : 0);
        }

        if (dto.getContent() != null) {
            String content = dto.getContent().strip();
            AutoReplyContentGuard.Result result = contentGuard.check(content);
            // 格式问题直接拒绝保存；内容问题保存但驳回，继续使用上一版生效文案
            if (!result.passed() && result.blocking()) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, result.reason());
            }
            // 内容没变就不重复消耗审核与版本号：用户点两次保存不该产生两个版本
            if (!content.equals(row.getContent())) {
                row.setContent(content);
                row.setContentVersion((row.getContentVersion() == null ? 0 : row.getContentVersion()) + 1);
                if (result.passed()) {
                    row.setAuditStatus(1);
                    row.setAuditReason("");
                    row.setEffectiveContent(content);
                } else {
                    row.setAuditStatus(2);
                    row.setAuditReason(result.reason());
                    // effectiveContent 保持上一版，功能不停摆
                }
            }
        }

        row.setUpdatedAt(now);
        if (isNew) {
            try {
                autoReplyMapper.insert(row);
            } catch (DuplicateKeyException e) {
                // 并发首次保存：另一个请求已插入，转为更新，避免整个请求失败
                autoReplyMapper.updateById(row);
            }
        } else {
            autoReplyMapper.updateById(row);
        }
        return ResponseResult.okResult(view(row, userId));
    }

    /** 统一的对外视图：未建记录的用户按默认配置返回，前端无需区分「有没有配置过」 */
    private Map<String, Object> view(UserAutoReply row, Long userId) {
        Map<String, Object> data = new HashMap<>();
        if (row == null) {
            data.put("user_id", userId);
            data.put("enabled", defaultEnabled);
            data.put("content", DEFAULT_CONTENT);
            data.put("content_version", 0);
            data.put("audit_status", 1);
            data.put("audit_reason", null);
            data.put("effective_content", DEFAULT_CONTENT);
            data.put("default_content", DEFAULT_CONTENT);
            data.put("can_send", defaultEnabled);
            return data;
        }
        boolean enabled = Integer.valueOf(1).equals(row.getEnabled());
        String effective = row.getEffectiveContent();
        data.put("user_id", row.getUserId());
        data.put("enabled", enabled);
        data.put("content", row.getContent());
        data.put("content_version", row.getContentVersion());
        data.put("audit_status", row.getAuditStatus());
        data.put("audit_reason", row.getAuditReason() == null || row.getAuditReason().isBlank()
            ? null : row.getAuditReason());
        data.put("effective_content", effective);
        data.put("default_content", DEFAULT_CONTENT);
        data.put("can_send", enabled && effective != null && !effective.isBlank());
        return data;
    }

    // ==================== 触发投递 ====================

    @Override
    public void sendFollowAutoReply(Long followId, Long followerId, Long followeeId) {
        if (followId == null || followerId == null || followeeId == null || followerId.equals(followeeId)) {
            skip(SkipReason.INVALID_PARAM, followId, followerId, followeeId);
            return;
        }
        // 幂等快路径：同一次关注已发过就直接返回。数据库唯一索引是最终兜底，这里只是省一次写入
        if (imMessageMapper.selectBySource(SOURCE_TYPE, followId) != null) {
            skip(SkipReason.DUPLICATE, followId, followerId, followeeId);
            return;
        }

        UserAutoReply config = autoReplyMapper.selectById(followeeId);
        boolean enabled = config == null ? defaultEnabled : Integer.valueOf(1).equals(config.getEnabled());
        if (!enabled) {
            skip(SkipReason.DISABLED, followId, followerId, followeeId);
            return;
        }
        // 只用「已过审的生效文案」：正在审核或已驳回的编辑稿不允许以用户身份外发
        String content = config == null ? DEFAULT_CONTENT : config.getEffectiveContent();
        if (content == null || content.isBlank()) {
            skip(SkipReason.NO_CONTENT, followId, followerId, followeeId);
            return;
        }

        if (isBlocked(followerId, followeeId)) {
            skip(SkipReason.BLOCKED, followId, followerId, followeeId);
            return;
        }
        if (isCoolingDown(followeeId, followerId)) {
            skip(SkipReason.COOLDOWN, followId, followerId, followeeId);
            return;
        }
        if (!allowRate(followeeId)) {
            skip(SkipReason.RATE_LIMITED, followId, followerId, followeeId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        ImMessage message = new ImMessage();
        message.setSenderId(followeeId);
        message.setReceiverId(followerId);
        message.setContent(content);
        message.setMsgType(1);
        message.setSenderType(2);
        message.setSourceType(SOURCE_TYPE);
        message.setSourceId(followId);
        message.setStatus(0);
        message.setIsDeletedForSender(0);
        message.setIsDeletedForReceiver(0);
        message.setCreatedAt(now);
        try {
            // 事务只包「建会话 + 写消息 + 更新会话」三条 SQL：
            // 前面的拉黑查询（Feign）与冷却/限频（Redis）都是远程 IO，放进事务会白占数据库连接
            runInTransaction(() -> {
                ImSession session = ImSessionSupport.getOrInsert(imSessionMapper, followerId, followeeId);
                message.setSessionId(session.getId());
                imMessageMapper.insert(message);
                imSessionMapper.applyIncomingMessage(session.getId(), followerId, preview(content), now);
            });
        } catch (DuplicateKeyException e) {
            // 并发/重放：另一条投递已经落库，按已发送处理，不重复递增未读
            skip(SkipReason.DUPLICATE, followId, followerId, followeeId);
            return;
        }

        markCooldown(followeeId, followerId);
        touchLastSent(followeeId, now);
        log.info("[AutoReply] sent: followId={}, follower={}, followee={}", followId, followerId, followeeId);
    }

    private String preview(String content) {
        String flat = content.replaceAll("\\s+", " ").trim();
        String body = flat.length() <= PREVIEW_MAX ? flat : flat.substring(0, PREVIEW_MAX) + "...";
        return PREVIEW_PREFIX + body;
    }

    /** 短事务执行：未装配事务管理器（单测）时直接执行，保持原有语义 */
    private void runInTransaction(Runnable action) {
        if (transactionManager == null) {
            action.run();
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private void touchLastSent(Long followeeId, LocalDateTime now) {
        // 只更新这一列：不能 updateById 整行回写，否则并发下会把用户刚编辑的文案覆盖回旧值
        autoReplyMapper.update(null, new UpdateWrapper<UserAutoReply>()
            .eq("user_id", followeeId)
            .set("last_sent_at", now));
    }

    // ==================== 拦截条件 ====================

    private boolean isBlocked(Long followerId, Long followeeId) {
        if (userClient == null) {
            return false;
        }
        try {
            ResponseResult res = userClient.isBlocked(followerId, followeeId);
            if (res != null && res.getCode() == 200 && res.getData() instanceof Map<?, ?> data) {
                Object v = data.get("blocked");
                return v != null && Boolean.parseBoolean(String.valueOf(v));
            }
        } catch (Exception e) {
            // 拉黑查询失败按「未拉黑」继续：漏拦一次拉黑，比让所有关注都收不到回应更容易接受
            log.warn("[AutoReply] 拉黑关系查询失败, follower={}, followee={}", followerId, followeeId, e);
        }
        return false;
    }

    /**
     * 冷却窗口：同一对用户在该窗口内只发一条。
     * <p>它拦的是「取关又重关」——每次重关都会生成新的关注记录、幂等键不同，
     * 只有按人维度再拦一层，才能避免用户被反复打扰。窗口调到极大即「终身只发一次」。
     */
    private boolean isCoolingDown(Long followeeId, Long followerId) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(cooldownKey(followeeId, followerId)));
        } catch (Exception e) {
            log.warn("[AutoReply] 冷却查询失败，按未冷却处理", e);
            return false;
        }
    }

    private void markCooldown(Long followeeId, Long followerId) {
        try {
            stringRedisTemplate.execute(SET_WITH_TTL, List.of(cooldownKey(followeeId, followerId)),
                String.valueOf(cooldownSeconds));
        } catch (Exception e) {
            log.warn("[AutoReply] 冷却标记写入失败", e);
        }
    }

    private String cooldownKey(Long followeeId, Long followerId) {
        return "im:ar:cd:" + followeeId + ":" + followerId;
    }

    /**
     * 速率限制：先按分钟，再按自然日。
     *
     * <p>大 V 一次热门内容可能带来数万新增关注，逐条写库会把私信表打满；
     * 分钟级限速让写入平滑，日级上限兜住极端情况。
     */
    private boolean allowRate(Long followeeId) {
        try {
            String minuteKey = "im:ar:r:" + followeeId + ":" + (System.currentTimeMillis() / 60000);
            Long minuteCount = stringRedisTemplate.execute(INCR_WITH_TTL, List.of(minuteKey), "120");
            if (minuteCount != null && minuteCount > ratePerMinute) {
                return false;
            }

            String dayKey = "im:ar:d:" + followeeId + ":" + LocalDate.now().format(DAY);
            Long dayCount = stringRedisTemplate.execute(INCR_WITH_TTL, List.of(dayKey), "172800");
            return dayCount == null || dayCount <= dailyLimit;
        } catch (Exception e) {
            // 限流不可用时放行：宁可多写几条，也不要因为 Redis 抖动让功能整体失效
            log.warn("[AutoReply] 速率校验失败，按放行处理", e);
            return true;
        }
    }

    private void skip(SkipReason reason, Long followId, Long followerId, Long followeeId) {
        log.info("[AutoReply] skip: reason={}, followId={}, follower={}, followee={}",
            reason, followId, followerId, followeeId);
    }
}
