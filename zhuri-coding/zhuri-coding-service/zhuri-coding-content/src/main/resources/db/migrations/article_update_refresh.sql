-- 内容时效更新回流（F3）—— 候选池查询索引
-- 目标库：MySQL leadnews_article（与 ap_article 同库）
-- 说明：本脚本仅需在 leadnews_article 库上执行一次，重复执行会因索引已存在而报错。
--       依赖前置脚本 article_revision_add_fields.sql 已为 ap_article 新增 update_time 列。
--
-- 背景：F3 把推荐候选池的过滤从"仅 publish_time 在窗口内"扩为"publish_time 或 update_time 在窗口内"，
--       即发布超窗但近期被实质更新的文章重新获得候选资格。既有索引 idx_status_publish / idx_status_channel_publish
--       只覆盖 status + publish_time，无法支撑新增的 update_time 时间条件，需补一条组合索引。

-- 更新回流候选查询按（status=9, update_time）过滤；与已有 idx_status_publish 对应，保证"最近被更新"的查询走索引
ALTER TABLE `ap_article`
    ADD INDEX `idx_status_update` (`status`, `update_time`);