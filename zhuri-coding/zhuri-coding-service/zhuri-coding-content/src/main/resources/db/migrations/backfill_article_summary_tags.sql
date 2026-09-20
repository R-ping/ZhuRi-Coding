-- =====================================================================
-- 历史数据回填：已发布文章缺少 summary 与 tags
-- 背景：早期发布流程未持久化标签(labels 字段与后端 tags 字段不一致)，
--       且发布后草稿被删除，导致历史文章既无摘要也无标签。
-- 方案：
--   1) summary：从 ap_article_content 正文提取前 150 字可见文案。
--   2) tags   ：按文章主题与 sys_tags 现有标签（leadnews_user 库）匹配回填，
--               保证标签在 feed 展示且可点击进入标签详情页。
-- 说明：仅影响历史测试/数据回填，新发布文章已由编辑器直接写入 tags JSON 数组。
-- =====================================================================

-- 1) 回填 summary（正文前 150 字，去掉换行与 Markdown 井号标题）
UPDATE `ap_article` a
JOIN `ap_article_content` c ON c.article_id = a.id
SET a.summary = REPLACE(REPLACE(LEFT(c.content, 150), CHAR(10), ' '), '#', '')
WHERE a.status = 9 AND (a.summary IS NULL OR a.summary = '');

-- 2) 回填 tags（JSON 数组），按文章 id 逐条匹配 sys_tags 中的标签名
UPDATE `ap_article` SET tags = JSON_ARRAY('Java')       WHERE id = 2086403442600767490; -- 稀土掘金签到系统全栈架构（一）
UPDATE `ap_article` SET tags = JSON_ARRAY('NLP')        WHERE id = 2086449569626734593; -- Prompt 注入攻击模式
UPDATE `ap_article` SET tags = JSON_ARRAY('Java')       WHERE id = 2086451750132137985; -- 稀土掘金签到系统全栈架构（三）
UPDATE `ap_article` SET tags = JSON_ARRAY('JavaScript') WHERE id = 2086482486151290882; -- 掘友分明细页分析
UPDATE `ap_article` SET tags = JSON_ARRAY('Python')     WHERE id = 2086737443580424194; -- 自动化测试文章 2026-08-10
UPDATE `ap_article` SET tags = JSON_ARRAY('Python')     WHERE id = 2086748198488911873; -- 浏览器自动化测试
UPDATE `ap_article` SET tags = JSON_ARRAY('Python')     WHERE id = 2086893096533925890; -- 浏览器自动化测试 2026/8/11
UPDATE `ap_article` SET tags = JSON_ARRAY('Java')       WHERE id = 2087071468589342722; -- 分布式系统架构设计与实践
UPDATE `ap_article` SET tags = JSON_ARRAY('Vue')        WHERE id = 2087071668418568194; -- 简单前端测试文章