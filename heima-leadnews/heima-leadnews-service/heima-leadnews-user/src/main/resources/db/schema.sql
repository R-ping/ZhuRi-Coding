
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `nickname` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '昵称',
  `password` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '密码（BCrypt加密）',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `email` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像',
  `sex` tinyint unsigned DEFAULT NULL COMMENT '0 男\r\n            1 女\r\n            2 未知',
  `is_certification` tinyint unsigned DEFAULT NULL COMMENT '0 未\r\n            1 是',
  `is_identity_authentication` tinyint(1) DEFAULT NULL COMMENT '是否身份认证',
  `status` tinyint unsigned DEFAULT NULL COMMENT '1正常\n            0锁定',
  `flag` tinyint unsigned DEFAULT NULL COMMENT '0 普通用户\r\n            1 自媒体人\r\n            2 大V',
  `created_time` datetime DEFAULT NULL COMMENT '注册时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1889522386 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='APP用户信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_social_binding` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` int unsigned DEFAULT NULL COMMENT '用户ID',
  `phone` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `platform` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '平台',
  `git_uid` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户在三方平台的唯一身份标识，通常github等需用户授权+回调的平台会使用',
  `weibo_uid` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户在微博平台的唯一身份标识，通常微博平台使用',
  `open_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '开放ID,通常微信公众号平台使用',
  `created_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户社交绑定表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_tags` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `category_code` varchar(30) DEFAULT NULL COMMENT '分类编码',
  `category_name` varchar(30) DEFAULT NULL COMMENT '分类名称',
  `tag_name` varchar(30) NOT NULL COMMENT '标签名称',
  `sort_order` int DEFAULT '0' COMMENT '排序',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_name` (`tag_name`)
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统标签表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_block_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '操作人',
  `target_type` tinyint NOT NULL COMMENT '1-作者(User), 2-标签(Tag)',
  `target_id` bigint NOT NULL COMMENT '目标ID',
  `create_time` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_user_target` (`user_id`,`target_type`,`target_id`)
) ENGINE=InnoDB AUTO_INCREMENT=43 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_profile` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `username` varchar(20) DEFAULT NULL COMMENT '用户名',
  `avatar_url` varchar(255) DEFAULT NULL COMMENT '头像URL',
  `career_start_date` date DEFAULT NULL COMMENT '开始工作日期',
  `career_direction` varchar(50) DEFAULT NULL COMMENT '职业方向',
  `position` varchar(50) DEFAULT NULL COMMENT '职位',
  `company` varchar(50) DEFAULT NULL COMMENT '公司',
  `website` varchar(100) DEFAULT NULL COMMENT '个人主页',
  `bio` varchar(100) DEFAULT NULL COMMENT '个人介绍',
  `region` varchar(50) DEFAULT NULL COMMENT '地区，对齐掘金 city',
  `education` varchar(100) DEFAULT NULL COMMENT '学历/学校，对齐掘金 education',
  `skills` varchar(500) DEFAULT NULL COMMENT '技术标签（JSON 数组字符串），对齐掘金 skills',
  `level` varchar(20) DEFAULT NULL COMMENT '等级快照，对齐掘金 level（逐力值/逐友）',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `privacy_message` tinyint DEFAULT '0' COMMENT '私信设置: 0-所有人, 1-我关注的人, 2-互相关注的人, 3-关闭',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户资料表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_tag_relation` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `tag_id` int NOT NULL COMMENT '标签ID',
  `rel_type` tinyint DEFAULT '1' COMMENT '关系类型: 1-兴趣标签, 2-订阅标签',
  PRIMARY KEY (`user_id`,`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户标签关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
