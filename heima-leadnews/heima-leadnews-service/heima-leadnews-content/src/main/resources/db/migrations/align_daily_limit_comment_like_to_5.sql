-- ============ 对齐"社区活跃"点赞/评论任务每日目标上限为 5 ============
-- 背景：界面任务展示目标（如"点赞一篇文章 0/5"）与行为每日分数上限不一致
--       （ap_behavior_config.daily_limit 原为 2，导致进度最多到 2/5、且 LevelScoreConstants 同步按 2 封顶）。
-- 处理：将点赞/评论类"社区活跃"任务的 daily_limit 统一为 5，与前端展示目标一致。
-- 说明：LevelScoreConstants.DAILY_ACTION_LIMIT 已对应更新为 5（comment_article/comment_pin/like_article/like_pin）。
USE `leadnews_article`;

UPDATE `ap_behavior_config`
SET `daily_limit` = 5
WHERE `action_code` IN ('comment_article', 'comment_pin', 'like_article', 'like_pin')
  AND `is_active` = 1;