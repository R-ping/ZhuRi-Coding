-- =====================================================
-- 审核记录表支持"通过"状态迁移
-- 原 status 仅定义 2-失败（审核通过不落审计轨迹），
-- 本迁移仅更新字段注释与默认值语义，存量数据不受影响（status=2 仍为失败）。
-- =====================================================
ALTER TABLE `ap_article_audit_record`
  MODIFY COLUMN `reason` varchar(500) NOT NULL DEFAULT '' COMMENT '审核结果说明（失败原因/通过说明）',
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT '2' COMMENT '审核状态: 1-通过 2-失败';
