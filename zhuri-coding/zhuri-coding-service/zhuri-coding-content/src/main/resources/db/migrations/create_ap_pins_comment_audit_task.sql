-- =====================================================
-- 沸点评论异步审核可靠队列表
-- 与 ap_comment_audit_task 同构但独立建表：两张源表（ap_comment / ap_pins_comment）
-- 的 id 各自 AUTO 自增会撞号，无法共用同一 comment_id 唯一键，故沸点独立一套。
-- 解决【先展示后审核】窗口期：服务重启/崩溃后由 PinsCommentAuditRecoveryTask 重新拉起。
-- 执行一次即可。
-- =====================================================
CREATE TABLE IF NOT EXISTS `ap_pins_comment_audit_task` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `comment_id`     BIGINT       NOT NULL COMMENT '沸点评论ID（唯一，一条评论仅一条审核任务）',
  `commenter_id`   INT          NOT NULL COMMENT '评论者用户ID',
  `commenter_name` VARCHAR(64)  DEFAULT '' COMMENT '评论者昵称',
  `content`        VARCHAR(2000) DEFAULT '' COMMENT '评论文本内容',
  `target_type`    TINYINT      NOT NULL DEFAULT 2 COMMENT '目标类型：固定 2-沸点',
  `target_id`      BIGINT       NOT NULL COMMENT '目标沸点ID（pins_id）',
  `target_user_id` INT          NULL COMMENT '目标沸点作者ID（沸点评论创建即通知，审核回调不使用）',
  `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '任务状态：0-待审核, 1-审核中, 2-完成(通过), 3-完成(违规), 4-重试超限降级通过',
  `retry_count`    INT          NOT NULL DEFAULT 0 COMMENT '重试次数（处理异常时累计）',
  `next_retry_time` DATETIME    NULL COMMENT '下次执行时间（退避重试用）',
  `audit_time`     DATETIME     NULL COMMENT '审核完成时间',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_comment_id` (`comment_id`),
  KEY `idx_status_next` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沸点评论异步审核可靠队列';
