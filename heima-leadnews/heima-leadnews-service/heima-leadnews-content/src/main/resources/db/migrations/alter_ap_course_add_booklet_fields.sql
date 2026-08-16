-- 新增小册申报相关字段
ALTER TABLE `ap_course`
  ADD COLUMN `apply_reason` VARCHAR(500) DEFAULT '' COMMENT '申报审核拒绝原因',
  ADD COLUMN `apply_time` DATETIME DEFAULT NULL COMMENT '申报提交时间',
  ADD COLUMN `review_time` DATETIME DEFAULT NULL COMMENT '编辑审核时间';
