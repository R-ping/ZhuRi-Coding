-- 文章评论（ap_comment）：AI 社区治理「折叠隐藏」标记（引战/阴阳/软广等非红线违规，温和处置）。
-- 注：沸点评论独立表 ap_pins_comment 暂不参与该治理，如需覆盖需另行加列。
ALTER TABLE ap_comment ADD COLUMN is_hidden TINYINT NOT NULL DEFAULT 0 COMMENT '0正常 1已折叠(AI社区治理)';
