-- =====================================================
-- 存量成就解锁回填脚本（一次性/可重复执行，幂等）
-- 依据历史行为数据统计各维度进度，写入 ap_user_achievement；
-- 达标（progress >= threshold）则 unlocked=1，不回填通知（避免存量用户被轰炸）。
-- 执行方式：mysql -uroot -p < backfill_ap_user_achievement.sql
-- 幂等保证：uk_user_code 唯一索引 + ON DUPLICATE KEY UPDATE，进度只增不减、已解锁不重复解锁。
-- =====================================================
USE leadnews_article;

-- 1. publish_article：作者已发布文章数
INSERT INTO ap_user_achievement (user_id, achievement_code, progress, threshold, unlocked, unlocked_at)
SELECT t.author_id, d.code, t.cnt, d.threshold,
       IF(t.cnt >= d.threshold, 1, 0),
       IF(t.cnt >= d.threshold, NOW(), NULL)
FROM ap_achievement d
JOIN (
    SELECT author_id, COUNT(*) AS cnt FROM ap_article WHERE is_deleted = 0 GROUP BY author_id
) t
WHERE d.trigger_type = 'publish_article'
ON DUPLICATE KEY UPDATE
  progress = GREATEST(ap_user_achievement.progress, VALUES(progress)),
  threshold = VALUES(threshold),
  unlocked = IF(ap_user_achievement.unlocked = 1, 1, VALUES(unlocked)),
  unlocked_at = IF(ap_user_achievement.unlocked = 1, ap_user_achievement.unlocked_at, VALUES(unlocked_at));

-- 2. publish_content：作者发布内容数（文章 + 沸点）
INSERT INTO ap_user_achievement (user_id, achievement_code, progress, threshold, unlocked, unlocked_at)
SELECT u.author_id, d.code, u.cnt, d.threshold,
       IF(u.cnt >= d.threshold, 1, 0),
       IF(u.cnt >= d.threshold, NOW(), NULL)
FROM ap_achievement d
JOIN (
    SELECT author_id, COUNT(*) AS cnt FROM (
        SELECT author_id FROM ap_article WHERE is_deleted = 0
        UNION ALL
        SELECT author_id FROM ap_pins WHERE is_deleted = 0
    ) x GROUP BY author_id
) u
WHERE d.trigger_type = 'publish_content'
ON DUPLICATE KEY UPDATE
  progress = GREATEST(ap_user_achievement.progress, VALUES(progress)),
  threshold = VALUES(threshold),
  unlocked = IF(ap_user_achievement.unlocked = 1, 1, VALUES(unlocked)),
  unlocked_at = IF(ap_user_achievement.unlocked = 1, ap_user_achievement.unlocked_at, VALUES(unlocked_at));

-- 3. followers：被关注者粉丝数
INSERT INTO ap_user_achievement (user_id, achievement_code, progress, threshold, unlocked, unlocked_at)
SELECT t.follow_user_id, d.code, t.cnt, d.threshold,
       IF(t.cnt >= d.threshold, 1, 0),
       IF(t.cnt >= d.threshold, NOW(), NULL)
FROM ap_achievement d
JOIN (
    SELECT follow_user_id, COUNT(*) AS cnt FROM ap_user_follow GROUP BY follow_user_id
) t
WHERE d.trigger_type = 'followers'
ON DUPLICATE KEY UPDATE
  progress = GREATEST(ap_user_achievement.progress, VALUES(progress)),
  threshold = VALUES(threshold),
  unlocked = IF(ap_user_achievement.unlocked = 1, 1, VALUES(unlocked)),
  unlocked_at = IF(ap_user_achievement.unlocked = 1, ap_user_achievement.unlocked_at, VALUES(unlocked_at));

-- 4. likes：作者文章累计获赞数
INSERT INTO ap_user_achievement (user_id, achievement_code, progress, threshold, unlocked, unlocked_at)
SELECT l.author_id, d.code, l.cnt, d.threshold,
       IF(l.cnt >= d.threshold, 1, 0),
       IF(l.cnt >= d.threshold, NOW(), NULL)
FROM ap_achievement d
JOIN (
    SELECT a.author_id, COUNT(*) AS cnt
    FROM ap_behavior_likes bl
    JOIN ap_article a ON a.id = bl.entry_id AND a.is_deleted = 0
    WHERE bl.type = 0 AND bl.operation = 0
    GROUP BY a.author_id
) l
WHERE d.trigger_type = 'likes'
ON DUPLICATE KEY UPDATE
  progress = GREATEST(ap_user_achievement.progress, VALUES(progress)),
  threshold = VALUES(threshold),
  unlocked = IF(ap_user_achievement.unlocked = 1, 1, VALUES(unlocked)),
  unlocked_at = IF(ap_user_achievement.unlocked = 1, ap_user_achievement.unlocked_at, VALUES(unlocked_at));

-- 5. checkin_streak：当前连续签到天数（跨库读取 reward 服务签到状态）
INSERT INTO ap_user_achievement (user_id, achievement_code, progress, threshold, unlocked, unlocked_at)
SELECT t.user_id, d.code, t.continuous_days, d.threshold,
       IF(t.continuous_days >= d.threshold, 1, 0),
       IF(t.continuous_days >= d.threshold, NOW(), NULL)
FROM ap_achievement d
JOIN leadnews_reward.user_checkin_state t ON t.continuous_days > 0
WHERE d.trigger_type = 'checkin_streak'
ON DUPLICATE KEY UPDATE
  progress = GREATEST(ap_user_achievement.progress, VALUES(progress)),
  threshold = VALUES(threshold),
  unlocked = IF(ap_user_achievement.unlocked = 1, 1, VALUES(unlocked)),
  unlocked_at = IF(ap_user_achievement.unlocked = 1, ap_user_achievement.unlocked_at, VALUES(unlocked_at));
