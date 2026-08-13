-- 逐日经验值支持小数（打赏/购买按实际支付金额加分，如打赏2.5元加2.5经验）
-- 将逐日分相关字段由 int 升级为 decimal(10,2)，存量整数数据不受影响

ALTER TABLE ap_user_action_log
    MODIFY COLUMN `score_change` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '逐日分变化量';

ALTER TABLE ap_user_level
    MODIFY COLUMN `daily_score` DECIMAL(10,2) DEFAULT 0 COMMENT '逐日分(逐友值)';

ALTER TABLE ap_user_level
    MODIFY COLUMN `daily_score_today` DECIMAL(10,2) DEFAULT 0 COMMENT '今日逐日分(逐友分)获取量';
