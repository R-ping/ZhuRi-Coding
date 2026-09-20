-- ======================================================================
-- 课程订单表：新增退款兜底字段
-- 数据库：leadnews_article（内容库）
-- 用途：
--   记录「支付成功但订单已关闭（并发窗口/超时导致 CANCELLED）」触发的自动退款信息，
--   与 OrderServiceImpl#markRefunded 配套，用于审计与对账。
-- ======================================================================
ALTER TABLE `ap_course_order`
  ADD COLUMN `refund_trade_no` varchar(64) DEFAULT NULL COMMENT '退款交易号（支付宝退款关联交易号，未配置凭据时为模拟流水号）' AFTER `trade_no`,
  ADD COLUMN `refund_time`    datetime     DEFAULT NULL COMMENT '退款时间（自动退款兜底成功时间）' AFTER `refund_trade_no`,
  ADD COLUMN `refund_pending` tinyint      NOT NULL DEFAULT 0  COMMENT '退款重试状态：0无 1待重试 2已告警停止重试（需人工介入）' AFTER `refund_time`,
  ADD COLUMN `refund_retry_count` int NOT NULL DEFAULT 0 COMMENT '退款失败已重试次数' AFTER `refund_pending`;