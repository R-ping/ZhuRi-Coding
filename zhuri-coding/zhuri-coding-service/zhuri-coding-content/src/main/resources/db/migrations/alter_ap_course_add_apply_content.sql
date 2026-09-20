-- 小册申报内容独立字段，避免与 description（小册介绍）互相覆盖
ALTER TABLE ap_course
  ADD COLUMN apply_content TEXT DEFAULT NULL COMMENT '小册申报内容（JSON：选题/大纲/简介/样章）';
