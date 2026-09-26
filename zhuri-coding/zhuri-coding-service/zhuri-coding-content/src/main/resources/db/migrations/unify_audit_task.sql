-- ============================================================================
-- 统一异步审核任务表：合并 ap_comment_audit_task / ap_pins_audit_task / ap_pins_comment_audit_task
--
-- 【为什么合并】
-- 三张表的字段、状态值、唯一键、索引完全一致，是同一份模板复制了三遍；
-- 配套的补偿扫描器有 3 个（另加 1 个文章审核扫描器），退避策略、批量大小、日志口径各不相同。
-- 合并后把「调度机制」收敛成一份实现：CAS 抢占 / 指数退避 / 超限降级放行 / 补偿扫描。
--
-- 【与本地消息表（ap_outbox_event）的区别 —— 别混为一谈】
-- 本表存的是「执行状态」（这件事做到哪一步了），不是「待投递的消息」；
-- 消费方是本服务内的审核 Handler，不存在跨系统投递的原子性问题。
-- 它不从属于 Outbox 框架，参考的是 Outbox 的可靠投递手法（同事务落库 + CAS + 退避 + 终态）。
--
-- 【字段映射（旧 -> 新）】
--   comment_id / pins_id              -> biz_id（配 biz_type 组成幂等键）
--   commenter_id / author_id          -> author_id
--   commenter_name / author_name      -> author_name
--   user_id（仅沸点表，等级积分用）    -> actor_user_id
--   content / image_urls              -> 同名（内容快照）
--   target_type/target_id/target_user_id（仅文章评论表） -> 同名
--
-- 【状态值刻意保持不变】0/1/2/3/4 —— 三个业务的判定逻辑无需改动：
--   0=待审核 1=审核中 2=完成(通过) 3=完成(违规) 4=重试超限降级通过
-- ============================================================================

CREATE TABLE IF NOT EXISTS `ap_audit_task` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_key`        VARCHAR(96)   NOT NULL COMMENT '幂等键 {bizType}:{bizId}，同一业务仅一条任务',
  `biz_type`        VARCHAR(32)   NOT NULL COMMENT '业务类型：article_comment / pins / pins_comment',
  `biz_id`          BIGINT        NOT NULL COMMENT '业务ID（评论ID / 沸点ID）',
  `actor_user_id`   INT           NULL     COMMENT '行为用户ID（审核通过后发等级积分用）',
  `author_id`       INT           NULL     COMMENT '内容作者ID',
  `author_name`     VARCHAR(64)   DEFAULT '' COMMENT '内容作者昵称',
  `content`         VARCHAR(2000) DEFAULT '' COMMENT '待审内容快照',
  `image_urls`      VARCHAR(2000) DEFAULT '' COMMENT '图片URL（逗号分隔）',
  `target_type`     TINYINT       NULL     COMMENT '目标内容类型：1-文章 2-沸点（审核通过后发通知用）',
  `target_id`       BIGINT        NULL     COMMENT '目标内容ID',
  `target_user_id`  INT           NULL     COMMENT '目标内容作者ID',
  `status`          TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待审核 1-审核中 2-完成(通过) 3-完成(违规) 4-重试超限降级通过',
  `retry_count`     INT           NOT NULL DEFAULT 0 COMMENT '重试次数（处理异常时累计）',
  `next_retry_time` DATETIME      NULL     COMMENT '下次执行时间（指数退避用）',
  `audit_time`      DATETIME      NULL     COMMENT '审核完成时间',
  `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_key` (`task_key`),
  KEY `idx_status_next` (`status`, `next_retry_time`),
  KEY `idx_biz` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一异步审核任务表（可靠队列）';

-- ============================================================================
-- 存量数据迁移（幂等：唯一键冲突时只刷新 update_time，不覆盖已有进度）
-- 注意：执行前请确认三张旧表的 AUTO_INCREMENT 与新表 id 无冲突（本表为独立自增，无冲突）。
-- ============================================================================

-- 1) 文章评论审核
INSERT INTO `ap_audit_task`
  (`task_key`, `biz_type`, `biz_id`, `author_id`, `author_name`, `content`, `image_urls`,
   `target_type`, `target_id`, `target_user_id`,
   `status`, `retry_count`, `next_retry_time`, `audit_time`, `create_time`, `update_time`)
SELECT CONCAT('article_comment:', `comment_id`), 'article_comment', `comment_id`,
       `commenter_id`, `commenter_name`, `content`, '',
       `target_type`, `target_id`, `target_user_id`,
       `status`, `retry_count`, `next_retry_time`, `audit_time`, `create_time`, `update_time`
FROM `ap_comment_audit_task`
ON DUPLICATE KEY UPDATE `update_time` = VALUES(`update_time`);

-- 2) 沸点审核（actor_user_id 取旧表的 user_id）
INSERT INTO `ap_audit_task`
  (`task_key`, `biz_type`, `biz_id`, `actor_user_id`, `author_id`, `author_name`, `content`, `image_urls`,
   `target_type`, `target_id`, `target_user_id`,
   `status`, `retry_count`, `next_retry_time`, `audit_time`, `create_time`, `update_time`)
SELECT CONCAT('pins:', `pins_id`), 'pins', `pins_id`,
       `user_id`, `author_id`, `author_name`, `content`, `image_urls`,
       NULL, NULL, NULL,
       `status`, `retry_count`, `next_retry_time`, `audit_time`, `create_time`, `update_time`
FROM `ap_pins_audit_task`
ON DUPLICATE KEY UPDATE `update_time` = VALUES(`update_time`);

-- 3) 沸点评论审核
INSERT INTO `ap_audit_task`
  (`task_key`, `biz_type`, `biz_id`, `author_id`, `author_name`, `content`, `image_urls`,
   `target_type`, `target_id`, `target_user_id`,
   `status`, `retry_count`, `next_retry_time`, `audit_time`, `create_time`, `update_time`)
SELECT CONCAT('pins_comment:', `comment_id`), 'pins_comment', `comment_id`,
       `commenter_id`, `commenter_name`, `content`, '',
       `target_type`, `target_id`, `target_user_id`,
       `status`, `retry_count`, `next_retry_time`, `audit_time`, `create_time`, `update_time`
FROM `ap_pins_comment_audit_task`
ON DUPLICATE KEY UPDATE `update_time` = VALUES(`update_time`);

-- 核对：迁移后行数应等于三张旧表之和（去重前）
-- SELECT biz_type, COUNT(*) FROM ap_audit_task GROUP BY biz_type;
--
-- ============================================================================
-- 进度（2026-09-26）
--   [x] 建表 ap_audit_task
--   [x] 存量数据迁移（本地库核对：article_comment 2 / pins 1 / pins_comment 0，与旧表一一对应）
--   [x] 业务代码切换：CommentAuditService / PinsReviewService / PinsCommentAuditService
--        及其扫描器全部改读本表；旧实体与旧 Mapper 已删除
--   [ ] 旧表下线（见下方 DROP，待业务验证后执行）
--   [ ] 重新导出 schema.sql（应在 DROP 之后，否则导出结果仍含旧表）
--
-- 数据回滚预案：旧表未被修改，随时可 DROP 本表并让代码回到旧提交；
--   若需回滚代码，旧实体与旧 Mapper 在 git HEAD 中仍可 `git checkout` 恢复。
-- ============================================================================

-- 旧表下线（确认业务代码已全部切换、并观察一段时间后再执行，不要提前跑）：
-- DROP TABLE `ap_comment_audit_task`;
-- DROP TABLE `ap_pins_audit_task`;
-- DROP TABLE `ap_pins_comment_audit_task`;
