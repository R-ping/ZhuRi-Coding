-- 每日一题与刷题（Coding 延展第一层）—— 题库 / 作答记录 / 用户编码统计
-- 目标库：MySQL leadnews_article（与 ap_article 同库，题目来源文章需读正文与标题）
-- 说明：本脚本仅需在 leadnews_article 库上执行一次，重复执行会因表已存在而报错。
--
-- 设计要点：
--   1) 判题分三步走（单选多选 → 填空简答 → 沙箱代码题），本表结构先服务第一步：
--      options/answer 用 JSON 字符串存数组（answer 存正确选项下标，如 [0] 或 [0,2]），
--      不引入 JSON 列类型是为了与项目既有 mapper 的 String 直读口径一致；
--   2) stem_hash 是题干 MD5，全局唯一 —— 生成/投稿两个入口都靠它做重复题兜底（AI 生成易产生近似重复）；
--   3) 「连续答题天数」不落本组表：由 reward 服务的签到体系（user_checkin_state.continuous_days）
--      作为唯一来源，答题正确视为当日完成并触发打卡，避免出现两套打卡数据；
--   4) is_daily 区分「当日一题」与「自由练习」：只有当日一题计入榜单与等级分，练习只沉淀统计。

CREATE TABLE `ap_coding_question` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stem` VARCHAR(500) NOT NULL COMMENT '题干',
    `question_type` TINYINT NOT NULL DEFAULT 1 COMMENT '题型：1单选 2多选',
    `options` TEXT NOT NULL COMMENT '选项（JSON数组，元素为选项文本，如 ["A选项","B选项"]）',
    `answer` VARCHAR(50) NOT NULL COMMENT '正确选项下标（JSON数组，如 [0] 或 [0,2]）',
    `explanation` VARCHAR(1000) DEFAULT NULL COMMENT '答案解析',
    `difficulty` TINYINT NOT NULL DEFAULT 1 COMMENT '难度：1入门 2进阶 3挑战',
    `tags` VARCHAR(200) DEFAULT NULL COMMENT '知识点标签（逗号分隔，如 Redis,缓存）',
    `stem_hash` CHAR(32) NOT NULL COMMENT '题干MD5（全局去重）',
    `source_type` TINYINT NOT NULL DEFAULT 0 COMMENT '来源：0平台 1文章AI生成 2作者投稿',
    `source_article_id` BIGINT DEFAULT NULL COMMENT '来源文章ID（做题解析后可跳转阅读）',
    `source_user_id` BIGINT DEFAULT NULL COMMENT '出题人用户ID（投稿场景，用于归属与积分）',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0待审核 1已上架 2已驳回',
    `reject_reason` VARCHAR(200) DEFAULT NULL COMMENT '驳回原因',
    `answer_count` INT NOT NULL DEFAULT 0 COMMENT '累计作答次数（热度参考）',
    `correct_count` INT NOT NULL DEFAULT 0 COMMENT '累计答对次数',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stem_hash` (`stem_hash`),
    KEY `idx_status_difficulty` (`status`, `difficulty`),
    KEY `idx_source_article` (`source_article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日一题题库（Coding 延展第一层）';

CREATE TABLE `ap_coding_answer_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT NOT NULL COMMENT '作答用户ID',
    `question_id` BIGINT NOT NULL COMMENT '题目ID',
    `answer_date` DATE NOT NULL COMMENT '作答日期（按天限次与榜单统计维度）',
    `user_answer` VARCHAR(50) NOT NULL COMMENT '用户答案（JSON数组）',
    `is_correct` TINYINT NOT NULL DEFAULT 0 COMMENT '是否答对 1是 0否',
    `elapsed_seconds` INT DEFAULT NULL COMMENT '作答用时（秒，榜单平局依据）',
    `is_daily` TINYINT NOT NULL DEFAULT 0 COMMENT '是否当日一题 1是 0自由练习',
    `score_awarded` INT NOT NULL DEFAULT 0 COMMENT '本次获得的逐日分',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_date` (`user_id`, `answer_date`),
    KEY `idx_user_question` (`user_id`, `question_id`),
    KEY `idx_daily_date` (`is_daily`, `answer_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日一题作答记录';

CREATE TABLE `ap_coding_user_stat` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT NOT NULL COMMENT '用户ID',
    `total_count` INT NOT NULL DEFAULT 0 COMMENT '每日一题累计作答数',
    `correct_count` INT NOT NULL DEFAULT 0 COMMENT '每日一题累计答对数',
    `practice_count` INT NOT NULL DEFAULT 0 COMMENT '自由练习累计作答数',
    `practice_correct_count` INT NOT NULL DEFAULT 0 COMMENT '自由练习累计答对数',
    `tag_stats` TEXT COMMENT '领域答题分布（JSON：{"Redis":{"total":3,"correct":2}}）',
    `first_answer_date` DATE DEFAULT NULL COMMENT '首次答题日期',
    `last_answer_date` DATE DEFAULT NULL COMMENT '最近答题日期',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户编码统计（连续天数以签到体系为唯一来源，本表不重复存储）';