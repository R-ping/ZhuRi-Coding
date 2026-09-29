-- =============================================================
-- 通知聚合：同一 (user_id, type, source_id) 永远只占一行
-- 数据库：leadnews_notification
--
-- 背景：一个赞一行通知。爆款文章 1 万个赞 = 1 万行 INSERT + 1 万次未读
--       缓存增量，列表里还全是长得一样的行。
-- 方案：可聚合的通知用唯一键合并成一行，新事件更新 agg_count /
--       last_event_at 并把 is_read 翻回 0（"最新的也冒出来"）。
--
-- 为什么用 agg_key 而不是直接拿 (user_id, type, source_id) 建唯一键：
--   评论(type=1) 与系统通知(type=4) 不该聚合——用户在意"说了什么"。
--   agg_key 为 NULL 的行不受唯一键约束（MySQL 唯一索引允许多个 NULL），
--   所以"不聚合"这个语义可以直接编码进列里，不用在索引上做条件。
--   与 im_messages.client_id 用的是同一个手法。
-- =============================================================

-- 1. 新增列
ALTER TABLE `notifications`
  ADD COLUMN `agg_key`       VARCHAR(128) NULL COMMENT '聚合键（type:sourceId）；为空表示不聚合，一行一事件'
      AFTER `source_id`,
  ADD COLUMN `agg_count`     INT      NOT NULL DEFAULT 1 COMMENT '本行合并的事件数；不聚合恒为 1'
      AFTER `content`,
  ADD COLUMN `last_event_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后一次事件时间；列表排序与游标用它，使被顶起来的行能浮到顶部'
      AFTER `agg_count`;

-- 2. 存量回填：ADD COLUMN 时所有行都被填成了 CURRENT_TIMESTAMP（执行迁移的那一瞬间），
--    这里必须与 created_at 对齐，否则历史通知会集体挤在迁移那一刻，排序全乱。
--    注意不要加 `WHERE last_event_at IS NULL` —— 该列 NOT NULL 有默认值，永远不为空。
UPDATE `notifications` SET `last_event_at` = `created_at`;

-- 3. 存量去重。表里已经存在同一 (user_id, type, source_id) 的多行，
--    直接建唯一键会失败。保留 id 最大的那一条（内容最新），
--    把被合并掉的行数累加进它的 agg_count。
DROP TEMPORARY TABLE IF EXISTS `_agg_dup`;
CREATE TEMPORARY TABLE `_agg_dup` AS
SELECT `user_id`, `type`, `source_id`,
       MAX(`id`)          AS `keep_id`,
       COUNT(*)           AS `c`,
       MAX(`created_at`)  AS `last_at`
FROM `notifications`
WHERE `type` IN (2, 3) AND `source_id` IS NOT NULL
GROUP BY `user_id`, `type`, `source_id`
HAVING COUNT(*) > 1;

UPDATE `notifications` n
JOIN `_agg_dup` g
  ON  n.`user_id`   = g.`user_id`
  AND n.`type`      = g.`type`
  AND n.`source_id` = g.`source_id`
  AND n.`id`        = g.`keep_id`
SET n.`agg_key`       = CONCAT(n.`type`, ':', n.`source_id`),
    n.`agg_count`     = g.`c`,
    n.`last_event_at` = g.`last_at`;

DELETE n FROM `notifications` n
JOIN `_agg_dup` g
  ON  n.`user_id`   = g.`user_id`
  AND n.`type`      = g.`type`
  AND n.`source_id` = g.`source_id`
WHERE n.`id` <> g.`keep_id`;

-- 4. 单条（未重复）的可聚合通知也要有 agg_key，否则下次写入会插新行
UPDATE `notifications`
SET `agg_key` = CONCAT(`type`, ':', `source_id`)
WHERE `type` IN (2, 3) AND `source_id` IS NOT NULL AND `agg_key` IS NULL;

-- 5. 唯一键：同一用户对同一触发源、同一类型，只有一行
ALTER TABLE `notifications`
  ADD UNIQUE KEY `uk_agg` (`user_id`, `agg_key`);

-- 6. 列表查询索引。列表是 WHERE user_id AND type ORDER BY last_event_at DESC, id DESC，
--    原 idx_user_read_created 只覆盖 (user_id, is_read, created_at)，type 得回表过滤。
ALTER TABLE `notifications`
  ADD KEY `idx_user_type_last` (`user_id`, `type`, `last_event_at`, `id`);
