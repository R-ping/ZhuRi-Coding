-- ============================================================================
-- 清理 article_event 的遗留列：minio_status / es_status / pub_status
--
-- 【背景：一次只做了一半的迁移】
-- article_event 的状态机早已收敛为单 status（见 convert_article_event_single_status.sql
-- 完成了双状态位 → 单 status 的存量数据折算），但那次迁移**只做了数据折算，没有清理冗余结构**。
-- 三列此后仅被 ApArticleEventMapper.xml 里的两条旧 SQL 隐式保留着
-- （实体字段也带着 @Deprecated 注释"保留以兼容存量数据与 SQL，新代码禁止读写"），
-- 属**有意的技术债**，而非遗忘。
--
-- 【本次三处联动清理 —— 缺任何一处都会炸】
--   1) XML ：insertArticleEvent 去掉三列；updateArticleEvent 去掉两个 <if> 分支
--   2) 实体：ArticleEvent 删除 esStatus / pubStatus / minioStatus
--   3) 本脚本：DROP 三列
--
-- ⚠️ 执行顺序要求：**先发布上面的代码改动，再执行本脚本**。
--    否则正在运行的旧代码仍会 INSERT 这三列，DROP 后立刻报 Unknown column，
--    而该 INSERT 位于发布链路（ApArticleServiceImpl 写 article_event），会造成发布失败。
--
-- 【回滚】
--    如需恢复，使用 DROP 前的 dump：docs/db-backup/20260926-article-event-before-drop-columns.sql
--    （docs/ 已被 .gitignore 忽略，仅存本地）
--
-- 执行方式：mysql -h127.0.0.1 -uroot -p leadnews_article < 本文件
-- ============================================================================

ALTER TABLE `article_event`
  DROP COLUMN `minio_status`,
  DROP COLUMN `es_status`,
  DROP COLUMN `pub_status`;

-- 核对：执行后应只剩 id / article_id / status / create_time / update_time /
--       retry_count / max_retry_count / retry_time / parameter
-- SHOW COLUMNS FROM `article_event`;
