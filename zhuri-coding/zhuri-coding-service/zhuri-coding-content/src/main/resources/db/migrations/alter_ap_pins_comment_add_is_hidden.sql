-- 沸点评论（ap_pins_comment）：AI 社区治理「折叠隐藏」标记（引战/阴阳/软广等非红线违规，温和处置）。
-- 语义：全局隐藏（对所有人含评论者本人不可见，数据保留可审计），区别于文章评论的"本人可见折叠条"。
ALTER TABLE ap_pins_comment ADD COLUMN is_hidden TINYINT NOT NULL DEFAULT 0 COMMENT '0正常 1已折叠(AI社区治理)';
