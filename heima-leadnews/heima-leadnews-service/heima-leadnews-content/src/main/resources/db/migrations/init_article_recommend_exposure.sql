-- ======================================================================
-- 文章推荐曝光记录表（数据回流闭环：曝光 → 行为）
-- 数据库：leadnews_article
-- 用途：
--   1. 推荐服务下发「推荐分栏」结果时，记录每一页的曝光明细（用户 × 文章 × 位次）。
--   2. 评分阶段对「近期已曝光给该用户、但从未被消费（阅读/点击）」的文章做降权，
--      形成负反馈闭环：反复曝光而无行为 → 下调排序，减少“刷到不感兴趣”的体验损耗。
-- ======================================================================
CREATE TABLE IF NOT EXISTS `ap_article_exposure` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT          NOT NULL                COMMENT '用户ID（匿名曝光统一记 0）',
  `article_id`  BIGINT          NOT NULL                COMMENT '文章ID',
  `channel`     VARCHAR(32)     NOT NULL DEFAULT '__all__' COMMENT '推荐渠道：__all__/具体频道ID/follow',
  `sub_tab`     VARCHAR(16)     NOT NULL DEFAULT 'recommend' COMMENT '分栏：recommend/latest',
  `page`        INT             NOT NULL DEFAULT 0      COMMENT '页码（从 0 起）',
  `position`    INT             NOT NULL DEFAULT 0      COMMENT '该页内位次（从 0 起）',
  `seed`        BIGINT          NOT NULL DEFAULT 0      COMMENT '会话种子（刷新/分页锚点）',
  `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '曝光时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_article` (`user_id`, `article_id`, `create_time`),
  KEY `idx_article_time` (`article_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章推荐曝光记录（数据回流闭环）';