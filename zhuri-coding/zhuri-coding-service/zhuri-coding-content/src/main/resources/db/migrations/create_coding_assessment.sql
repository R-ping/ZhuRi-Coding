-- 能力档案与测评简版（Coding 延展第二层 · Stage A/Stage B 共用）—— 测评记录 / 档案隐私开关
-- 目标库：MySQL leadnews_article（与 ap_coding_question 等第一层表同库）
-- 说明：本脚本仅需在 leadnews_article 库上执行一次，重复执行会因表已存在而报错。
--
-- 设计要点：
--   1) ap_coding_assessment 为单人单卷生命周期单表（进行中→已提交/已过期）：
--      paper_snapshot 存组卷时刻的题目快照（含正确答案，仅服务端可见），判分与成绩单
--      回放都以快照为准 —— 题库题目被编辑/下架不影响历史成绩；
--   2) ap_coding_profile_setting 是能力档案隐私开关（user_id 唯一）：默认无记录 = 全私有；
--      总开关 is_public 关闭时忽略分项开关，打开后可逐项关闭（如隐藏"持续度"避免暴露活跃时间）；
--   3) 档案聚合数据全部来自既有表（ap_coding_user_stat / ap_coding_answer_record /
--      ap_article / ap_collection / reward 签到），本组表只承载"测评"与"隐私设置"两个新概念。

CREATE TABLE `ap_coding_assessment` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT NOT NULL COMMENT '用户ID',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1进行中 2已提交 3已过期',
    `paper_snapshot` TEXT NOT NULL COMMENT '组卷快照（JSON数组：id/stem/type/options/answer/explanation/difficulty/tags）',
    `answers` TEXT DEFAULT NULL COMMENT '作答明细（JSON数组：questionId/userAnswer/correct）',
    `score` INT NOT NULL DEFAULT 0 COMMENT '得分（0-100，correct/total 归一化）',
    `correct_count` INT NOT NULL DEFAULT 0 COMMENT '答对题数',
    `total_count` INT NOT NULL DEFAULT 0 COMMENT '总题数',
    `domain_stats` TEXT DEFAULT NULL COMMENT '领域分布（JSON：{"Redis":{"total":2,"correct":1}}）',
    `percentile` INT DEFAULT NULL COMMENT '百分位（样本不足时为 NULL，展示前需判分值>0）',
    `duration_seconds` INT DEFAULT NULL COMMENT '总用时（秒）',
    `started_time` DATETIME NOT NULL COMMENT '开卷时间',
    `deadline_time` DATETIME NOT NULL COMMENT '截止时间',
    `submitted_time` DATETIME DEFAULT NULL COMMENT '交卷时间',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_time`),
    KEY `idx_status_score` (`status`, `score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编码能力测评记录（组卷快照判分，题库变更不影响历史成绩）';

CREATE TABLE `ap_coding_profile_setting` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT NOT NULL COMMENT '用户ID',
    `is_public` TINYINT NOT NULL DEFAULT 0 COMMENT '总开关：0私有（默认） 1公开',
    `public_domain` TINYINT NOT NULL DEFAULT 1 COMMENT '技术领域分布是否公开',
    `public_streak` TINYINT NOT NULL DEFAULT 1 COMMENT '持续度是否公开',
    `public_output` TINYINT NOT NULL DEFAULT 1 COMMENT '输出能力是否公开',
    `public_solve` TINYINT NOT NULL DEFAULT 0 COMMENT '解决问题（依赖付费问答，预留）',
    `public_assessment` TINYINT NOT NULL DEFAULT 1 COMMENT '测评成绩是否公开',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力档案隐私开关（默认私有，打开后逐项可控）';