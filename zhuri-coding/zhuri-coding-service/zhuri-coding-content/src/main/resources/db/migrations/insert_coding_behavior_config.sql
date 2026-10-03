-- 每日一题 · 等级行为配置（Coding 延展第一层）
-- 目标库：MySQL leadnews_article（ap_behavior_config 所在库）
-- 说明：本脚本可重复执行（ON DUPLICATE KEY UPDATE 幂等）。
--
-- 背景：答题得分要进入逐力值等级体系（与文章、互动共用同一口径），
--       等级体系按 ap_behavior_config.action_code 解析单次分值与每日次数上限，
--       代码侧 LevelScoreConstants.ACTION_SCORE_MAP / DAILY_ACTION_LIMIT 仅作兜底。
-- 口径：答对一次得 3 分；每日一题一天仅一题（答错不给分、答对当日完成），
--       故 daily_limit=1 —— 分值上限天然被"每日一句题"约束，无需额外防刷。

INSERT INTO `ap_behavior_config`
    (`action_code`, `action_name`, `group_type`, `group_sort`, `score`, `daily_limit`,
     `icon_name`, `btn_name`, `web_jump_url`, `sort_order`, `is_active`)
VALUES
    ('answer_question', '每日一题', '社区学习', 3, 3.0, 1,
     'fa-lightbulb-o', '去答题', '/coding', 31, 1)
ON DUPLICATE KEY UPDATE
    `action_name` = VALUES(`action_name`),
    `group_type` = VALUES(`group_type`),
    `group_sort` = VALUES(`group_sort`),
    `score` = VALUES(`score`),
    `daily_limit` = VALUES(`daily_limit`),
    `icon_name` = VALUES(`icon_name`),
    `btn_name` = VALUES(`btn_name`),
    `web_jump_url` = VALUES(`web_jump_url`),
    `sort_order` = VALUES(`sort_order`),
    `is_active` = VALUES(`is_active`);