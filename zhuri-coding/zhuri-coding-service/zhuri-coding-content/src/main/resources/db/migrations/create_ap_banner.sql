-- =====================================================
-- 运营位 Banner（首页轮播）
-- 管理端：/api/v1/admin/banners（OPS_CONFIG）　C 端：GET /api/v1/banners/list
-- 新建一律默认停用（status=0），启用是独立动作 + 独立理由（与活动 CMS 同一原则）
-- 内容库：leadnews_article，执行一次
-- =====================================================
CREATE TABLE IF NOT EXISTS `ap_banner` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(100) NOT NULL COMMENT '运营备注名（图上不该是唯一信息源，列表靠它辨认）',
  `image_url`    VARCHAR(500) NOT NULL COMMENT '图片地址',
  `link_url`     VARCHAR(500) NOT NULL COMMENT '跳转地址（站内路由 / 开头或 http(s) 外链，禁其他协议）',
  `sort_order`   INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，小的在前',
  `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0-停用 1-启用',
  `start_time`   DATETIME     NULL COMMENT '生效开始，NULL=不限',
  `end_time`     DATETIME     NULL COMMENT '生效结束，NULL=不限（长期挂）',
  `created_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_sort` (`status`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营位Banner(首页轮播)';
