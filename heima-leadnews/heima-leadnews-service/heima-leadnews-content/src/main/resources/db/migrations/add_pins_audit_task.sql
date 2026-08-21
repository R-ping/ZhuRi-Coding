-- =====================================================
-- 沸点异步审核可靠队列表
-- 沸点为"先审后展"，审核任务持久化到 DB 后可被定时补偿任务重新拉起，
-- 避免服务重启/崩溃后审核任务丢失，导致沸点长期停留在"待审"状态不可见。
-- 执行一次即可。
-- =====================================================
CREATE TABLE IF NOT EXISTS `ap_pins_audit_task` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `pins_id`        BIGINT       NOT NULL COMMENT '沸点ID（唯一，一条沸点仅一条审核任务）',
  `author_id`      INT          NOT NULL COMMENT '作者/发布者用户ID',
  `author_name`    VARCHAR(64)  DEFAULT '' COMMENT '作者昵称',
  `user_id`        INT          NULL COMMENT '发布行为的用户ID（等级积分用，通常等于 author_id）',
  `content`        VARCHAR(2000) DEFAULT '' COMMENT '沸点正文',
  `image_urls`     VARCHAR(2000) DEFAULT '' COMMENT '沸点图片URL（逗号分隔）',
  `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '任务状态：0-待审核, 1-审核中, 2-完成(通过), 3-完成(违规), 4-重试超限降级通过',
  `retry_count`    INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `next_retry_time` DATETIME    NULL COMMENT '下次执行时间（退避重试用）',
  `audit_time`     DATETIME     NULL COMMENT '审核完成时间',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_pins_id` (`pins_id`),
  KEY `idx_status_next` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沸点异步审核可靠队列';