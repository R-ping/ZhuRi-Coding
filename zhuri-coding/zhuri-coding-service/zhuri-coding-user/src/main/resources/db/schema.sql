
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
CREATE TABLE `ap_admin_account` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '运营账号ID',
  `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录名',
  `password` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '口令（BCrypt，$2a$10$ 开头共 60 字符）',
  `nick_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '展示名（会话里下发给前端的昵称）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1 启用 0 停用；停用后登录被拒且已签发会话立即失效',
  `must_change_password` tinyint NOT NULL DEFAULT '0' COMMENT '1 表示仍在使用初始口令，前端据此强提示改密',
  `created_time` datetime NOT NULL COMMENT '创建时间',
  `updated_time` datetime NOT NULL COMMENT '更新时间',
  `last_login_time` datetime DEFAULT NULL COMMENT '最近一次登录成功时间',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营账号（与 C 端账号完全隔离）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_admin_account_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `account_id` int unsigned NOT NULL COMMENT '运营账号ID（ap_admin_account.id）',
  `role_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色编码：AUDITOR / OPERATOR / SUPER_ADMIN',
  `granted_by` int unsigned DEFAULT NULL COMMENT '授权人运营账号ID；初始迁移为 NULL',
  `granted_time` datetime NOT NULL COMMENT '授权时间',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '授权备注（为什么给他这个角色）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_role` (`account_id`,`role_code`),
  KEY `idx_role_code` (`role_code`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营账号角色绑定（一人多角色取权限并集）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_admin_audit_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` int unsigned NOT NULL COMMENT '操作人账号ID',
  `role_codes` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作时的角色快照（逗号分隔；角色可能事后被回收，故存快照）',
  `module` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务模块：REPORT举报 / CONTENT内容 / USER用户 / OPS运营位 / ACCESS访问控制（越权尝试）',
  `action` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '动作编码，如 USER_WARN / USER_BAN / USER_UNBAN',
  `target_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '对象类型：ARTICLE / COMMENT / PINS / USER；被拒访问时为 ENDPOINT',
  `target_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '对象ID（用字符串以兼容不同主键形态）',
  `reason` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作理由（必填，事后追溯的依据）',
  `detail` text COLLATE utf8mb4_unicode_ci COMMENT '变更摘要（处置前后摘要，不含敏感明文）',
  `result` tinyint NOT NULL DEFAULT '1' COMMENT '结果：1成功 0失败',
  `error_msg` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `ip` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作来源IP',
  `cost_ms` int DEFAULT NULL COMMENT '耗时（毫秒）',
  `created_time` datetime NOT NULL COMMENT '操作时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_time` (`user_id`,`created_time`),
  KEY `idx_module_time` (`module`,`created_time`),
  KEY `idx_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营操作审计日志（谁在何时对什么做了什么、为什么）';
/*!40101 SET character_set_client = @saved_cs_client */;
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
  `ban_until` datetime DEFAULT NULL COMMENT '封禁截止时间：> NOW() 表示封禁中；NULL 表示未被封禁',
  `ban_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '封禁理由（对当事人展示）',
  `ban_time` datetime DEFAULT NULL COMMENT '本次封禁的操作时间',
  `ban_operator_id` int unsigned DEFAULT NULL COMMENT '执行封禁的运营账号ID',
  `flag` tinyint unsigned DEFAULT NULL COMMENT '0 普通用户\r\n            1 自媒体人\r\n            2 大V',
  `created_time` datetime DEFAULT NULL COMMENT '注册时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_phone` (`phone`),
  UNIQUE KEY `uk_email` (`email`),
  KEY `idx_ban_until` (`ban_until`,`ban_time`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1889522430 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='APP用户信息表';
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

