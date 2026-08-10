CREATE TABLE IF NOT EXISTS `ap_article_comment` (
  `id` bigint(20) NOT NULL COMMENT '主键ID',
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `article_id` bigint(20) NOT NULL COMMENT '文章ID',
  `content` text NOT NULL COMMENT '评论内容',
  `parent_id` bigint(20) DEFAULT NULL COMMENT '父评论ID',
  `root_id` bigint(20) DEFAULT NULL COMMENT '根评论ID',
  `digg_count` int(11) DEFAULT 0 COMMENT '点赞数',
  `reply_count` int(11) DEFAULT 0 COMMENT '回复数',
  `status` int(11) DEFAULT 0 COMMENT '状态 0-正常 1-隐藏',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at` datetime DEFAULT NULL COMMENT '删除时间（软删除）',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_article_id` (`article_id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE,
  KEY `idx_root_id` (`root_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章评论表';