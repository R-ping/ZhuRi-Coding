-- ============================================
-- AI文章审核系统 - 数据库迁移脚本
-- 执行环境: MySQL (leadnews_article库)
-- 执行前请备份数据库
-- ============================================

-- 1. 创建AI分析结果表
CREATE TABLE IF NOT EXISTS `ap_article_ai_analysis` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id` BIGINT NOT NULL COMMENT '文章ID',
    `title_relevance_score` INT DEFAULT NULL COMMENT '标题相关性评分(0-100)',
    `title_relevance_reason` TEXT COMMENT '标题相关性判断理由',
    `quality_score` INT DEFAULT NULL COMMENT '内容质量综合评分(0-100)',
    `originality_score` INT DEFAULT NULL COMMENT '原创性评分(0-100)',
    `logic_score` INT DEFAULT NULL COMMENT '逻辑性评分(0-100)',
    `clarity_score` INT DEFAULT NULL COMMENT '表达清晰度评分(0-100)',
    `quality_comment` TEXT COMMENT '内容质量综合评语',
    `is_tech_content` TINYINT(1) DEFAULT NULL COMMENT '是否技术内容(0:否,1:是)',
    `tech_confidence` DECIMAL(5,2) DEFAULT NULL COMMENT '技术相关性置信度',
    `raw_response` MEDIUMTEXT COMMENT 'AI原始响应JSON',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_id` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI文章分析结果表';

-- 2. 在ap_article_config表中新增is_recommend字段
ALTER TABLE `ap_article_config` 
ADD COLUMN `is_recommend` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否推荐(0:不推荐,1:推荐)' AFTER `is_delete`;

-- 更新已有记录的is_recommend为默认值1
UPDATE `ap_article_config` SET `is_recommend` = 1 WHERE `is_recommend` IS NULL;