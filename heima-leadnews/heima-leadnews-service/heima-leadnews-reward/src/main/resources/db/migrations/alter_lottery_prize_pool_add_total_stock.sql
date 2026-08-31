-- 转盘抽奖奖品池：实物奖品新增库存字段，防止实物奖品超发
-- 说明：-1 不限量，0 已售罄，>0 剩余件数；仅 type=3（实物）奖品生效。
-- 执行一次即可，幂等（若提示列已存在请忽略）。
ALTER TABLE `lottery_prize_pool`
  ADD COLUMN `total_stock` int NOT NULL DEFAULT '-1'
  COMMENT '实物奖品总库存(-1=不限量,0=已售罄,>0=剩余件数)'
  AFTER `discount_rate`;