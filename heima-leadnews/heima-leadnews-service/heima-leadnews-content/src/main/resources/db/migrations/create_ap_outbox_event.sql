-- ============================================================================
-- 方案 B：Transactional Outbox 本地消息表（分支 fix/order-discount-outbox）
--
-- 目标：把「支付成功后的经验/站内信联动」等非资金副作用从 try-catch+log.error
--       升级为「主事务同表写入事件 → 定时分发执行 → 失败指数退避重试 → 超限死信」。
--       主事务回滚时事件一起回滚（Outbox 核心保证：业务与消息原子提交）。
--
-- 状态机：0=PENDING（待分发）→ 3=PROCESSING（执行中，CAS 抢占）
--        → 1=DONE（成功） / 回 PENDING（失败，retry_count++，next_retry_at 指数退避）
--        → 2=DEAD（重试超限，需人工介入）
-- 幂等：uk_event_key 唯一键保证同一业务事件只写一次（重复写入被捕获忽略）。
-- 多实例安全：Dispatcher 用单条 CAS（WHERE status=PENDING）抢占，无需分布式锁；
--            PROCESSING 卡死（执行中崩溃）由 updated_time 超时回收重新分发。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `ap_outbox_event` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_key`     VARCHAR(64)  NOT NULL COMMENT '业务幂等键（如 PAY_REWARD:{orderNo}），同一事件只写一次',
  `event_type`    VARCHAR(64)  NOT NULL COMMENT '事件类型 = OutboxHandler 路由键',
  `payload`       VARCHAR(2000) NOT NULL COMMENT 'JSON 载荷，由对应 Handler 反序列化执行',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=DONE 2=DEAD 3=PROCESSING',
  `retry_count`   INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `max_retries`   INT          NOT NULL DEFAULT 5 COMMENT '重试上限，达到即置 DEAD',
  `next_retry_at` DATETIME     NULL COMMENT '下次重试时间（指数退避）；PENDING 且到期才被分发',
  `last_error`    VARCHAR(500) NULL COMMENT '最近一次失败原因（截断到 500 字符）',
  `created_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（PROCESSING 超时回收依据）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_key` (`event_key`),
  KEY `idx_status_next_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表（Transactional Outbox，支付联动等异步副作用）';
