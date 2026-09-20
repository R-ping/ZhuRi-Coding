-- =====================================================================
-- article_event 单 status 状态机改造：存量数据折算（一次性脚本，幂等）
--
-- 背景：重构前 article_event 用 es_status/pub_status 双状态位跟踪
-- "ES 同步 / DB 发布态"两个目标；重构后统一为单列 status 状态机：
--   1=INIT(待处理) 2=DB_SET_FAIL(置发布态失败) 3=ES_SYNC_FAIL(ES同步失败) 4=DONE(完成)
-- 该脚本仅处理"旧双位语义残留"（es_status/pub_status 非 0）的行；
-- 已按新状态机写入的行（status IN (2,3,4)）不受影响。
--
-- 折算规则：
--   1) es_status=2 且 pub_status=2（重构前已完成）           → 直接删除
--   2) pub_status=1（重构前发布动作失败，含 DB/ES 双写失败） → status=2，
--      扫描将先补 DB 置位（幂等：已是 PUBLISHED 则直接转 ES 同步）
--   3) 其余 es_status=1（ES 同步失败，发布已完成）           → status=3
--   4) 其余（es_status=0 的 INIT/中断行）保留 status=1，
--      滞留超 60s 后由扫描整段重放（置位+同步），无需显式转换
--   5) ES 同步重试上限统一放宽为 5（原 DB 默认 2 过紧）
-- 执行方式：mysql -h127.0.0.1 -uroot -p leadnews_article < 本文件
-- =====================================================================

-- 1. 已完成行直接删除
DELETE FROM article_event WHERE status NOT IN (2,3,4) AND es_status = 2 AND pub_status = 2;

-- 2. 发布动作失败（含 DB/ES 双失败）→ 置位失败态
UPDATE article_event
SET status = 2, update_time = NOW()
WHERE status NOT IN (2,3,4) AND pub_status = 1;

-- 3. 其余 ES 同步失败 → ES 失败态（排除第 2 步已置位为 2 的行）
UPDATE article_event
SET status = 3, update_time = NOW()
WHERE status NOT IN (2,3,4) AND es_status = 1 AND pub_status <> 1;

-- 4. 放宽 ES 同步重试上限（新代码 INSERT 默认 5，存量行兜底对齐）
UPDATE article_event SET max_retry_count = 5 WHERE status IN (1,2,3) AND max_retry_count < 5;
