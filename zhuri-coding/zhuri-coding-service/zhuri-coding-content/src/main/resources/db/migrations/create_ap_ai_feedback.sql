-- =====================================================
-- AI 反馈闭环（👍/👎）：让 Prompt/模型策略的迭代有数据依据
-- 幂等：同用户+功能+场景+问题只保留一次（question_hash 唯一，冲突时更新 feedback）
-- 内容库：leadnews_article，执行一次
-- =====================================================
CREATE TABLE IF NOT EXISTS `ap_ai_feedback` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`       INT          NOT NULL COMMENT '反馈用户ID',
  `feature`       VARCHAR(32)  NOT NULL COMMENT 'AI功能：aiask_global-社区问答 aiask_article-单篇问答 summary-摘要 precheck-发布预检',
  `scene_id`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '场景ID（如文章ID/空）',
  `question_hash` CHAR(32)     NOT NULL DEFAULT '' COMMENT '问题内容哈希(MD5) 用于幂等',
  `question`      VARCHAR(500) NOT NULL DEFAULT '' COMMENT '问题（截断）',
  `answer`        VARCHAR(500) NOT NULL DEFAULT '' COMMENT '回答（截断，留分析样本）',
  `feedback`      TINYINT      NOT NULL COMMENT '1-有帮助(👍) -1-没帮助/有误(👎)',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_feature_scene_q` (`user_id`,`feature`,`scene_id`,`question_hash`),
  KEY `idx_feature_fb` (`feature`,`feedback`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 反馈(点赞/点踩)';
