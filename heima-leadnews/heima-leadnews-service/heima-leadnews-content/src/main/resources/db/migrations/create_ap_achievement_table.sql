-- =====================================================
-- 成就勋章定义表迁移脚本
-- ap_achievement：勋章定义表 + 11 枚静态勋章种子数据
-- 2 枚等级徽章（逐友/逐力值）不落库，由服务动态构造
-- =====================================================

CREATE TABLE `ap_achievement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code` varchar(64) NOT NULL COMMENT '勋章唯一编码',
  `name` varchar(64) NOT NULL COMMENT '勋章名称',
  `category` tinyint NOT NULL DEFAULT '2' COMMENT '分类：1=新人成长，2=活跃成就',
  `icon` varchar(16) NOT NULL DEFAULT '' COMMENT '图标（emoji字符）',
  `description` varchar(200) NOT NULL DEFAULT '' COMMENT '解锁条件文案',
  `trigger_type` varchar(32) NOT NULL COMMENT '触发类型：publish_article/publish_content/checkin_streak/likes/followers',
  `threshold` int NOT NULL DEFAULT '0' COMMENT '解锁阈值',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示排序',
  `is_active` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：1=启用 0=禁用',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成就勋章定义表';

-- 种子数据：新人成长类（2枚）
INSERT INTO `ap_achievement` (`code`,`name`,`category`,`icon`,`description`,`trigger_type`,`threshold`,`sort_order`) VALUES
('first_content','初来乍到',1,'🚀','首次发布文章或沸点','publish_content',1,1),
('checkin_streak_30','连续签到30天',1,'📅','连续签到满30天','checkin_streak',30,2);

-- 种子数据：活跃成就类（9枚）
INSERT INTO `ap_achievement` (`code`,`name`,`category`,`icon`,`description`,`trigger_type`,`threshold`,`sort_order`) VALUES
('publish_10','笔耕不辍',2,'✍️','累计发布文章10篇','publish_article',10,3),
('publish_50','创作达人',2,'📚','累计发布文章50篇','publish_article',50,4),
('publish_100','大神作家',2,'👑','累计发布文章100篇','publish_article',100,5),
('likes_100','初获认可',2,'👍','文章累计获赞100','likes',100,6),
('likes_1000','广受好评',2,'🌟','文章累计获赞1000','likes',1000,7),
('likes_10000','万人追捧',2,'🔥','文章累计获赞10000','likes',10000,8),
('followers_100','小有名气',2,'💡','粉丝数达到100','followers',100,9),
('followers_500','人气爆棚',2,'🎉','粉丝数达到500','followers',500,10),
('followers_1000','顶流作家',2,'🏆','粉丝数达到1000','followers',1000,11);
