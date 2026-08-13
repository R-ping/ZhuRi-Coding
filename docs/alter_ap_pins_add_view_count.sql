-- 为沸点表 ap_pins 增加浏览量字段，用于话题详情页聚合浏览数
ALTER TABLE ap_pins ADD COLUMN view_count INT NOT NULL DEFAULT 0 COMMENT '浏览量' AFTER share_count;
