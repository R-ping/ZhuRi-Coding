-- 补齐搬运文章缺失的 ap_article_config 行，使已发布文章可进入"最新"分栏
-- 说明：
--   1) 首页"推荐"与"最新"分栏 SQL 均 INNER JOIN ap_article_config，
--      搬运（从稀土掘金导入）的文章原本无 config 行，导致两分栏都不展示。
--   2) 按业务口径：搬运文章不强制进"推荐"分栏，置于"最新"分栏展示即可，
--      故 is_recommend=0；is_down/is_delete=0（未下架/未删除），评论与转发开启。
--   3) 幂等：仅补 insert 缺失行（article_id 唯一），重复执行无副作用。
-- 执行库：leadnews_article

INSERT INTO `ap_article_config`
    (`article_id`, `is_comment`, `is_forward`, `is_down`, `is_delete`, `is_recommend`)
SELECT
    aa.id, 1, 1, 0, 0, 0
FROM `ap_article` aa
LEFT JOIN `ap_article_config` aac ON aa.id = aac.article_id
WHERE aac.article_id IS NULL
  AND aa.status = 9;