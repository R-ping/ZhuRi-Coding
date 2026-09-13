-- =====================================================
-- 内容治理申诉表（AI 预审 + 人工终审，让误伤可纠正）
-- 对象：1-评论折叠申诉（is_hidden） 2-文章 AIGC 误标申诉（is_aigc）
-- 流程：作者提交申诉 → AI 预审给建议(ai_verdict, 不终决) → 运营/人工终审 allow(解除)/uphold(维持)
-- 内容库：leadnews_article，执行一次
-- =====================================================
CREATE TABLE IF NOT EXISTS `ap_content_appeal` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `appeal_type`   TINYINT      NOT NULL COMMENT '1-评论折叠申诉 2-文章AIGC误标申诉',
  `content_id`    BIGINT       NOT NULL COMMENT '被申诉内容ID（评论ID/文章ID）',
  `applicant_id`  INT          NOT NULL COMMENT '申诉人用户ID（须为内容归属者）',
  `reason`        VARCHAR(500) NOT NULL DEFAULT '' COMMENT '申诉理由',
  `ai_verdict`    VARCHAR(1000) NOT NULL DEFAULT '' COMMENT 'AI预审JSON {suggest:allow|uphold, score, reason}（不终决）',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待人工终审 1-已解除(allow) 2-已驳回(uphold维持)',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_applicant` (`applicant_id`),
  KEY `idx_type_content` (`appeal_type`,`content_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容治理申诉(AI预审+人工终审)';
