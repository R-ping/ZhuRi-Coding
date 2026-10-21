-- =====================================================
-- 兑换请求幂等：同一用户同一幂等号只允许成一单
--
-- 背景：兑换会同时扣库存与矿石，前端连点或客户端超时重试会把同一动作做两次。
--       原先没有任何幂等键，订单号是服务端生成的，uniq_exchange_id 只防同号重复插入。
--
-- 做法：订单表加 idempotency_key（客户端或网关生成的请求号）。唯一索引
--       uk_user_idem(user_id, idempotency_key) —— MySQL 唯一索引允许多个 NULL，
--       所以没带幂等号的旧客户端行为不变，只有带了号才有强幂等。
--       未带号时由应用层的 3 秒短锁挡连点。
--
-- 从库：leadnews_reward，执行一次
-- =====================================================
USE `leadnews_reward`;

ALTER TABLE `welfare_exchange_orders`
  ADD COLUMN `idempotency_key` VARCHAR(64) DEFAULT NULL COMMENT '请求幂等号（同一用户同一 key 只成一单）',
  ADD UNIQUE KEY `uk_user_idem` (`user_id`, `idempotency_key`);
