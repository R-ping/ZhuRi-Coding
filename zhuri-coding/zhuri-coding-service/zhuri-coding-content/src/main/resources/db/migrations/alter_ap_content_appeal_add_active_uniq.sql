-- =====================================================
-- 申诉并发去重：给「同一内容同一时间只能有一条待审申诉」加数据库级约束
--
-- 背景：应用层是「先 selectCount 再 insert」两段判断，两个并发请求都能通过预查，
--       于是同一内容会挂两条待审申诉，运营会重复处理、AI 预审也要跑两次。
--
-- 做法：生成列 active_uniq —— 只在 status=0（待人工终审）时有值，终态（1/2）为 NULL。
--       MySQL 唯一索引允许多个 NULL，所以「处理完可以再次申诉」的语义不受影响，
--       而并发插入待审申诉时第二条会撞 uk_active_uniq，由应用层转成友好提示。
--
-- 内容库：leadnews_article，执行一次
-- =====================================================
USE `leadnews_article`;

ALTER TABLE `ap_content_appeal`
  ADD COLUMN `active_uniq` VARCHAR(64)
      GENERATED ALWAYS AS (IF(`status` = 0, CONCAT(`appeal_type`, ':', `content_id`), NULL)) STORED
      COMMENT '待审唯一键：status=0 时=type:contentId，终态为 NULL（允许后续再次申诉）',
  ADD UNIQUE KEY `uk_active_uniq` (`active_uniq`);
