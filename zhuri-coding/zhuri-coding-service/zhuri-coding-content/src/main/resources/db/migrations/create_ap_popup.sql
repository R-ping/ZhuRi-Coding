-- =====================================================
-- 运营位弹窗公告（全站弹窗，登录用户一次关闭、本期内不再弹）
-- 管理端：/api/v1/admin/popups（OPS_CONFIG）　C 端：GET /api/v1/popups/current + POST /{id}/close
-- 时间窗 NOT NULL：弹窗是投放语义（到点出现、到点消失），必须有截止；关闭记录存 Redis，TTL 至 end_time
-- 内容库：leadnews_article，执行一次
-- =====================================================
CREATE TABLE IF NOT EXISTS `ap_popup` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(100)  NOT NULL COMMENT '弹窗标题',
  `content`      VARCHAR(2000) NULL COMMENT '正文纯文本（不收 HTML：弹窗不是富文本场景，收了就要为 XSS 负责）',
  `image_url`    VARCHAR(500)  NULL COMMENT '可选配图',
  `button_text`  VARCHAR(50)   NULL COMMENT '动作按钮文案，NULL=只有关闭',
  `link_url`     VARCHAR(500)  NULL COMMENT '动作按钮跳转（站内路由 / 开头或 http(s) 外链，禁其他协议）',
  `status`       TINYINT       NOT NULL DEFAULT 0 COMMENT '0-停用 1-启用',
  `start_time`   DATETIME      NOT NULL COMMENT '生效开始',
  `end_time`     DATETIME      NOT NULL COMMENT '生效结束（关闭记录的 TTL 上限也取它）',
  `created_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`, `start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营位弹窗公告';
