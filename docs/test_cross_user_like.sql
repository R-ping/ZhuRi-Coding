-- 跨用户点赞测试：用户B(zhangsan, id=1) 点赞 用户A(752311, id=442462209) 的沸点
START TRANSACTION;

-- 1. 插入点赞记录
INSERT INTO leadnews_article.ap_pins_like (pins_id, user_id, created_time) 
VALUES (2086899943651319810, 1, NOW());

-- 2. 更新沸点的点赞数
UPDATE leadnews_article.ap_pins SET like_count = like_count + 1 WHERE id = 2086899943651319810;

-- 3. 插入行为记录
INSERT INTO leadnews_article.user_behavior_record (user_id, behavior_type, target_type, target_id, target_user_id, status, created_time, updated_time) 
VALUES (1, 'like_pin', 2, 2086899943651319810, 442462209, 1, NOW(), NOW());

-- 4. 插入站内信通知：通知用户A(442462209) 有人点赞了他的沸点
INSERT INTO leadnews_notification.notifications (user_id, type, source_id, content, is_read, created_at)
VALUES (442462209, 4, '2086899943651319810', '{"notification_type":"interaction","trigger_user":{"id":"1","name":"zhangsan","avatar":"https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_1.png"},"action_type":"like_pin","source_type":"pins","target_id":"2086899943651319810"}', 0, NOW());

SELECT '点赞测试 - 跨用户通知已插入' AS result;

COMMIT;