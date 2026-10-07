package com.zhuri.coding.content.service.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;

/**
 * 弹窗关闭记录（Redis，best-effort 基建）。
 *
 * <p><b>结构：每条弹窗一个 Set（{@code popup:closed:{popupId}}，成员 = userId）</b>。
 * 不用「每用户每弹窗一个 String key」的原因是管理端会编辑弹窗的结束时间 ——
 * Set 方案下 TTL 是一条 EXPIRE 的事，String 方案得枚举所有已关闭用户（做不到）。
 * 判断 {@code SISMEMBER}、清理 {@code DEL}、调整 TTL {@code EXPIRE}，都是单 key 操作。
 *
 * <p><b>所有方法都不抛异常（有意为之）</b>：这个组件丢数据的最坏后果是
 * "用户已关过的弹窗再弹一次"，用户再点一次关闭即可 —— 与"封禁通知 best-effort"同一判据：
 * 会自愈的失败不值得让它炸上游。反之，Redis 故障不该把 C 端首页/弹窗接口一起拖垮
 * （fail-open：查不到就当未关闭）。代价是极端并发下（SADD 成功、EXPIRE 前进程挂）
 * 可能留下无 TTL 的孤儿 key —— 删除弹窗时的 {@link #purge} 会顺手清掉，
 * 残余影响只是一个空 key 占几个字节。
 *
 * <p>TTL 取「弹窗结束时间 - 现在」，下限 60 秒兜底（结束时间已过的弹窗，
 * 关闭记录再活一分钟，足够"到点前弹出来、用户点关"这个窗口期）。
 */
@Slf4j
@Component
public class PopupCloseStore {

    private static final String KEY_PREFIX = "popup:closed:";

    /** TTL 下限兜底：到期弹窗的关闭记录也至少活一分钟，避免向 Redis 传非正数 TTL 报错 */
    private static final long MIN_TTL_SECONDS = 60;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 记录"用户关闭了这条弹窗"，TTL 至弹窗结束时间 */
    public void markClosed(Long popupId, Long userId, Date endTime) {
        try {
            String key = KEY_PREFIX + popupId;
            redisTemplate.opsForSet().add(key, String.valueOf(userId));
            redisTemplate.expire(key, remaining(endTime));
        } catch (Exception e) {
            log.warn("[PopupClose] 关闭记录写入失败（按未关闭处理，弹窗可能重复出现）, popupId={}, userId={}",
                popupId, userId, e);
        }
    }

    /** 用户是否已关闭；Redis 异常按未关闭处理（fail-open，重弹无害） */
    public boolean isClosed(Long popupId, Long userId) {
        try {
            Boolean member = redisTemplate.opsForSet()
                .isMember(KEY_PREFIX + popupId, String.valueOf(userId));
            return Boolean.TRUE.equals(member);
        } catch (Exception e) {
            log.warn("[PopupClose] 关闭记录查询失败（按未关闭处理）, popupId={}, userId={}", popupId, userId, e);
            return false;
        }
    }

    /** 弹窗结束时间被编辑后刷新 TTL；key 不存在时 EXPIRE 无效，无副作用 */
    public void refreshTtl(Long popupId, Date endTime) {
        try {
            redisTemplate.expire(KEY_PREFIX + popupId, remaining(endTime));
        } catch (Exception e) {
            log.warn("[PopupClose] TTL 刷新失败（关闭记录将按原 TTL 自然过期）, popupId={}", popupId, e);
        }
    }

    /** 删除弹窗时清掉关闭记录（同时兜底清掉可能存在的无 TTL 孤儿 key） */
    public void purge(Long popupId) {
        try {
            redisTemplate.delete(KEY_PREFIX + popupId);
        } catch (Exception e) {
            log.warn("[PopupClose] 关闭记录清理失败（孤儿 key 占用极小，可忽略）, popupId={}", popupId, e);
        }
    }

    private static Duration remaining(Date endTime) {
        if (endTime == null) {
            return Duration.ofSeconds(MIN_TTL_SECONDS);
        }
        long ms = endTime.getTime() - System.currentTimeMillis();
        return Duration.ofMillis(Math.max(ms, MIN_TTL_SECONDS * 1000));
    }
}
