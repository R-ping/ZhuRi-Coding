-- ============================================================================
-- AI 额度包：次数 → tokens 配额（双轨并存，向后兼容）
--
-- 背景：原额度包按"次数"售卖（q200=200 次），但不同功能 token 成本差 10 倍以上
--       （rerank 短 prompt vs 创作复盘长输出），按次数计费对用户不公平、对平台不可控。
-- 改造：新增 token 维度余额，次数余额保留（兼容存量订单与老前端展示），
--       实际用量按 token 结算（每日免费 tokens + 已购 tokens）。
--
-- 兼容性：新增列均带默认值 0，存量行不受影响（存量次数仍有效，只是不再增长）。
-- ============================================================================

ALTER TABLE `ap_ai_wallet`
  ADD COLUMN `token_balance` BIGINT NOT NULL DEFAULT 0 COMMENT '已购 AI token 余额（用量按 token 结算）';

ALTER TABLE `ap_ai_topup_order`
  ADD COLUMN `token_added` BIGINT NOT NULL DEFAULT 0 COMMENT '本单到账 token 额度';
