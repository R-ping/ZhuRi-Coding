-- 小册站：小节提交审核留言（作者提交小节审核时可附留言，如"重新定义了xxx/对内容重新排版"）
-- 小节 status 语义扩展为：0草稿 / 1已发布 / 2审核中
ALTER TABLE `ap_course_chapter`
  ADD COLUMN `review_note` VARCHAR(500) DEFAULT '' COMMENT '作者提交审核留言' AFTER `comment_count`;