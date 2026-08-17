-- 作者基础信息表：供小册申请回填与作者页展示
-- 库：leadnews_article
CREATE TABLE IF NOT EXISTS `ap_author_profile` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        INT          NOT NULL COMMENT '用户ID',
  `real_name`      VARCHAR(64)  DEFAULT '' COMMENT '姓名',
  `position`       VARCHAR(128) DEFAULT '' COMMENT '个人职位/职业',
  `resume`         TEXT         DEFAULT NULL COMMENT '个人履历/简介',
  `apply_reason`   VARCHAR(500) DEFAULT '' COMMENT '申请理由',
  `contact_wechat` VARCHAR(64)  DEFAULT '' COMMENT '联系方式-微信',
  `contact_email`  VARCHAR(128) DEFAULT '' COMMENT '联系方式-常用邮箱',
  `blogs`          VARCHAR(500) DEFAULT '' COMMENT '掘金账号及其他博客/技术媒体',
  `personal_intro` VARCHAR(500) DEFAULT '' COMMENT '个人自我介绍（用于小册作者页展示）',
  `created_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作者基础信息（供小册申请回填与作者页展示）';