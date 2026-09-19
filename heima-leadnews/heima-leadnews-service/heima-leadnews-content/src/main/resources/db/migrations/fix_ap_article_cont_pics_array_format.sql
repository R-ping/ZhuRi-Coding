-- =====================================================
-- 修复 ap_article.cont_pics 存量数据格式：字符串数组 -> 对象数组
-- 库：MySQL leadnews_article（仅执行一次）
--
-- 背景：历史导入（juejin 素材）写入的 cont_pics 为纯字符串数组
--   ["https://.../a.webp", "https://.../b.webp"]
-- 而实体 ApArticle.contPics 期望 JacksonTypeHandler 反序列化的对象数组
--   [{"picUrl":"https://.../a.webp"}, {"picUrl":"https://.../b.webp"}]
-- 类型不匹配导致查询 ap_article（selectList/selectById 全列）时抛
-- MismatchedInputException -> MyBatisSystemException，进而使：
--   1) [AiAsk] 增量向量同步定时任务（含 cont_pics 列的全列查询）每 10 分钟失败刷 ERROR；
--   2) 文章 AI 摘要 /summary 接口 500。
-- 当前写入方仅有 ImageScanProcessor（对象数组），迁移后存量与实体一致、不再复发。
-- 幂等：WHERE 仅命中字符串数组形式（["...开头），已为对象数组的行不受影响。
-- =====================================================
UPDATE ap_article
SET cont_pics = (
    SELECT JSON_ARRAYAGG(JSON_OBJECT('picUri', '', 'picUrl', JSON_UNQUOTE(jt.url)))
    FROM JSON_TABLE(cont_pics, '$[*]' COLUMNS (url VARCHAR(2048) PATH '$')) AS jt
)
WHERE cont_pics IS NOT NULL
  AND cont_pics <> ''
  AND cont_pics LIKE '["%'
  AND JSON_UNQUOTE(JSON_EXTRACT(cont_pics, '$[0]')) LIKE 'http%';