-- ============================================================================
-- 为 ap_outbox_event 增加 (event_type, created_time) 索引
--
-- 【背景】新增的「生命周期护栏」（OutboxDispatcher.enforceLifetime）按
--     WHERE event_type = ? AND status NOT IN (DONE, DEAD) AND created_time < ?
-- 清理超时未完成的事件。而现有索引只有：
--     uk_event_key          (event_key)
--     idx_status_next_retry (status, next_retry_at)
-- 两者都不覆盖 event_type / created_time，该查询会退化为全表扫描。
--
-- 护栏每 5 秒执行一轮（每个声明了 maxLifetimeMinutes 的 Handler 一条 UPDATE），
-- 所以这个索引不是"将来再做"的优化，而是功能接入即需要 —— 否则一个本该
-- 保护链路的机制会因为自身开销过大而变成负担。
--
-- 执行方式：mysql -h127.0.0.1 -uroot -p leadnews_article < 本文件
-- ============================================================================

ALTER TABLE `ap_outbox_event`
  ADD INDEX `idx_type_created` (`event_type`, `created_time`);

-- 核对：SHOW INDEX FROM `ap_outbox_event`;
