-- =====================================================
-- 评论异步审核可靠队列表
-- 解决【先展示后审核】窗口期：审核任务持久化到 DB，
-- 即使服务重启/崩溃，定时补偿任务也能重新拉起未审核评论，避免审核任务丢失。
-- 执行一次即可。
-- =====================================================
CREATE TABLE IF NOT EXISTS `ap_comment_audit_task` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `comment_id`     BIGINT       NOT NULL COMMENT '评论ID（唯一，一条评论仅一条审核任务）',
  `commenter_id`   INT          NOT NULL COMMENT '评论者用户ID',
  `commenter_name` VARCHAR(64)  DEFAULT '' COMMENT '评论者昵称',
  `content`        VARCHAR(2000) DEFAULT '' COMMENT '评论文本内容',
  `target_type`    TINYINT      NOT NULL DEFAULT 1 COMMENT '目标类型：1-文章, 2-沸点',
  `target_id`      BIGINT       NOT NULL COMMENT '目标内容ID（文章/沸点ID）',
  `target_user_id` INT          NULL COMMENT '目标内容作者ID（用于审核通过后发通知）',
  `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '任务状态：0-待审核, 1-审核中, 2-完成(通过), 3-完成(违规), 4-重试超限降级通过',
  `retry_count`    INT          NOT NULL DEFAULT 0 COMMENT '重试次数（处理异常时累计）',
  `next_retry_time` DATETIME    NULL COMMENT '下次执行时间（退避重试用）',
  `audit_time`     DATETIME     NULL COMMENT '审核完成时间',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_comment_id` (`comment_id`),
  KEY `idx_status_next` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论异步审核可靠队列';