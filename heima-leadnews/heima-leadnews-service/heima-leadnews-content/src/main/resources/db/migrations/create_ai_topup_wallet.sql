-- =====================================================
-- AI 额度包（订阅-配额打通：支付宝沙箱购买 AI 问答次数）
-- ap_ai_wallet：用户 AI 余额（按用户单行，消费优先于每日免费）
-- ap_ai_topup_order：额度包充值订单（out_trade_no 前缀 "ai"，回调按前缀分发）
-- 内容库：leadnews_article，执行一次
-- =====================================================
CREATE TABLE IF NOT EXISTS `ap_ai_wallet` (
  `user_id`     INT      NOT NULL COMMENT '用户ID',
  `balance`     INT      NOT NULL DEFAULT 0 COMMENT 'AI 余额（次），负值禁止',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 额度钱包';

CREATE TABLE IF NOT EXISTS `ap_ai_topup_order` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `order_no`     VARCHAR(32)  NOT NULL COMMENT '业务订单号（ai+雪花，作为支付宝 out_trade_no）',
  `user_id`      INT          NOT NULL COMMENT '购买用户',
  `package_code` VARCHAR(16)  NOT NULL COMMENT '额度包：q200/q1000/q5000',
  `amount_fen`   INT          NOT NULL COMMENT '实付金额（分），回调按此校验防篡改',
  `quota_added`  INT          NOT NULL COMMENT '到账次数',
  `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待支付 1-支付成功(已入账) 2-已关闭',
  `pay_trade_no` VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '支付宝交易号',
  `pay_time`     DATETIME     DEFAULT NULL COMMENT '支付时间',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 额度包充值订单';
