-- 内容时效更新提醒（F5）—— 收藏者批量查询索引
-- 目标库：MySQL leadnews_article（与 ap_collection 同库）
-- 说明：本脚本仅需在 leadnews_article 库上执行一次，重复执行会因索引已存在而报错。
--
-- 背景：文章被实质更新后，需要向"收藏该文章"的用户分批投递站内信，
--       查询形如 where article_id = ? order by created_time desc limit ?, ?。
--       ap_collection 现有索引 uk_collection_user_article(user_id, article_id) 与
--       idx_user_type(entry_id, article_id) 均不以 article_id 打头，无法支撑该分页查询
--       （会退化为全表扫描 + filesort），故补一条 (article_id, created_time) 组合索引。

ALTER TABLE `ap_collection`
    ADD INDEX `idx_article_created` (`article_id`, `created_time`);