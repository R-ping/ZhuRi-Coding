-- =====================================================
-- Step4 内容诚信治理（AIGC 水文检测）
-- 三张内容表加检测标记列 + 检测明细表 ap_aigc_record
-- 内容库：leadnews_article，执行一次即可
-- 处置语义：只标不删——flagged 关闭打赏/不入向量库/课程禁售，作者可申诉
-- =====================================================

ALTER TABLE ap_article
  ADD COLUMN is_aigc TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常 1-疑似AI水文(内容诚信治理)',
  ADD COLUMN aigc_score TINYINT NOT NULL DEFAULT 0 COMMENT 'AI水文疑似分 0-100(越高越疑似)',
  ADD COLUMN aigc_checked_at DATETIME NULL COMMENT 'AIGC检测时间';

ALTER TABLE ap_pins
  ADD COLUMN is_aigc TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常 1-疑似AI水文(内容诚信治理)',
  ADD COLUMN aigc_score TINYINT NOT NULL DEFAULT 0 COMMENT 'AI水文疑似分 0-100';

ALTER TABLE ap_course_chapter
  ADD COLUMN is_aigc TINYINT NOT NULL DEFAULT 0 COMMENT '0-正常 1-疑似AI水文(禁止售卖)',
  ADD COLUMN aigc_score TINYINT NOT NULL DEFAULT 0 COMMENT 'AI水文疑似分 0-100';

-- 检测明细（信号可审计、支撑阈值调优与申诉），主键遵循 INT AUTO 规范
CREATE TABLE IF NOT EXISTS `ap_aigc_record` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `content_type`  TINYINT      NOT NULL COMMENT '1-文章 2-沸点 3-课程小节',
  `content_id`    BIGINT       NOT NULL COMMENT '内容ID',
  `author_id`     INT          NOT NULL COMMENT '作者用户ID',
  `score`         TINYINT      NOT NULL COMMENT '综合疑似分 0-100',
  `signals_json`  VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '信号明细 JSON(burst/repeat/template/anchor/author_cos/llm_verdict)',
  `method`        VARCHAR(32)  NOT NULL DEFAULT 'stat_v1' COMMENT '检测版本/方法',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-仅记录 1-flagged 2-申诉中 3-人工复核放行 4-人工确认水文',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_type_content` (`content_type`,`content_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AIGC 水文检测记录(内容诚信治理)';
