-- ============================================================
-- 新增文章举报记录表 ap_article_report
-- 用途：详情页举报功能，举报记录落库供运营处理
-- ============================================================
CREATE TABLE IF NOT EXISTS `ap_article_report` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` int unsigned DEFAULT NULL COMMENT '举报人ID',
  `article_id` bigint unsigned DEFAULT NULL COMMENT '被举报文章ID',
  `author_id` bigint unsigned DEFAULT NULL COMMENT '被举报文章作者ID',
  `reason` varchar(100) DEFAULT NULL COMMENT '举报原因',
  `description` varchar(200) DEFAULT NULL COMMENT '补充说明（≤100字）',
  `image_urls` varchar(2000) DEFAULT NULL COMMENT '举报图片URL（逗号分隔，最多4张）',
  `status` tinyint DEFAULT '0' COMMENT '处理状态：0待处理 1已处理',
  `created_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_article_id` (`article_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章举报记录表';
