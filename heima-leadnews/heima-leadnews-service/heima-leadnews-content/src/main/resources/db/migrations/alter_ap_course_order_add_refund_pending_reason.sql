-- ============================================================================
-- P0-5 退款待重试原因字段（fix/order-discount-outbox 分支）
--
-- 背景：OrderServiceImpl.handlePaySuccess 在「已 PAID + 券核销失败」场景下
--       只 log.warn 不抛异常（catch 吞掉），导致用户享受折扣但券未扣，资损。
-- 修复：把「已 PAID 但券核销失败」视为「需退款」，走现有 RefundRetryTask 兜底。
--       在 ap_course_order 上加 refund_pending_reason 字段记录原因（便于运维
--       直接 SQL 排查，避免只能去 ELK 翻日志）。
-- 兼容性：VARCHAR(100) NULL —— 老订单该字段为 NULL，行为与改造前一致。
-- ============================================================================

ALTER TABLE `ap_course_order`
  ADD COLUMN `refund_pending_reason` VARCHAR(100) NULL COMMENT '退款待重试原因（order_closed / discount_code_exhausted / coupon_consume_failed）' AFTER `refund_pending`;
