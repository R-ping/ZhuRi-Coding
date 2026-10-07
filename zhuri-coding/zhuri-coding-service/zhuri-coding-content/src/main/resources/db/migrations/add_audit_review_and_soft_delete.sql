-- ============================================================================
-- 审核复核二期：评论/沸点评论软删改造 + 审核任务表复核列
--
-- 【为什么改造】
-- 此前评论（ap_comment）与沸点评论（ap_pins_comment）的违规处置是物理删除
-- （审核责任链 handleFailed 里 deleteById），删了就找不回来 ——
-- AI 高置信度误判没有任何人工捞回通道。本脚本把处置改成软删除（is_deleted=1），
-- 内容行保留，复核"放行"就是把标记翻回来。
--
-- 【为什么不补 root_id/parent_id 快照列】
-- 曾考虑"物理删除 + 快照重建"方案，那才需要回复关系快照；软删方案下
-- 内容行（含 root_id/parent_id）一直都在，恢复不需要重建，快照列没有必要。
--
-- 【ap_audit_task 加三列】
--   violation_reason：违规原因（调度器在违规终态时统一回填，队列展示用；
--     此前原因只存在于通知与业务表（ap_pins.reason），任务表本身不留痕）。
--   review_status / review_time：人工复核结果。0=未复核（进队列）1=复核放行 2=维持违规。
--     队列 = status=3(违规) AND review_status=0；已有 idx_status_next(status,...) 前缀可服务查询，
--     任务表量小，不加新索引。
--
-- ⚠️ MySQL 没有 ADD COLUMN IF NOT EXISTS，本脚本**不可重复执行**（重跑报 Duplicate，错误无害）。
-- ⚠️ 执行方式（与其它迁移脚本一致）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_article < 本脚本
-- ============================================================================

-- 1) 文章评论：违规软删标记（0=正常 1=违规删除，任何端不可见）
ALTER TABLE `ap_comment`
  ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0
  COMMENT '审核违规软删标记：1=违规删除（对所有人不可见，与 is_hidden 折叠正交；复核放行后回到 0）'
  AFTER `is_hidden`;

-- 2) 沸点评论：同上
ALTER TABLE `ap_pins_comment`
  ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0
  COMMENT '审核违规软删标记：1=违规删除（对所有人不可见，与 is_hidden 折叠正交；复核放行后回到 0）'
  AFTER `is_hidden`;

-- 3) 统一审核任务表：违规原因 + 人工复核结果
ALTER TABLE `ap_audit_task`
  ADD COLUMN `violation_reason` VARCHAR(500) DEFAULT NULL
  COMMENT '违规原因（调度器在违规终态时回填，供复核队列展示）'
  AFTER `status`,
  ADD COLUMN `review_status` TINYINT NOT NULL DEFAULT 0
  COMMENT '人工复核状态：0-未复核（在队列中）1-复核放行（内容已恢复）2-维持违规'
  AFTER `violation_reason`,
  ADD COLUMN `review_time` DATETIME DEFAULT NULL
  COMMENT '人工复核完成时间'
  AFTER `review_status`;
