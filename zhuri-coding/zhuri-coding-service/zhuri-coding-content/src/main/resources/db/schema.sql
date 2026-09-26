
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
CREATE TABLE `ap_achievement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '勋章唯一编码',
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '勋章名称',
  `category` tinyint NOT NULL DEFAULT '2' COMMENT '分类：1=新人成长，2=活跃成就',
  `icon` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '图标（emoji字符）',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '解锁条件文案',
  `trigger_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发类型：publish_article/publish_content/checkin_streak/likes/followers',
  `threshold` int NOT NULL DEFAULT '0' COMMENT '解锁阈值',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示排序',
  `is_active` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：1=启用 0=禁用',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成就勋章定义表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '活动标题',
  `description` text COLLATE utf8mb4_unicode_ci COMMENT '活动描述',
  `cover_image` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '活动封面图',
  `activity_type` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'article' COMMENT '活动类型: article/pin',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'upcoming' COMMENT '状态: upcoming/ongoing/ended',
  `category` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'hot' COMMENT '分类: hot/backend/frontend/android/ios/ai/devtools/codelife',
  `start_date` date NOT NULL COMMENT '开始日期',
  `end_date` date NOT NULL COMMENT '结束日期',
  `topic_id` bigint DEFAULT NULL COMMENT '关联话题ID',
  `total_participants` int DEFAULT '0' COMMENT '参与人数',
  `total_read_count` bigint DEFAULT '0' COMMENT '总阅读量',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_date` (`status`,`start_date`,`end_date`)
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='创作活动表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_ai_feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '反馈用户ID',
  `feature` varchar(32) NOT NULL COMMENT 'AI功能：aiask_global-社区问答 aiask_article-单篇问答 summary-摘要 precheck-发布预检',
  `scene_id` varchar(64) NOT NULL DEFAULT '' COMMENT '场景ID（如文章ID/空）',
  `question_hash` char(32) NOT NULL DEFAULT '' COMMENT '问题内容哈希(MD5) 用于幂等',
  `question` varchar(500) NOT NULL DEFAULT '' COMMENT '问题（截断）',
  `answer` varchar(500) NOT NULL DEFAULT '' COMMENT '回答（截断，留分析样本）',
  `feedback` tinyint NOT NULL COMMENT '1-鏈夊府鍔?馃憤) -1-娌″府鍔?鏈夎?(馃憥)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_feature_scene_q` (`user_id`,`feature`,`scene_id`,`question_hash`),
  KEY `idx_feature_fb` (`feature`,`feedback`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 反馈(点赞/点踩)';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_ai_prompt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `prompt_key` varchar(64) NOT NULL COMMENT '业务键（如 ai_ask_system），同 key 多行多版本',
  `version` int NOT NULL DEFAULT '1' COMMENT '版本号，同 key 内递增',
  `content` text NOT NULL COMMENT 'prompt 全文',
  `rollout_percent` int NOT NULL DEFAULT '0' COMMENT '灰度放量百分比；0=正式版，1~99=灰度',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1 启用 0 停用（停用即回退代码兜底）',
  `remark` varchar(200) DEFAULT NULL COMMENT '变更说明（谁改的/为什么）',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_version` (`prompt_key`,`version`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI Prompt 版本注册表（P2-1：版本化 + 灰度 + 归因）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_ai_topup_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(32) NOT NULL COMMENT '业务订单号（ai+雪花，作为支付宝 out_trade_no）',
  `user_id` int NOT NULL COMMENT '购买用户',
  `package_code` varchar(16) NOT NULL COMMENT '额度包：q200/q1000/q5000',
  `amount_fen` int NOT NULL COMMENT '瀹炰粯閲戦?锛堝垎锛夛紝鍥炶皟鎸夋?鏍￠獙闃茬?鏀',
  `quota_added` int NOT NULL COMMENT '鍒拌处娆℃暟',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-寰呮敮浠?1-鏀?粯鎴愬姛(宸插叆璐? 2-宸插叧闂',
  `pay_trade_no` varchar(64) NOT NULL DEFAULT '' COMMENT '鏀?粯瀹濅氦鏄撳彿',
  `pay_time` datetime DEFAULT NULL COMMENT '鏀?粯鏃堕棿',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `token_added` bigint NOT NULL DEFAULT '0' COMMENT '本单到账 token 额度',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 额度包充值订单';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_ai_wallet` (
  `user_id` int NOT NULL COMMENT '用户ID',
  `balance` int NOT NULL DEFAULT '0' COMMENT 'AI 浣欓?锛堟?锛夛紝璐熷?绂佹?',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `token_balance` bigint NOT NULL DEFAULT '0' COMMENT '已购 AI token 余额（用量按 token 结算）',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 额度钱包';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_aigc_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content_type` tinyint NOT NULL COMMENT '1-文章 2-沸点 3-课程小节',
  `content_id` bigint NOT NULL COMMENT '内容ID',
  `author_id` int NOT NULL COMMENT '作者用户ID',
  `score` tinyint NOT NULL COMMENT '缁煎悎鐤戜技鍒?0-100',
  `signals_json` varchar(2000) NOT NULL DEFAULT '' COMMENT '淇″彿鏄庣粏 JSON(burst/repeat/template/anchor/author_cos/llm_verdict)',
  `method` varchar(32) NOT NULL DEFAULT 'stat_v1' COMMENT '检测版本/方法',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-仅记录 1-flagged 2-申诉中 3-人工复核放行 4-人工确认水文',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_type_content` (`content_type`,`content_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AIGC 水文检测记录(内容诚信治理)';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标题',
  `summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '摘要',
  `author_id` int unsigned DEFAULT NULL COMMENT '文章作者的ID',
  `author_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '作者昵称',
  `channel_id` int unsigned DEFAULT NULL COMMENT '文章所属频道ID',
  `channel_name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '频道名称',
  `layout` tinyint unsigned DEFAULT NULL COMMENT '    1 无图文章\n    2 有图文章',
  `flag` tinyint unsigned DEFAULT NULL COMMENT '文章标记\r\n            0 普通文章\r\n            1 热点文章\r\n            2 置顶文章\r\n            3 精品文章\r\n            4 大V 文章',
  `cover_image` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文章封面图片',
  `cont_pics` json DEFAULT NULL COMMENT '内容里嵌入的图片',
  `likes` int unsigned DEFAULT NULL COMMENT '点赞数量',
  `tags` json DEFAULT NULL,
  `collection` int unsigned DEFAULT NULL COMMENT '收藏数量',
  `comment` int unsigned DEFAULT NULL COMMENT '评论数量',
  `comment_open` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否开放评论 1开放 0关闭',
  `tip_count` int unsigned NOT NULL DEFAULT '0' COMMENT '打赏人数',
  `tip_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '打赏总金额',
  `views` int unsigned DEFAULT NULL COMMENT '阅读数量',
  `score` int DEFAULT NULL,
  `province_id` int unsigned DEFAULT NULL COMMENT '省市',
  `city_id` int unsigned DEFAULT NULL COMMENT '市区',
  `county_id` int unsigned DEFAULT NULL COMMENT '区县',
  `status` tinyint DEFAULT NULL,
  `sync_status` tinyint(1) DEFAULT '0' COMMENT '同步状态',
  `origin` tinyint unsigned DEFAULT '0' COMMENT '来源',
  `static_url` varchar(150) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_time` datetime DEFAULT NULL COMMENT '创建时间',
  `publish_time` datetime DEFAULT NULL COMMENT '发布时间',
  `is_deleted` tinyint DEFAULT '0' COMMENT '是否删除 0未删除 1已删除',
  `reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `author_image` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `column_id` bigint DEFAULT NULL COMMENT '专栏ID',
  `is_aigc` tinyint NOT NULL DEFAULT '0' COMMENT '0-正常 1-疑似AI水文(内容诚信治理)',
  `aigc_score` tinyint NOT NULL DEFAULT '0' COMMENT 'AI水文疑似分 0-100(越高越疑似)',
  `aigc_checked_at` datetime DEFAULT NULL COMMENT 'AIGC妫?祴鏃堕棿',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_status_channel_publish` (`status`,`channel_id`,`publish_time`,`id`),
  KEY `idx_status_publish` (`status`,`publish_time`,`id`),
  KEY `idx_status_score` (`status`,`score`,`id`),
  KEY `idx_author_status_publish` (`author_id`,`status`,`publish_time`,`id`),
  KEY `idx_column_status_publish` (`column_id`,`status`,`publish_time`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2091867593875820960 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='文章信息表，存储已发布的文章';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_ai_analysis` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `article_id` bigint NOT NULL COMMENT '文章ID',
  `title_relevance_score` int DEFAULT NULL COMMENT '标题相关性评分(0-100)',
  `title_relevance_reason` text COMMENT '标题相关性判断理由',
  `quality_score` int DEFAULT NULL COMMENT '内容质量综合评分(0-100)',
  `originality_score` int DEFAULT NULL COMMENT '原创性评分(0-100)',
  `logic_score` int DEFAULT NULL COMMENT '逻辑性评分(0-100)',
  `clarity_score` int DEFAULT NULL COMMENT '表达清晰度评分(0-100)',
  `quality_comment` text COMMENT '内容质量综合评语',
  `is_tech_content` tinyint(1) DEFAULT NULL COMMENT '是否技术内容(0:否,1:是)',
  `tech_confidence` decimal(5,2) DEFAULT NULL COMMENT '技术相关性置信度',
  `raw_response` mediumtext COMMENT 'AI原始响应JSON',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `is_violation` tinyint(1) DEFAULT '0' COMMENT '是否违规 0-否 1-是',
  `violation_type` varchar(50) DEFAULT '' COMMENT '违规类型：色情低俗/暴力恐怖/政治敏感/违法信息/其他',
  `violation_reason` varchar(500) DEFAULT '' COMMENT '违规原因详细描述',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_article_id` (`article_id`)
) ENGINE=InnoDB AUTO_INCREMENT=160 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI文章分析结果表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_audit_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `article_id` bigint NOT NULL COMMENT '文章ID',
  `author_id` bigint NOT NULL COMMENT '作者ID',
  `title` varchar(255) NOT NULL DEFAULT '' COMMENT '文章标题',
  `content` longtext COMMENT '文章内容',
  `reason` varchar(500) NOT NULL DEFAULT '' COMMENT '瀹℃牳缁撴灉璇存槑锛堝け璐ュ師鍥?閫氳繃璇存槑锛',
  `audit_type` varchar(50) NOT NULL DEFAULT 'text' COMMENT '审核类型: text-文本审核, image-图片审核',
  `status` tinyint NOT NULL DEFAULT '2' COMMENT '瀹℃牳鐘舵?: 1-閫氳繃 2-澶辫触',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_article_id` (`article_id`),
  KEY `idx_author_id` (`author_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章审核记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_comment` (
  `id` bigint NOT NULL COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `article_id` bigint NOT NULL COMMENT '文章ID',
  `content` text NOT NULL COMMENT '评论内容',
  `parent_id` bigint DEFAULT NULL COMMENT '父评论ID',
  `root_id` bigint DEFAULT NULL COMMENT '根评论ID',
  `digg_count` int DEFAULT '0' COMMENT '点赞数',
  `reply_count` int DEFAULT '0' COMMENT '回复数',
  `status` int DEFAULT '0' COMMENT '状态 0-正常 1-隐藏',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at` datetime DEFAULT NULL COMMENT '删除时间（软删除）',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_article_id` (`article_id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE,
  KEY `idx_root_id` (`root_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章评论表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `article_id` bigint unsigned DEFAULT NULL COMMENT '文章ID',
  `is_comment` tinyint unsigned DEFAULT NULL COMMENT '是否可评论',
  `is_forward` tinyint unsigned DEFAULT NULL COMMENT '是否转发',
  `is_down` tinyint unsigned DEFAULT NULL COMMENT '是否下架',
  `is_delete` tinyint unsigned DEFAULT NULL COMMENT '是否已删除',
  `is_recommend` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否推荐(0:不推荐,1:推荐)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_article_id` (`article_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2091867593875820891 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='APP已发布文章配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_content` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `article_id` bigint unsigned DEFAULT NULL COMMENT '文章ID',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '文章内容',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_article_id` (`article_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2091867593942929535 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='APP已发布文章内容表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_draft` (
  `id` bigint NOT NULL COMMENT '主键',
  `article_id` bigint DEFAULT NULL COMMENT '关联文章ID',
  `title` varchar(200) DEFAULT NULL COMMENT '标题',
  `author_id` bigint DEFAULT NULL COMMENT '作者id',
  `channel_id` int DEFAULT NULL COMMENT '频道id',
  `channel_name` varchar(50) DEFAULT NULL COMMENT '频道名称',
  `layout` smallint DEFAULT NULL COMMENT '布局',
  `tags` json DEFAULT NULL,
  `cover_image` varchar(256) DEFAULT NULL COMMENT '封面图片',
  `cont_pics` json DEFAULT NULL COMMENT '内容里嵌入的图片',
  `topic` varchar(200) DEFAULT NULL COMMENT '话题',
  `content` longtext COMMENT '文章内容',
  `summary` varchar(500) DEFAULT NULL COMMENT '摘要',
  `publish_time` datetime DEFAULT NULL COMMENT '定时发布时间',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `status` tinyint DEFAULT '0' COMMENT '状态',
  `is_deleted` tinyint DEFAULT '0' COMMENT '是否删除 0未删除 1已删除',
  `column_id` bigint DEFAULT NULL COMMENT '专栏ID',
  PRIMARY KEY (`id`),
  KEY `idx_author_id` (`author_id`),
  KEY `idx_article_id` (`article_id`),
  KEY `idx_channel_id` (`channel_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章草稿表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_exposure` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID（匿名曝光统一记 0）',
  `article_id` bigint NOT NULL COMMENT '文章ID',
  `channel` varchar(32) NOT NULL DEFAULT '__all__' COMMENT '推荐渠道：__all__/具体频道ID/follow',
  `sub_tab` varchar(16) NOT NULL DEFAULT 'recommend' COMMENT '分栏：recommend/latest',
  `page` int NOT NULL DEFAULT '0' COMMENT '页码（从 0 起）',
  `position` int NOT NULL DEFAULT '0' COMMENT '该页内位次（从 0 起）',
  `seed` bigint NOT NULL DEFAULT '0' COMMENT '会话种子（刷新/分页锚点）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '曝光时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_article` (`user_id`,`article_id`,`create_time`),
  KEY `idx_article_time` (`article_id`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=4949 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章推荐曝光记录（数据回流闭环）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_report` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` int unsigned DEFAULT NULL COMMENT '举报人ID',
  `article_id` bigint unsigned DEFAULT NULL COMMENT '被举报文章ID',
  `author_id` bigint unsigned DEFAULT NULL COMMENT '被举报文章作者ID',
  `reason` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '举报原因',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '补充说明（≤100字）',
  `image_urls` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '举报图片URL（逗号分隔，最多4张）',
  `status` tinyint DEFAULT '0' COMMENT '处理状态：0待处理 1已处理',
  `created_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_article_id` (`article_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章举报记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_tip_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单号',
  `user_id` int NOT NULL COMMENT '打赏人用户ID',
  `article_id` bigint NOT NULL COMMENT '文章ID',
  `author_id` int NOT NULL COMMENT '作者用户ID',
  `amount` decimal(10,2) NOT NULL COMMENT '打赏金额',
  `message` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '打赏留言',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态: 0-待支付 1-已支付',
  `trade_no` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '支付宝交易号',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_article_id` (`article_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_author_id` (`author_id`)
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章打赏订单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_article_tip_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '关联订单号',
  `user_id` int NOT NULL COMMENT '打赏人用户ID',
  `nick_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '打赏人昵称',
  `avatar` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '打赏人头像',
  `article_id` bigint NOT NULL COMMENT '文章ID',
  `author_id` int NOT NULL COMMENT '作者用户ID',
  `amount` decimal(10,2) NOT NULL COMMENT '打赏金额',
  `message` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '打赏留言',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '打赏时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_article_id` (`article_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章打赏流水表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_audit_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '涓婚敭',
  `task_key` varchar(96) NOT NULL COMMENT '骞傜瓑閿?{bizType}:{bizId}锛屽悓涓?笟鍔′粎涓?潯浠诲姟',
  `biz_type` varchar(32) NOT NULL COMMENT '涓氬姟绫诲瀷锛歛rticle_comment / pins / pins_comment',
  `biz_id` bigint NOT NULL COMMENT '涓氬姟ID锛堣瘎璁篒D / 娌哥偣ID锛',
  `actor_user_id` int DEFAULT NULL COMMENT '琛屼负鐢ㄦ埛ID锛堝?鏍搁?杩囧悗鍙戠瓑绾хН鍒嗙敤锛',
  `author_id` int DEFAULT NULL COMMENT '鍐呭?浣滆?ID',
  `author_name` varchar(64) DEFAULT '' COMMENT '鍐呭?浣滆?鏄电О',
  `content` varchar(2000) DEFAULT '' COMMENT '寰呭?鍐呭?蹇?収',
  `image_urls` varchar(2000) DEFAULT '' COMMENT '鍥剧墖URL锛堥?鍙峰垎闅旓級',
  `target_type` tinyint DEFAULT NULL COMMENT '鐩?爣鍐呭?绫诲瀷锛?-鏂囩珷 2-娌哥偣锛堝?鏍搁?杩囧悗鍙戦?鐭ョ敤锛',
  `target_id` bigint DEFAULT NULL COMMENT '鐩?爣鍐呭?ID',
  `target_user_id` int DEFAULT NULL COMMENT '鐩?爣鍐呭?浣滆?ID',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-寰呭?鏍?1-瀹℃牳涓?2-瀹屾垚(閫氳繃) 3-瀹屾垚(杩濊?) 4-閲嶈瘯瓒呴檺闄嶇骇閫氳繃',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '閲嶈瘯娆℃暟锛堝?鐞嗗紓甯告椂绱??锛',
  `next_retry_time` datetime DEFAULT NULL COMMENT '涓嬫?鎵ц?鏃堕棿锛堟寚鏁伴?閬跨敤锛',
  `audit_time` datetime DEFAULT NULL COMMENT '瀹℃牳瀹屾垚鏃堕棿',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_key` (`task_key`),
  KEY `idx_status_next` (`status`,`next_retry_time`),
  KEY `idx_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='缁熶竴寮傛?瀹℃牳浠诲姟琛?紙鍙?潬闃熷垪锛';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_author_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` int NOT NULL COMMENT '用户ID',
  `real_name` varchar(64) DEFAULT '' COMMENT '姓名',
  `position` varchar(128) DEFAULT '' COMMENT '个人职位/职业',
  `resume` text COMMENT '个人履历/简介',
  `apply_reason` varchar(500) DEFAULT '' COMMENT '申请理由',
  `contact_wechat` varchar(64) DEFAULT '' COMMENT '联系方式-微信',
  `contact_email` varchar(128) DEFAULT '' COMMENT '联系方式-常用邮箱',
  `blogs` varchar(500) DEFAULT '' COMMENT '掘金账号及其他博客/技术媒体',
  `personal_intro` varchar(500) DEFAULT '' COMMENT '个人自我介绍（用于小册作者页展示）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=201 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='作者基础信息（供小册申请回填与作者页展示）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_behavior_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `action_code` varchar(50) NOT NULL COMMENT '行为编码（唯一）',
  `action_name` varchar(100) NOT NULL COMMENT '行为名称',
  `group_type` varchar(20) NOT NULL COMMENT '分组类型：社区基础/社区活跃/社区学习/社区影响力',
  `group_sort` int NOT NULL DEFAULT '0' COMMENT '分组排序：1社区基础 3社区学习 4社区影响力 5社区活跃',
  `score` decimal(5,1) NOT NULL DEFAULT '0.0' COMMENT '单次掘友分',
  `daily_limit` int NOT NULL DEFAULT '-1' COMMENT '每日上限，-1表示无上限',
  `icon_name` varchar(100) NOT NULL DEFAULT '' COMMENT '图标名称（前端本地资源）',
  `btn_name` varchar(50) NOT NULL DEFAULT '' COMMENT '按钮文案',
  `web_jump_url` varchar(255) NOT NULL DEFAULT '' COMMENT 'Web端跳转链接',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '行为排序',
  `is_active` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用 1是 0否',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_action_code` (`action_code`)
) ENGINE=InnoDB AUTO_INCREMENT=24 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行为项配置表（系统固定，所有用户一致）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_behavior_likes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `entry_id` bigint DEFAULT NULL,
  `user_id` int DEFAULT NULL,
  `type` int DEFAULT NULL,
  `operation` int DEFAULT NULL,
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_op_time` (`user_id`,`operation`,`created_time`),
  KEY `idx_entry_user` (`entry_id`,`user_id`,`type`)
) ENGINE=InnoDB AUTO_INCREMENT=321 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_browse_history` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `article_id` bigint DEFAULT NULL COMMENT '文章ID',
  `target_type` tinyint NOT NULL DEFAULT '1' COMMENT '目标类型: 1-文章, 2-沸点, 3-课程, 4-专栏',
  `article_title` varchar(200) DEFAULT NULL COMMENT '文章标题',
  `author_id` bigint DEFAULT NULL COMMENT '作者ID',
  `author_name` varchar(50) DEFAULT NULL COMMENT '作者名称',
  `author_avatar` varchar(255) DEFAULT NULL COMMENT '冗余作者头像',
  `summary` varchar(500) DEFAULT NULL COMMENT '冗余摘要',
  `read_count` int DEFAULT '0' COMMENT '冗余阅读量',
  `like_count` int DEFAULT '0' COMMENT '冗余点赞数',
  `comment_count` int DEFAULT '0' COMMENT '冗余评论数',
  `browse_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '浏览时间',
  `is_deleted` tinyint DEFAULT '0' COMMENT '是否删除 0未删除 1已删除',
  `deleted_at` datetime(3) DEFAULT NULL COMMENT '删除时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_browse_time` (`browse_time`),
  KEY `idx_user_deleted_time` (`user_id`,`is_deleted`,`browse_time`)
) ENGINE=InnoDB AUTO_INCREMENT=8655 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='浏览记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_channel` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) DEFAULT NULL COMMENT '频道名称',
  `description` varchar(255) DEFAULT NULL COMMENT '频道描述',
  `is_default` tinyint DEFAULT '0' COMMENT '是否默认频道 1默认 0非默认',
  `status` tinyint DEFAULT '1' COMMENT '是否启用 1启用 0禁用',
  `ord` int DEFAULT '0' COMMENT '默认排序',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_circle` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint DEFAULT NULL COMMENT '父圈子ID，null表示一级圈子',
  `name` varchar(50) NOT NULL COMMENT '圈子名称',
  `description` varchar(255) DEFAULT '' COMMENT '圈子描述',
  `icon` varchar(255) DEFAULT '' COMMENT '圈子图标',
  `member_count` int DEFAULT '0' COMMENT '成员数',
  `pins_count` int DEFAULT '0' COMMENT '沸点数',
  `sort_order` int DEFAULT '0' COMMENT '排序号',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `category_id` bigint DEFAULT NULL COMMENT '关联圈子分类ID',
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_sort_order` (`sort_order`)
) ENGINE=InnoDB AUTO_INCREMENT=48 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='圈子表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_circle_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分类名称',
  `sort_order` int DEFAULT '0' COMMENT '排序',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='圈子分类表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_circle_hot_config` (
  `id` int NOT NULL AUTO_INCREMENT,
  `circle_id` bigint NOT NULL COMMENT '圈子ID',
  `display_order` int NOT NULL COMMENT '展示顺序 1-5',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_order` (`display_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人气圈子配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_collection` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `entry_id` int unsigned DEFAULT NULL COMMENT '实体ID',
  `article_id` bigint unsigned DEFAULT NULL COMMENT '文章ID',
  `type` tinyint unsigned DEFAULT NULL COMMENT '点赞内容类型\r\n            0文章\r\n            1动态',
  `collection_time` datetime DEFAULT NULL COMMENT '创建时间',
  `published_time` datetime DEFAULT NULL COMMENT '发布时间',
  `user_id` int unsigned DEFAULT NULL,
  `created_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_collection_user_article` (`user_id`,`article_id`),
  KEY `idx_user_type` (`entry_id`,`article_id`)
) ENGINE=InnoDB AUTO_INCREMENT=437 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='APP收藏信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_column` (
  `id` bigint NOT NULL COMMENT '主键',
  `author_id` bigint DEFAULT NULL COMMENT '作者ID',
  `author_name` varchar(50) DEFAULT NULL COMMENT '作者名称',
  `author_image` varchar(255) DEFAULT NULL COMMENT '作者头像',
  `title` varchar(100) DEFAULT NULL COMMENT '专栏名称',
  `description` varchar(500) DEFAULT NULL COMMENT '专栏简介',
  `cover_image` varchar(500) DEFAULT NULL COMMENT '封面图片',
  `article_count` int DEFAULT '0' COMMENT '文章数',
  `subscribe_count` int DEFAULT '0' COMMENT '订阅人数',
  `status` tinyint DEFAULT '0' COMMENT '审核状态 0草稿 1提交审核 2审核失败 9已发布',
  `is_deleted` tinyint DEFAULT '0' COMMENT '是否删除 0未删除 1已删除',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_author_id` (`author_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='专栏表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `article_id` bigint NOT NULL COMMENT '文章ID',
  `user_id` int NOT NULL COMMENT '用户ID',
  `user_name` varchar(50) DEFAULT '' COMMENT '用户昵称',
  `user_avatar` varchar(255) DEFAULT '' COMMENT '用户头像',
  `parent_id` bigint DEFAULT NULL COMMENT '父评论ID，null表示一级评论',
  `root_id` bigint DEFAULT NULL COMMENT '根评论ID',
  `content` text NOT NULL COMMENT '评论内容',
  `comment_pics` varchar(2000) DEFAULT '' COMMENT '评论图片URL列表，逗号分隔',
  `like_count` int DEFAULT '0' COMMENT '点赞数',
  `reply_count` int DEFAULT '0' COMMENT '回复数',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `is_hidden` tinyint NOT NULL DEFAULT '0' COMMENT '0正常 1已折叠',
  PRIMARY KEY (`id`),
  KEY `idx_article_id` (`article_id`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_created_time` (`created_time`),
  KEY `idx_article_parent_created` (`article_id`,`parent_id`,`created_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1870 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文章评论表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_comment_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `comment_id` bigint NOT NULL COMMENT '评论ID',
  `user_id` int NOT NULL COMMENT '用户ID',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_user` (`comment_id`,`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=243 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论点赞记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_content_appeal` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `appeal_type` tinyint NOT NULL COMMENT '1-评论折叠申诉 2-文章AIGC误标申诉',
  `content_id` bigint NOT NULL COMMENT '被申诉内容ID（评论ID/文章ID）',
  `applicant_id` int NOT NULL COMMENT '申诉人用户ID（须为内容归属者）',
  `reason` varchar(500) NOT NULL DEFAULT '' COMMENT '申诉理由',
  `ai_verdict` varchar(1000) NOT NULL DEFAULT '' COMMENT 'AI预审JSON {suggest:allow|uphold, score, reason}（不终决）',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-寰呬汉宸ョ粓瀹?1-宸茶В闄?allow) 2-宸查┏鍥?uphold缁存寔)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_applicant` (`applicant_id`),
  KEY `idx_type_content` (`appeal_type`,`content_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容治理申诉(AI预审+人工终审)';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_course` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL COMMENT '课程标题',
  `subtitle` varchar(500) DEFAULT '' COMMENT '副标题/摘要',
  `description` text COMMENT '课程详细介绍',
  `cover_image` varchar(255) DEFAULT '' COMMENT '封面图URL',
  `author_id` int NOT NULL COMMENT '作者用户ID',
  `author_name` varchar(50) NOT NULL COMMENT '作者昵称',
  `author_avatar` varchar(255) DEFAULT '' COMMENT '作者头像',
  `price` decimal(10,2) DEFAULT '0.00' COMMENT '售价',
  `original_price` decimal(10,2) DEFAULT '0.00' COMMENT '原价',
  `status` tinyint DEFAULT '0' COMMENT '状态 0草稿 1待审 2已上架 3已下架',
  `reason` varchar(500) DEFAULT NULL COMMENT '审核拒绝理由',
  `category_id` int NOT NULL COMMENT '分类ID',
  `chapter_count` int DEFAULT '0' COMMENT '小节数量',
  `study_count` int DEFAULT '0' COMMENT '学习人数',
  `estimated_hours` decimal(5,1) DEFAULT '0.0' COMMENT '预估学习时长（小时）',
  `published_at` datetime DEFAULT NULL COMMENT '上架时间',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint(1) DEFAULT '0' COMMENT '软删除标记',
  `version` int DEFAULT '1' COMMENT '版本号',
  `sales_count` int DEFAULT '0' COMMENT '销售数量',
  `total_revenue` decimal(10,2) DEFAULT '0.00' COMMENT '累计收入',
  `apply_reason` varchar(500) DEFAULT '' COMMENT '申报审核拒绝原因',
  `apply_time` datetime DEFAULT NULL COMMENT '申报提交时间',
  `review_time` datetime DEFAULT NULL COMMENT '编辑审核时间',
  `apply_content` text COMMENT '小册申报内容（JSON：选题/大纲/简介/样章）',
  PRIMARY KEY (`id`),
  KEY `idx_category_id` (`category_id`),
  KEY `idx_status` (`status`),
  KEY `idx_published_at` (`published_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2091819114168292001 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_course_chapter` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_id` bigint NOT NULL COMMENT '所属课程',
  `title` varchar(200) NOT NULL COMMENT '小节标题',
  `sort_order` int DEFAULT '0' COMMENT '排序序号',
  `content` longtext COMMENT '小节正文（Markdown格式）',
  `word_count` int DEFAULT '0' COMMENT '字数统计',
  `is_free` tinyint DEFAULT '0' COMMENT '是否免费 0付费 1免费',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `status` tinyint DEFAULT '1' COMMENT '0草稿 1已发布',
  `estimated_minutes` int DEFAULT '5' COMMENT '预估阅读时长(分钟)',
  `comment_count` int DEFAULT '0' COMMENT '评论数',
  `review_note` varchar(500) DEFAULT '' COMMENT '作者提交审核留言',
  `is_aigc` tinyint NOT NULL DEFAULT '0' COMMENT '0-正常 1-疑似AI水文(禁止售卖)',
  `aigc_score` tinyint NOT NULL DEFAULT '0' COMMENT 'AI水文疑似分 0-100',
  PRIMARY KEY (`id`),
  KEY `idx_course_id` (`course_id`),
  KEY `idx_sort_order` (`sort_order`)
) ENGINE=InnoDB AUTO_INCREMENT=2089278840963515219 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程小节表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_course_chapter_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `chapter_id` bigint NOT NULL COMMENT '章节ID',
  `user_id` int NOT NULL COMMENT '用户ID',
  `user_name` varchar(50) DEFAULT '' COMMENT '用户昵称',
  `user_avatar` varchar(255) DEFAULT '' COMMENT '用户头像',
  `content` varchar(2000) NOT NULL COMMENT '评论内容',
  `parent_id` bigint DEFAULT '0' COMMENT '父评论ID(0为一级评论)',
  `reply_to_uid` int DEFAULT '0' COMMENT '回复目标用户ID',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_chapter_id` (`chapter_id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程章节评论表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_course_discount` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_id` bigint NOT NULL COMMENT '课程ID',
  `code` varchar(32) NOT NULL COMMENT '折扣码',
  `discount_type` tinyint NOT NULL COMMENT '1固定金额 2百分比',
  `discount_value` decimal(10,2) NOT NULL COMMENT '折扣值',
  `max_uses` int DEFAULT '100' COMMENT '最大使用次数',
  `used_count` int DEFAULT '0' COMMENT '已使用次数',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime NOT NULL COMMENT '结束时间',
  `status` tinyint DEFAULT '1' COMMENT '1有效 0失效',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_course_id` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程折扣码表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_course_invitation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_id` bigint NOT NULL COMMENT '课程ID',
  `inviter_id` int NOT NULL COMMENT '邀请人ID',
  `token` varchar(64) NOT NULL COMMENT '邀请token',
  `status` tinyint DEFAULT '0' COMMENT '0待接受 1已接受 2已过期',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token`),
  KEY `idx_course_id` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程邀请编辑表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_course_order` (
  `id` bigint NOT NULL COMMENT '主键',
  `order_no` varchar(50) NOT NULL COMMENT '对外订单号',
  `course_id` bigint NOT NULL COMMENT '课程id',
  `user_id` int NOT NULL COMMENT '下单用户',
  `original_amount` decimal(10,2) NOT NULL,
  `discount_amount` decimal(10,2) NOT NULL,
  `paid_amount` decimal(10,2) DEFAULT NULL COMMENT '支付金额',
  `total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
  `discount_code` varchar(32) NOT NULL,
  `coupon_item_code` varchar(32) NOT NULL DEFAULT '' COMMENT '使用的通用5折券道具代码（空=未使用）',
  `pay_method` varchar(20) DEFAULT '' COMMENT '支付方式',
  `status` tinyint DEFAULT '0' COMMENT '状态 0待支付 1已支付 2已取消 3已退款',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `trade_no` varchar(32) DEFAULT NULL COMMENT '交易号',
  `refund_trade_no` varchar(64) DEFAULT NULL COMMENT '退款交易号',
  `refund_time` datetime DEFAULT NULL COMMENT '退款时间',
  `refund_pending` tinyint NOT NULL DEFAULT '0' COMMENT '是否待重试退款',
  `refund_pending_reason` varchar(100) DEFAULT NULL COMMENT '????????order_closed / discount_code_exhausted / coupon_consume_failed?',
  `refund_retry_count` int NOT NULL DEFAULT '0' COMMENT '退款失败已重试次数',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程订单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_course_reading_progress` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` int NOT NULL COMMENT '用户ID',
  `chapter_id` bigint NOT NULL COMMENT '小节ID',
  `progress` float DEFAULT '0' COMMENT '阅读进度百分比',
  `last_read_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '最后阅读时间',
  `is_completed` tinyint(1) DEFAULT '0' COMMENT '是否已完成',
  `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_chapter` (`user_id`,`chapter_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='阅读进度表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_course_review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_id` bigint NOT NULL COMMENT '课程ID',
  `reviewer_id` int NOT NULL COMMENT '审核人ID',
  `action` tinyint NOT NULL COMMENT '1通过 2拒绝 3反馈',
  `comment` varchar(1000) DEFAULT '' COMMENT '审核意见',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_course_id` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程审核记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_course_settlement` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `author_id` int NOT NULL COMMENT '作者ID',
  `course_id` bigint NOT NULL COMMENT '课程ID',
  `settlement_month` varchar(7) NOT NULL COMMENT '结算月份(YYYY-MM)',
  `total_sales` decimal(10,2) DEFAULT '0.00' COMMENT '总销售额',
  `platform_share` decimal(10,2) DEFAULT '0.00' COMMENT '平台分成',
  `author_share` decimal(10,2) DEFAULT '0.00' COMMENT '作者分成',
  `order_count` int DEFAULT '0' COMMENT '订单数',
  `status` tinyint DEFAULT '0' COMMENT '0待结算 1已结算',
  `settled_at` datetime DEFAULT NULL COMMENT '结算时间',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_author_course_month` (`author_id`,`course_id`,`settlement_month`),
  KEY `idx_author_id` (`author_id`),
  KEY `idx_settlement_month` (`settlement_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程收入结算表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_level_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `level_type` tinyint NOT NULL COMMENT '等级类型 1-逐友等级 2-逐力值等级',
  `level_value` tinyint NOT NULL COMMENT '等级值',
  `min_score` int NOT NULL COMMENT '最低分数',
  `max_score` int NOT NULL COMMENT '最高分数',
  `title` varchar(50) DEFAULT NULL COMMENT '等级头衔',
  `icon_url` varchar(255) DEFAULT NULL COMMENT '等级图标',
  `description` varchar(200) DEFAULT NULL COMMENT '等级描述',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `diamond_reward` int DEFAULT '0' COMMENT '等级升级钻石奖励数量',
  `is_active` tinyint(1) DEFAULT '1' COMMENT '是否启用',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_level_type_value` (`level_type`,`level_value`)
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='等级配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_level_privilege` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `level_type` tinyint NOT NULL COMMENT '等级类型 1-逐友等级 2-逐力值等级',
  `level_value` tinyint NOT NULL COMMENT '等级值',
  `privilege_name` varchar(100) NOT NULL COMMENT '特权名称',
  `privilege_code` varchar(50) NOT NULL COMMENT '特权编码',
  `icon_name` varchar(100) NOT NULL DEFAULT '' COMMENT '图标名称（前端本地资源）',
  `poster_name` varchar(100) NOT NULL DEFAULT '' COMMENT '海报图名称（前端本地资源）',
  `description` varchar(500) DEFAULT NULL COMMENT '特权描述',
  `desc_json` text COMMENT '权益说明JSON：[{desc_title,desc_content}]',
  `need_jscore_level` int NOT NULL DEFAULT '0' COMMENT '所需逐日等级',
  `web_jump_url` varchar(255) NOT NULL DEFAULT '' COMMENT 'Web端跳转链接',
  `app_jump_url` varchar(255) NOT NULL DEFAULT '' COMMENT 'App端跳转链接',
  `priv_status` tinyint NOT NULL DEFAULT '0' COMMENT '权益状态 1已解锁 0未解锁',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `is_active` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用 1是 0否',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_level_privilege` (`level_type`,`level_value`,`privilege_code`),
  KEY `idx_level_type` (`level_type`)
) ENGINE=InnoDB AUTO_INCREMENT=44 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='等级特权表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_outbox_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_key` varchar(64) NOT NULL COMMENT '业务幂等键（如 PAY_REWARD:{orderNo}），同一事件只写一次',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型 = OutboxHandler 路由键',
  `payload` varchar(2000) NOT NULL COMMENT 'JSON 载荷，由对应 Handler 反序列化执行',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0=PENDING 1=DONE 2=DEAD 3=PROCESSING',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `max_retries` int NOT NULL DEFAULT '5' COMMENT '重试上限，达到即置 DEAD',
  `next_retry_at` datetime DEFAULT NULL COMMENT '下次重试时间（指数退避）；PENDING 且到期才被分发',
  `last_error` varchar(500) DEFAULT NULL COMMENT '最近一次失败原因（截断到 500 字符）',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（PROCESSING 超时回收依据）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_key` (`event_key`),
  KEY `idx_status_next_retry` (`status`,`next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地消息表（Transactional Outbox，支付联动等异步副作用）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_permission_definition` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `permission_code` varchar(50) NOT NULL COMMENT '权限编码',
  `permission_name` varchar(100) NOT NULL COMMENT '权限名称',
  `description` varchar(500) DEFAULT NULL COMMENT '权限描述',
  `related_level_type` tinyint NOT NULL COMMENT '关联等级类型 1-逐日(逐友)等级 2-逐力值等级',
  `required_level` tinyint NOT NULL COMMENT '所需等级',
  `is_active` tinyint DEFAULT '1' COMMENT '是否启用 1启用 0禁用',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`permission_code`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限定义表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_pins` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `user_name` varchar(50) DEFAULT '' COMMENT '用户昵称',
  `user_avatar` varchar(255) DEFAULT '' COMMENT '用户头像',
  `content` text NOT NULL COMMENT '帖子内容',
  `circle_id` bigint DEFAULT NULL COMMENT '圈子ID（可选）',
  `topic_id` bigint DEFAULT NULL COMMENT '话题ID（可选）',
  `view_count` int DEFAULT '0',
  `like_count` int DEFAULT '0' COMMENT '点赞数',
  `comment_count` int DEFAULT '0' COMMENT '评论数',
  `share_count` int DEFAULT '0' COMMENT '分享数',
  `status` tinyint DEFAULT '0' COMMENT '状态 0草稿 1待审 2审核失败 9已发布',
  `reason` varchar(500) DEFAULT NULL COMMENT '审核拒绝理由',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `author_id` bigint DEFAULT NULL,
  `author_name` varchar(50) DEFAULT NULL,
  `author_image` varchar(255) DEFAULT NULL,
  `image_urls` varchar(1000) DEFAULT NULL,
  `topic_tags` varchar(500) DEFAULT NULL,
  `link_url` varchar(500) DEFAULT '' COMMENT '链接URL',
  `link_title` varchar(255) DEFAULT '' COMMENT '链接标题',
  `is_deleted` tinyint DEFAULT '0',
  `publish_time` datetime DEFAULT NULL,
  `review_time` datetime DEFAULT NULL COMMENT '审核成功时间',
  `is_aigc` tinyint NOT NULL DEFAULT '0' COMMENT '0-正常 1-疑似AI水文(内容诚信治理)',
  `aigc_score` tinyint NOT NULL DEFAULT '0' COMMENT 'AI水文疑似分 0-100',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_circle_id` (`circle_id`),
  KEY `idx_topic_id` (`topic_id`),
  KEY `idx_created_time` (`created_time`),
  KEY `idx_status_deleted_review` (`status`,`is_deleted`,`review_time`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2095199586856367894 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='沸点帖子表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_pins_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pins_id` bigint NOT NULL COMMENT '沸点ID',
  `user_id` int NOT NULL COMMENT '用户ID',
  `user_name` varchar(50) DEFAULT '' COMMENT '用户昵称',
  `user_avatar` varchar(255) DEFAULT '' COMMENT '用户头像',
  `parent_id` bigint DEFAULT NULL COMMENT '父评论ID，null表示一级评论',
  `content` text NOT NULL COMMENT '评论内容',
  `image_urls` varchar(1000) DEFAULT '' COMMENT '评论图片URL列表，逗号分隔',
  `reply_to_user_id` int DEFAULT NULL COMMENT '被回复用户ID，回复二级评论时使用',
  `reply_to_user_name` varchar(50) DEFAULT '' COMMENT '被回复用户昵称',
  `like_count` int DEFAULT '0' COMMENT '点赞数',
  `reply_count` int DEFAULT '0' COMMENT '回复数',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_pins_id` (`pins_id`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_created_time` (`created_time`)
) ENGINE=InnoDB AUTO_INCREMENT=7788 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='沸点评论表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_pins_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pins_id` bigint NOT NULL COMMENT '沸点ID',
  `user_id` int NOT NULL COMMENT '用户ID',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pins_user` (`pins_id`,`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4457 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='沸点帖子点赞记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_tag` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) DEFAULT NULL COMMENT '标签名称',
  `category` varchar(50) DEFAULT NULL COMMENT '标签分类（语言方向/技术栈/数据库/其它）',
  `sort` int DEFAULT '0' COMMENT '排序',
  `status` tinyint DEFAULT '1' COMMENT '状态 1启用 0禁用',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `post_article_count` int DEFAULT '0' COMMENT '文章关联的数量',
  `concern_user_count` int DEFAULT '0' COMMENT '用户关注量',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=60 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标签表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_topic` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '话题名称',
  `description` varchar(200) DEFAULT '' COMMENT '导语',
  `cover_image` varchar(255) DEFAULT '' COMMENT '话题封面图',
  `type` tinyint DEFAULT '1' COMMENT '1-纯沸点, 2-文章+沸点',
  `post_count` int DEFAULT '0' COMMENT '关联帖子总数',
  `view_count` bigint DEFAULT '0' COMMENT '总阅读数',
  `participant_count` bigint DEFAULT '0' COMMENT '参与人数',
  `recommend_sort` int DEFAULT '0' COMMENT '推荐排序权重',
  `is_recommend` tinyint(1) DEFAULT '0' COMMENT '是否推荐至侧边栏',
  `badge` varchar(20) DEFAULT '' COMMENT '角标文字',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT ' ''状态 1启用 0禁用''',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `theme_type` tinyint DEFAULT '1' COMMENT '1=文章和沸点都展示, 0=仅在沸点展示',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_sort_order` (`recommend_sort`)
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='话题表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_achievement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `achievement_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '勋章编码（关联 ap_achievement.code）',
  `progress` bigint NOT NULL DEFAULT '0' COMMENT '当前进度快照（只增不减）',
  `threshold` int NOT NULL DEFAULT '0' COMMENT '解锁阈值快照',
  `unlocked` tinyint NOT NULL DEFAULT '0' COMMENT '是否解锁：1=已解锁',
  `unlocked_at` datetime DEFAULT NULL COMMENT '解锁时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_code` (`user_id`,`achievement_code`),
  KEY `idx_user_unlocked` (`user_id`,`unlocked`)
) ENGINE=InnoDB AUTO_INCREMENT=52 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户成就解锁记录表（事件驱动）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_action_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `action_type` varchar(50) NOT NULL COMMENT '行为类型 daily_login, article_read, comment, like, share, follow',
  `score_change` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '逐日分变化量',
  `action_detail` varchar(500) DEFAULT NULL COMMENT '行为详情',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_action_type` (`action_type`)
) ENGINE=InnoDB AUTO_INCREMENT=2096227351084847106 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户行为日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_circle` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `circle_id` bigint NOT NULL COMMENT '圈子ID',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_circle` (`user_id`,`circle_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2545 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户圈子关系表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_course` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID',
  `course_id` bigint NOT NULL COMMENT '课程ID',
  `order_id` bigint DEFAULT NULL COMMENT '关联订单',
  `purchased_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '购买时间',
  `is_active` tinyint DEFAULT '1' COMMENT '权限是否有效',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `access_type` tinyint DEFAULT '1' COMMENT '1-购买 2-VIP借阅',
  `borrow_expire_at` datetime DEFAULT NULL COMMENT 'VIP借阅到期时间',
  `progress` decimal(5,2) DEFAULT '0.00' COMMENT '学习进度百分比',
  `last_learn_chapter_id` bigint DEFAULT NULL COMMENT '最后学习章节ID',
  `last_learn_at` datetime DEFAULT NULL COMMENT '最后学习时间',
  `is_trial` tinyint(1) DEFAULT '0' COMMENT '是否试学状态',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_course` (`user_id`,`course_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_course_id` (`course_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2089680531173085186 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户课程购买记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_daily_log` (
  `id` bigint DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `power_change` int DEFAULT NULL COMMENT '逐日分变动值',
  `change_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '改变类型',
  `source_id` bigint DEFAULT NULL,
  `calculated_at` datetime DEFAULT NULL,
  `created_time` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='逐日分日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_daily_progress` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `action_code` varchar(50) NOT NULL COMMENT '行为编码',
  `count` int NOT NULL DEFAULT '0' COMMENT '当日已完成次数',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_date_action` (`user_id`,`stat_date`,`action_code`),
  KEY `idx_user_date` (`user_id`,`stat_date`)
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户每日行为进度表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_diamond_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `change_type` varchar(50) NOT NULL DEFAULT '' COMMENT '变更类型: level_up-等级升级',
  `change_amount` int NOT NULL DEFAULT '0' COMMENT '变更数量(正数为增加)',
  `balance` int NOT NULL DEFAULT '0' COMMENT '变更后余额',
  `source_id` varchar(100) DEFAULT '' COMMENT '来源ID(如等级ID)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户钻石交易日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_follow` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` int unsigned DEFAULT NULL COMMENT '用户ID',
  `follow_id` int unsigned DEFAULT NULL COMMENT '关注作者ID',
  `follow_user_id` int unsigned DEFAULT NULL,
  `follow_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '粉丝昵称',
  `level` tinyint unsigned DEFAULT NULL COMMENT '关注度\r\n            0 偶尔感兴趣\r\n            1 一般\r\n            2 经常\r\n            3 高度',
  `is_notice` tinyint unsigned DEFAULT NULL COMMENT '是否动态通知',
  `created_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_follow_user_target` (`user_id`,`follow_user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2692 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='APP用户关注信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_level` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `daily_score` decimal(10,2) DEFAULT '0.00' COMMENT '逐日分(逐友值)',
  `daily_level` tinyint DEFAULT '1' COMMENT '逐日等级(逐友等级)',
  `power_value` int DEFAULT '0' COMMENT '逐力值',
  `daily_score_today` decimal(10,2) DEFAULT '0.00' COMMENT '今日逐日分(逐友分)获取量',
  `power_level` tinyint DEFAULT '1' COMMENT '逐力等级',
  `power_value_today` int DEFAULT '0' COMMENT '今日逐力值获取量',
  `diamond_balance` int DEFAULT '0' COMMENT '矿石余额',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `action_score` int DEFAULT '0' COMMENT '行为贡献分',
  `influence_score` int DEFAULT '0' COMMENT '影响力分',
  `quality_score` int DEFAULT '0' COMMENT '内容质量分',
  `violation_score` int DEFAULT '0' COMMENT '违规扣分',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2101606414095929346 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户等级表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `permission_code` varchar(50) NOT NULL COMMENT '权限编码',
  `granted_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '授予时间',
  `expired_at` datetime DEFAULT NULL COMMENT '过期时间',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_permission` (`user_id`,`permission_code`)
) ENGINE=InnoDB AUTO_INCREMENT=2095502923132755971 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户权限表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ap_user_power_log` (
  `id` bigint NOT NULL COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `record_date` date NOT NULL COMMENT '记录日期',
  `action_score` int DEFAULT '0' COMMENT '行为贡献分',
  `influence_score` int DEFAULT '0' COMMENT '影响力分',
  `quality_score` int DEFAULT '0' COMMENT '内容质量分',
  `violation_score` int DEFAULT '0' COMMENT '违规扣分',
  `power_value` int DEFAULT '0' COMMENT '逐力值',
  `power_level` int DEFAULT '1' COMMENT '逐力等级',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_record_date` (`record_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户逐力值日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `article_event` (
  `id` int NOT NULL AUTO_INCREMENT,
  `article_id` bigint NOT NULL COMMENT '文章id',
  `status` tinyint NOT NULL DEFAULT '1',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `retry_count` tinyint NOT NULL DEFAULT '0' COMMENT '事务重试次数',
  `max_retry_count` tinyint DEFAULT '2' COMMENT '最大重试次数',
  `minio_status` int NOT NULL DEFAULT '0' COMMENT '0初始化，1为还未成功，2已成功',
  `es_status` int NOT NULL DEFAULT '0' COMMENT '0初始化，1为还未成功，2已成功',
  `retry_time` datetime DEFAULT NULL COMMENT '生产者重试时间',
  `parameter` longtext COLLATE utf8mb4_unicode_ci COMMENT '方法执行参数',
  `pub_status` tinyint DEFAULT '0' COMMENT '发布状态 0=初始化 1=待重试 2=成功',
  PRIMARY KEY (`id`),
  UNIQUE KEY `article_id` (`article_id`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_featured_pin` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `circle_id` bigint NOT NULL COMMENT '圈子ID',
  `pin_id` bigint NOT NULL COMMENT '沸点帖子ID',
  `sort_order` int DEFAULT '0' COMMENT '排序权重',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_club_pin` (`circle_id`,`pin_id`),
  KEY `idx_sort_order` (`circle_id`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='圈子精选沸点表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `taskinfo_logs` (
  `task_id` bigint NOT NULL COMMENT '任务id',
  `execute_time` datetime(3) DEFAULT NULL COMMENT '执行时间',
  `in_one_hour` tinyint(1) DEFAULT NULL COMMENT '插入时，延迟间隔是否在一小时以内',
  `parameters` blob COMMENT '参数',
  `version` int DEFAULT '0' COMMENT '版本号,乐观锁',
  `status` int DEFAULT '1' COMMENT '状态 PROGRESSING=1 COMPLETED=2 FAILED=9',
  `first_exec_interval` bigint DEFAULT '0' COMMENT '预执行时间',
  `last_exec_interval` bigint DEFAULT '0' COMMENT '执行时间',
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `topic_circle_relation` (
  `topic_id` bigint NOT NULL,
  `circle_id` bigint NOT NULL,
  PRIMARY KEY (`topic_id`,`circle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='话题-圈子关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `topic_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic_id` bigint NOT NULL,
  `target_type` tinyint NOT NULL COMMENT '1-文章(article), 2-沸点(pin)',
  `target_id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_topic_target` (`topic_id`,`target_type`,`target_id`),
  KEY `idx_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='话题关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_behavior_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` int NOT NULL COMMENT '操作用户ID',
  `behavior_type` varchar(50) NOT NULL COMMENT '行为类型',
  `target_type` tinyint NOT NULL COMMENT '目标类型: 1-文章, 2-沸点, 3-用户, 4-课程, 5-专栏',
  `target_id` bigint NOT NULL COMMENT '目标ID',
  `target_user_id` int DEFAULT NULL COMMENT '目标作者/被关注用户ID',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 1-有效, 0-已撤销',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_type` (`user_id`,`behavior_type`,`created_time`),
  KEY `idx_target` (`target_type`,`target_id`),
  KEY `idx_user_target` (`user_id`,`target_type`,`target_id`)
) ENGINE=InnoDB AUTO_INCREMENT=63 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户行为记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_daily_stats` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `increment_collection` int DEFAULT '0' COMMENT '新增收藏数',
  `increment_likes` int DEFAULT '0' COMMENT '新增点赞数',
  `increment_fans` int DEFAULT '0' COMMENT '新增粉丝数',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_date` (`user_id`,`stat_date`)
) ENGINE=InnoDB AUTO_INCREMENT=91 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_onboarding_tasks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `task_type` varchar(30) NOT NULL COMMENT '任务类型',
  `status` tinyint DEFAULT '0' COMMENT '0-未开始, 1-进行中, 2-已完成待领奖, 3-已领奖',
  `condition_value` int DEFAULT '0' COMMENT '条件阈值',
  `reward_ore` int DEFAULT '0' COMMENT '奖励矿石数',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_task` (`user_id`,`task_type`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户新手任务表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_score_details` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `category` tinyint NOT NULL COMMENT '1-基础 2-活跃 3-学习 4-影响力 5-规范',
  `action_code` varchar(64) NOT NULL COMMENT '行为码，如 like_article, read_article',
  `action_desc` varchar(128) NOT NULL COMMENT '行为描述',
  `score` decimal(10,1) NOT NULL COMMENT '变化分值',
  `biz_id` varchar(64) DEFAULT NULL COMMENT '关联业务ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_category_created` (`user_id`,`category`,`created_at` DESC),
  KEY `idx_user_created` (`user_id`,`created_at` DESC)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_score_summary` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `stat_date` date NOT NULL COMMENT '统计日期',
  `total_score` decimal(10,1) DEFAULT '0.0',
  `basic_score` decimal(10,1) DEFAULT '0.0',
  `active_score` decimal(10,1) DEFAULT '0.0',
  `learn_score` decimal(10,1) DEFAULT '0.0',
  `effect_score` decimal(10,1) DEFAULT '0.0',
  `spec_score` decimal(10,1) DEFAULT '0.0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_user_date` (`user_id`,`stat_date`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_topic_post` (
  `user_id` bigint NOT NULL,
  `topic_id` bigint NOT NULL,
  `first_post_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_post_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `post_count` int DEFAULT '1',
  PRIMARY KEY (`user_id`,`topic_id`),
  KEY `idx_topic_id` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户话题发布记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

