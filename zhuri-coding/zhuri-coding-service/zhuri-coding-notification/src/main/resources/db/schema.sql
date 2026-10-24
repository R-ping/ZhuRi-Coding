
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
CREATE TABLE `ap_user_auto_reply` (
  `user_id` bigint NOT NULL COMMENT '閰嶇疆鏂癸紙琚?叧娉ㄨ?锛夌敤鎴稩D',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '寮?叧 1寮?0鍏',
  `content` varchar(300) NOT NULL COMMENT '褰撳墠缂栬緫涓?殑鏂囨?锛堝彲鑳芥湭杩囧?锛',
  `content_version` int NOT NULL DEFAULT '1' COMMENT '鏂囨?鐗堟湰鍙凤紝姣忔?鍐呭?鍙樻洿+1',
  `audit_status` tinyint NOT NULL DEFAULT '0' COMMENT '0寰呭? 1閫氳繃 2椹冲洖',
  `audit_reason` varchar(200) DEFAULT NULL COMMENT '椹冲洖鍘熷洜',
  `effective_content` varchar(300) DEFAULT NULL COMMENT '褰撳墠鐢熸晥鏂囨?锛堜笂涓?増宸茶繃瀹″唴瀹癸級',
  `last_sent_at` datetime DEFAULT NULL COMMENT '鏈?繎涓??鍙戦?鏃堕棿',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='鐢ㄦ埛鍏虫敞鑷?姩绉佷俊閰嶇疆';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `im_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `sender_id` bigint NOT NULL,
  `receiver_id` bigint NOT NULL,
  `client_id` varchar(64) DEFAULT NULL COMMENT '瀹㈡埛绔?幓閲岻D锛屽悓涓?細璇濆唴鍞?竴锛涗负绌鸿〃绀轰笉鍘婚噸',
  `content` text NOT NULL,
  `msg_type` tinyint DEFAULT '1' COMMENT '1-文本 2-图片',
  `sender_type` tinyint NOT NULL DEFAULT '1' COMMENT '1鐪熷疄鐢ㄦ埛 2绯荤粺浠ｅ彂',
  `source_type` varchar(32) DEFAULT NULL COMMENT '鏉ユ簮鏋氫妇锛屽綋鍓嶄粎 FOLLOW_AUTO_REPLY',
  `source_id` bigint DEFAULT NULL COMMENT '鏉ユ簮涓氬姟涓婚敭锛堝叧娉ㄥ叧绯籌D锛',
  `status` tinyint DEFAULT '0' COMMENT '0-已发送 1-已读',
  `is_deleted_for_sender` tinyint(1) DEFAULT '0',
  `is_deleted_for_receiver` tinyint(1) DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_client` (`session_id`,`client_id`),
  UNIQUE KEY `uk_source` (`source_type`,`source_id`),
  KEY `idx_session_created` (`session_id`,`created_at`),
  KEY `idx_sender_receiver` (`sender_id`,`receiver_id`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=646 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信消息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `im_sessions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_key` varchar(64) NOT NULL COMMENT '会话唯一标识，小ID_大ID排序后拼接',
  `user1_id` bigint NOT NULL,
  `user2_id` bigint NOT NULL,
  `last_message` varchar(500) DEFAULT NULL COMMENT '最后一条消息预览',
  `last_message_at` datetime DEFAULT NULL,
  `user1_unread_count` int DEFAULT '0',
  `user2_unread_count` int DEFAULT '0',
  `is_active` tinyint(1) DEFAULT '0' COMMENT 'B是否回复过A（状态机S3激活标记）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_session` (`session_key`),
  UNIQUE KEY `uk_session_key` (`session_key`),
  KEY `idx_user1_lastmsg` (`user1_id`,`last_message_at`),
  KEY `idx_user2_lastmsg` (`user2_id`,`last_message_at`)
) ENGINE=InnoDB AUTO_INCREMENT=9030 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信会话表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '接收通知的用户ID',
  `type` tinyint NOT NULL COMMENT '1-评论 2-赞/收藏 3-粉丝 4-系统',
  `source_id` varchar(64) DEFAULT NULL COMMENT '触发源ID（评论ID/文章ID/用户ID等）',
  `agg_key` varchar(128) DEFAULT NULL COMMENT '鑱氬悎閿?紙type:sourceId锛夛紱涓虹┖琛ㄧず涓嶈仛鍚堬紝涓??涓?簨浠',
  `content` text COMMENT '通知内容摘要（JSON存储多态数据）',
  `agg_count` int NOT NULL DEFAULT '1' COMMENT '鏈??鍚堝苟鐨勪簨浠舵暟锛涗笉鑱氬悎鎭掍负 1',
  `last_event_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鏈?悗涓??浜嬩欢鏃堕棿锛涘垪琛ㄦ帓搴忎笌娓告爣鐢ㄥ畠锛屼娇琚?《璧锋潵鐨勮?鑳芥诞鍒伴《閮',
  `is_read` tinyint(1) DEFAULT '0' COMMENT '0-未读 1-已读',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agg` (`user_id`,`agg_key`),
  KEY `idx_user_read_created` (`user_id`,`is_read`,`created_at`),
  KEY `idx_user_type_last` (`user_id`,`type`,`last_event_at`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=122 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='被动通知表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

