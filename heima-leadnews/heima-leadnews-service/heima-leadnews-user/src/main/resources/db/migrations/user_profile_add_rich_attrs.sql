-- 数据模型对齐：user_profile 补齐掘金式个人资料字段 region/education/skills/level
-- 说明：skills 存 JSON 数组字符串，如 '["Java","Spring"]'
-- 执行库：leadnews_user

ALTER TABLE `user_profile`
    ADD COLUMN `region`    VARCHAR(50)  NULL COMMENT '地区，对齐掘金 city' AFTER `bio`,
    ADD COLUMN `education` VARCHAR(100) NULL COMMENT '学历/学校，对齐掘金 education' AFTER `region`,
    ADD COLUMN `skills`    VARCHAR(500) NULL COMMENT '技术标签（JSON 数组字符串），对齐掘金 skills' AFTER `education`,
    ADD COLUMN `level`     VARCHAR(20)  NULL COMMENT '等级快照，对齐掘金 level（逐力值/逐友）' AFTER `skills`;