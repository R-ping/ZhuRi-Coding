package com.zhuri.coding.content.service.article.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.content.service.article.ArticleUpdateNotifyService;
import com.zhuri.coding.content.service.outbox.OutboxDispatcher;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessage;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 收藏者更新提醒投递实现（F5）。
 *
 * <p>三件事按顺序做：<b>分批取收藏者</b>（按收藏时间由近及远，避免大收藏量文章一次性载入）→
 * <b>逐人做 7 天去重</b>（同一文章对同一用户 7 天内只提醒一次）→ <b>按「用户 × 自然日」聚合投递</b>
 * （同一用户当天收到多条更新只合并成一条，文案携带当日累计篇数）。</p>
 *
 * <p><b>失败语义</b>：单个用户投递失败会释放其去重占位并继续处理其他人，最后抛异常让 Outbox 重试整条事件 ——
 * 已成功的人被去重占位挡住不会重复投递，失败的人交回下一次重试。文章不存在/非已发布属永久失败，直接抛
 * {@link OutboxDispatcher.DeadSignal} 判死，不浪费重试预算。</p>
 *
 * <p><b>降级口径（均为 fail-open，宁可多发不可漏发）</b>：Redis 不可用 → 不做去重与当日计数（退化为逐条提醒）；
 * 用户服务不可用 → 跳过注销过滤；通知服务不可用 → 计入失败并触发重试，绝不静默丢弃。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleUpdateNotifyServiceImpl implements ArticleUpdateNotifyService {

    /** 用户有效性批量校验的单次上限，与用户服务 {@code UserFeignController.MAX_BATCH} 保持一致 */
    private static final int USER_VALIDATE_CHUNK = 200;

    /** 去重键前缀：{@code notif:collect-update:{userId}:{articleId}} */
    private static final String DEDUP_KEY_PREFIX = "notif:collect-update:";
    /** 当日篇数计数键前缀：{@code notif:collect-update:daily:{userId}:{yyyy-MM-dd}} */
    private static final String DAILY_KEY_PREFIX = "notif:collect-update:daily:";

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 更新说明缺失时的默认文案（PRD 规则约束） */
    private static final String DEFAULT_NOTE = "这篇文章有内容更新";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ApArticleMapper apArticleMapper;
    private final ApCollectionMapper apCollectionMapper;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Autowired(required = false)
    private IUserClient userClient;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 总开关：关闭后所有投递直接返回（事件照常登记与重放，但静默跳过、不重试） */
    @Value("${app.article.update-notify.enabled:true}")
    private boolean enabled;

    /** 同一文章对同一用户的提醒间隔（天） */
    @Value("${app.article.update-notify.dedup-days:7}")
    private int dedupDays;

    /** 收藏者分批拉取/投递的批大小（大收藏量文章由近及远分批，避免一次性载入） */
    @Value("${app.article.update-notify.batch-size:200}")
    private int batchSize;

    @Override
    @LocalMessage(eventType = "ARTICLE_UPDATE_NOTIFY", key = "'article_update_notify:' + #a0 + ':' + #a1")
    public void notifyCollectors(Long articleId, Long updateTimeMillis) {
        if (!enabled || articleId == null) {
            return;
        }
        ApArticle article = apArticleMapper.selectById(articleId);
        if (article == null || article.getStatus() == null
                || article.getStatus().byteValue() != ApArticle.Status.PUBLISHED.getCode()) {
            // 永久失败：文章已删除/未发布，重试多少次都不会变，直接判死
            throw new OutboxDispatcher.DeadSignal("更新提醒目标文章不可用, articleId=" + articleId);
        }
        if (article.getUpdateTime() == null) {
            // 兜底：没有实质更新时间说明不是实质更新（或数据被回滚），没有可告知读者的事实
            log.info("[UpdateNotify] 文章无实质更新时间，跳过提醒, articleId={}", articleId);
            return;
        }

        String title = article.getTitle() != null ? article.getTitle() : "";
        String note = (article.getUpdateNote() != null && !article.getUpdateNote().isBlank())
                ? article.getUpdateNote().trim() : null;
        String dayKey = LocalDate.now().format(DAY_FMT);
        // 说明非空 → 直接落到可展开的说明框（:target 展开）；否则落到时效印章本身
        String link = "/content/article/" + articleId + (note != null ? "#updateNoteBox" : "#updateStamp");

        int pageSize = Math.max(1, batchSize);
        int offset = 0;
        long scanned = 0;
        long notified = 0;
        long cooled = 0;
        long failed = 0;

        while (true) {
            List<Long> collectors = apCollectionMapper.selectUserIdsByArticleId(articleId, offset, pageSize);
            if (collectors == null || collectors.isEmpty()) {
                break;
            }
            for (Long userId : filterValidUsers(collectors)) {
                if (userId == null || userId.equals(article.getAuthorId())) {
                    continue; // 作者无需被提醒"自己更新了自己的文章"
                }
                if (!claimDedup(userId, articleId)) {
                    cooled++;
                    continue;
                }
                long count = incrDailyCount(userId, dayKey);
                if (sendDigest(userId, dayKey, articleId, title, note, link, count)) {
                    notified++;
                } else {
                    // 投递失败：释放占位并计数，最后抛异常交给 Outbox 重试（已成功者不会重复）
                    releaseDedup(userId, articleId);
                    failed++;
                }
            }
            scanned += collectors.size();
            if (collectors.size() < pageSize) {
                break;
            }
            offset += collectors.size();
        }

        log.info("[UpdateNotify] 更新提醒投递完成, articleId={}, 收藏者={}, 新提醒={}, 冷却跳过={}, 失败={}",
                articleId, scanned, notified, cooled, failed);
        if (failed > 0) {
            throw new IllegalStateException("更新提醒部分投递失败, articleId=" + articleId + ", failed=" + failed);
        }
    }

    /**
     * 过滤已注销/锁定用户（ap_user.status=0）。
     *
     * <p>分片调用（用户服务单次上限 200），任一分片失败则<b>该分片整体放行</b>：
     * 给注销账号多写一条无人可见的站内信，比给一批正常用户漏发代价小得多。</p>
     */
    private List<Long> filterValidUsers(List<Long> userIds) {
        if (userClient == null || userIds.isEmpty()) {
            return userIds;
        }
        List<Long> valid = new ArrayList<>(userIds.size());
        for (int i = 0; i < userIds.size(); i += USER_VALIDATE_CHUNK) {
            List<Long> chunk = userIds.subList(i, Math.min(i + USER_VALIDATE_CHUNK, userIds.size()));
            try {
                ResponseResult result = userClient.getValidUserIds(chunk);
                if (result != null && result.getCode() == 200 && result.getData() instanceof List) {
                    for (Object id : (List<?>) result.getData()) {
                        if (id != null) {
                            valid.add(Long.valueOf(id.toString()));
                        }
                    }
                } else {
                    valid.addAll(chunk);
                }
            } catch (Exception e) {
                log.warn("[UpdateNotify] 用户有效性校验失败，本批放行, size={}", chunk.size(), e);
                valid.addAll(chunk);
            }
        }
        return valid;
    }

    /**
     * 抢占 7 天去重占位：返回 true 表示本次获得提醒资格。
     * <p>Redis 未装配或异常时 fail-open（返回 true，不做去重）。</p>
     */
    private boolean claimDedup(Long userId, Long articleId) {
        if (redisTemplate == null) {
            return true;
        }
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(
                    DEDUP_KEY_PREFIX + userId + ":" + articleId, "1", dedupDays, TimeUnit.DAYS);
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.warn("[UpdateNotify] 去重占位失败，按可提醒处理, userId={}, articleId={}", userId, articleId, e);
            return true;
        }
    }

    /** 释放去重占位（投递失败时调用，让重试能重新覆盖该用户） */
    private void releaseDedup(Long userId, Long articleId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(DEDUP_KEY_PREFIX + userId + ":" + articleId);
        } catch (Exception e) {
            log.debug("[UpdateNotify] 释放去重占位失败, userId={}, articleId={}", userId, articleId, e);
        }
    }

    /**
     * 累计该用户今日收到的更新提醒篇数，用于"你收藏的 N 篇文章有更新"的文案。
     * <p>Redis 不可用时退化为 1（按单篇文案发送）。</p>
     */
    private long incrDailyCount(Long userId, String dayKey) {
        if (redisTemplate == null) {
            return 1L;
        }
        try {
            String key = DAILY_KEY_PREFIX + userId + ":" + dayKey;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, 2, TimeUnit.DAYS);
            }
            return count != null && count > 0 ? count : 1L;
        } catch (Exception e) {
            log.warn("[UpdateNotify] 当日提醒计数失败，按单篇文案发送, userId={}", userId, e);
            return 1L;
        }
    }

    /**
     * 投递单条聚合提醒（同一用户同一天由通知服务合并进同一条站内信）。
     *
     * @return true=投递成功；false=失败（调用方负责释放占位并触发重试）
     */
    private boolean sendDigest(Long userId, String dayKey, Long articleId,
                               String title, String note, String link, long count) {
        if (notificationClient == null) {
            log.error("[UpdateNotify] INotificationClient 未装配，无法投递, userId={}", userId);
            return false;
        }
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("notification_type", "collect_update");
            content.put("article_id", String.valueOf(articleId));
            content.put("title", title);
            // 展示文案用 message 键：与既有系统通知（NotificationHelper）一致，前端列表直接读该字段渲染
            content.put("message", buildText(title, note, count));
            content.put("link", link);
            content.put("count", count);

            Map<String, Object> params = new HashMap<>();
            params.put("userId", userId);
            params.put("dayKey", dayKey);
            params.put("content", OBJECT_MAPPER.writeValueAsString(content));

            ResponseResult result = notificationClient.createCollectUpdateNotification(params);
            return result != null && result.getCode() == 200;
        } catch (Exception e) {
            log.warn("[UpdateNotify] 投递更新提醒失败, userId={}, articleId={}", userId, articleId, e);
            return false;
        }
    }

    /**
     * 提醒文案：单篇带文章标题与更新说明，多篇汇总为"你收藏的 N 篇文章有更新"。
     * 更新说明缺失时用默认文案（PRD 规则约束）。
     */
    private String buildText(String title, String note, long count) {
        String detail = note != null ? note : DEFAULT_NOTE;
        if (count <= 1) {
            return "你收藏的文章《" + title + "》有内容更新：" + detail;
        }
        return "你收藏的 " + count + " 篇文章有更新，最近一篇《" + title + "》：" + detail;
    }
}