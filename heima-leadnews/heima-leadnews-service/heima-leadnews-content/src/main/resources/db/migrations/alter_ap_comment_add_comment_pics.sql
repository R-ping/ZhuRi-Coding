-- 文章评论支持附带图片：独立字段存储（不嵌入内容正文），URL 已清洗（去掉 ? 签名参数）
-- 执行库：leadnews_article
-- 日期：2026-08-17

ALTER TABLE `ap_comment`
    ADD COLUMN `comment_pics` varchar(2000) DEFAULT '' COMMENT '评论图片URL列表，逗号分隔' AFTER `content`;