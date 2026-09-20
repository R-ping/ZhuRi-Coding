-- 清理 ap_behavior_config 中与掘金"升级行为"不一致的错误配置
-- 原因：以下 10 条动作在掘金成长等级页不存在（本地多余项 / 被动接收型无独立配置语义），
--      且 daily_login/article_read 等在代码中仅有常量定义、无生产调用点（死任务），
--      导致成长等级页展示出"不存在的动作行为"且分组混乱。
-- 清理后保留 13 条与掘金对齐的主动行为配置。
-- 执行日期：2026-09-05

DELETE FROM ap_behavior_config WHERE action_code IN (
    'attend_activity',       -- 参与平台活动（本地多余）
    'study_course',          -- 学习创作课程（本地多余）
    'daily_login',           -- 移动端每日登录（掘金无此任务，且无生产调用点）
    'original_article',      -- 发布原创文章（"内容创作"分组在掘金无）
    'article_read',          -- 文章被阅读（被动行为，非升级行为）
    'article_like',          -- 文章被点赞（被动行为）
    'article_comment',       -- 文章被评论（被动行为）
    'article_share',         -- 文章被分享（被动行为）
    'follower_increase',     -- 粉丝增长（被动行为）
    'article_selected'       -- 文章被加精（被动行为，掘金另页展示）
);
