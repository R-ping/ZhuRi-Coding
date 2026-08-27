-- =============================================================
-- 课程订单支持抽奖获得的通用5折券（reward 侧 user_virtual_assets 持有）
-- 数据库：leadnews_article
-- 新增 coupon_item_code 列：记录本单使用的虚拟道具代码（如 course50），供支付成功后核销
-- =============================================================
ALTER TABLE `ap_course_order`
  ADD COLUMN `coupon_item_code` varchar(32) NOT NULL DEFAULT '' COMMENT '使用的通用5折券道具代码（空=未使用）' AFTER `discount_code`;