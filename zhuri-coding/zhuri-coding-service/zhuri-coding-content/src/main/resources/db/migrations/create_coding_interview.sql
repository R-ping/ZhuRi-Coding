-- 模拟面试（Coding 延展第三层 · Stage A）—— 面试会话单表（提纲 + 对话流水 + 报告）
-- 目标库：MySQL leadnews_article（与 ap_coding_question / ap_coding_assessment 同库）
-- 说明：本脚本仅需在 leadnews_article 库上执行一次，重复执行会因表已存在而报错。
--
-- 设计要点：
--   1) 单表 + JSON 快照（与测评表同构）：题量与文本量可控（≤10 轮对话），回放 = plan_snapshot + turns + report；
--   2) plan_snapshot 存提纲（含 keyPoints 关键考点清单，仅服务端可见）——报告覆盖度判定以此为准，
--      同时避免题库/文章后续变更影响历史回放；
--   3) turns 每轮追加写（单次 UPDATE），会话状态以 DB 为准，不依赖进程内长记忆（重启/多实例安全）；
--   4) turn_count 是 turnSeq 防重基准（update ... where id=? and status=1 and turn_count=? 原子防重）；
--      followup_count 为当前主题已追问次数（服务端强制上限，不依赖模型自觉）。

CREATE TABLE `ap_coding_interview` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT NOT NULL COMMENT '用户ID',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1进行中 2已完成 3已过期',
    `direction` VARCHAR(64) NOT NULL COMMENT '面试方向（技术栈/岗位，如 Java 后端）',
    `difficulty` TINYINT NOT NULL DEFAULT 2 COMMENT '难度：1入门 2进阶 3挑战',
    `plan_snapshot` TEXT NOT NULL COMMENT '面试提纲（JSON数组：[{topic,mainQuestion,keyPoints[],tag}]，含关键考点，仅服务端可见）',
    `turns` TEXT DEFAULT NULL COMMENT '对话流水（JSON数组：[{role:interviewer|user, type:question|followup|answer, content, topicIndex, ts}]）',
    `current_index` INT NOT NULL DEFAULT 0 COMMENT '当前主题下标（从0起）',
    `followup_count` INT NOT NULL DEFAULT 0 COMMENT '当前主题已追问次数（服务端强制上限）',
    `turn_count` INT NOT NULL DEFAULT 0 COMMENT '用户作答轮数（turnSeq 防重基准）',
    `report` TEXT DEFAULT NULL COMMENT '面试报告（JSON：逐题点评+三维等级+覆盖考点+总评建议；结构化失败时存模型原文）',
    `overall_score` INT DEFAULT NULL COMMENT '综合等级（1-5，报告生成后写入）',
    `started_time` DATETIME NOT NULL COMMENT '开面时间',
    `deadline_time` DATETIME NOT NULL COMMENT '截止时间（开面+限时）',
    `last_active_time` DATETIME NOT NULL COMMENT '最近活动时间（每轮刷新）',
    `finished_time` DATETIME DEFAULT NULL COMMENT '结束时间',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_started` (`user_id`, `started_time`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟面试记录（提纲+对话流水+报告，单表 JSON 快照）';