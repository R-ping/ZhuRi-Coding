-- =====================================================
-- 交互行为表唯一索引（并发幂等加固）
-- 防止"先查后插"竞态导致同一用户重复收藏/关注
-- 执行一次即可；执行前已确认无历史重复数据
--
-- 说明：ap_browse_history 因含软删除(is_deleted)同文章多条记录，
-- 且阅读计数已有 selectCount 先查去重，暂不加唯一索引。
-- =====================================================

-- 收藏表：同一用户对同一文章仅一条收藏记录
ALTER TABLE `ap_collection`
  ADD UNIQUE INDEX `uk_collection_user_article` (`user_id`, `article_id`);

-- 关注表：同一用户对同一作者仅一条关注记录（follow_user_id 为被关注作者ID）
ALTER TABLE `ap_user_follow`
  ADD UNIQUE INDEX `uk_follow_user_target` (`user_id`, `follow_user_id`);