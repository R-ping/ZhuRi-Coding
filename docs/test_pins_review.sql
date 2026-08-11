-- 沸点审核流程测试
-- 测试审核通过和审核拒绝两种场景，验证站内信通知

-- ============================================
-- 场景1：审核通过测试
-- ============================================
-- 1.1 创建一条SUBMIT状态的沸点（用户A: 442462209）
INSERT INTO leadnews_article.ap_pins (user_id, user_name, user_avatar, content, status, like_count, comment_count, share_count, created_time, author_id, author_name, author_image, publish_time, review_time)
VALUES (442462209, '用户752311', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_10.png', '【审核测试】这是一条用于测试审核流程的沸点，初始状态为SUBMIT', 1, 0, 0, 0, NOW(), 442462209, '用户752311', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_10.png', NULL, NULL);

SET @pins_id_1 = LAST_INSERT_ID();

-- 1.2 插入行为记录（发布沸点）
INSERT INTO leadnews_article.user_behavior_record (user_id, behavior_type, target_type, target_id, status, created_time, updated_time)
VALUES (442462209, 'publish_pins', 2, @pins_id_1, 1, NOW(), NOW());

-- 1.3 模拟审核通过：更新状态为PUBLISHED(9)，设置发布时间和审核时间
UPDATE leadnews_article.ap_pins SET status = 9, publish_time = NOW(), review_time = NOW() WHERE id = @pins_id_1;

-- 1.4 插入审核通过通知
SET @notification_content_1 = CONCAT('{"notification_type":"system","pinsId":"', CAST(@pins_id_1 AS CHAR), '","message":"你的沸点已通过审核，已成功发布。","entity_type":"沸点"}');
INSERT INTO leadnews_notification.notifications (user_id, type, source_id, content, is_read, created_at)
VALUES (442462209, 4, CAST(@pins_id_1 AS CHAR), @notification_content_1, 0, NOW());

SELECT CONCAT('场景1-审核通过: 沸点ID=', CAST(@pins_id_1 AS CHAR), ', 通知已发送') AS result;

-- ============================================
-- 场景2：审核拒绝测试
-- ============================================
-- 2.1 创建一条SUBMIT状态的沸点（用户A: 442462209）
INSERT INTO leadnews_article.ap_pins (user_id, user_name, user_avatar, content, status, like_count, comment_count, share_count, created_time, author_id, author_name, author_image, publish_time, review_time)
VALUES (442462209, '用户752311', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_10.png', '【审核测试】这是一条会被审核拒绝的沸点，包含违规内容', 1, 0, 0, 0, NOW(), 442462209, '用户752311', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_10.png', NULL, NULL);

SET @pins_id_2 = LAST_INSERT_ID();

-- 2.2 模拟审核拒绝：更新状态为FAIL(2)，填写拒绝原因
UPDATE leadnews_article.ap_pins SET status = 2, reason = '内容包含违规信息，不符合社区规范', review_time = NOW() WHERE id = @pins_id_2;

-- 2.3 插入审核拒绝通知
SET @notification_content_2 = CONCAT('{"notification_type":"system","pinsId":"', CAST(@pins_id_2 AS CHAR), '","message":"你的沸点审核未通过，原因：内容包含违规信息，不符合社区规范","entity_type":"沸点"}');
INSERT INTO leadnews_notification.notifications (user_id, type, source_id, content, is_read, created_at)
VALUES (442462209, 4, CAST(@pins_id_2 AS CHAR), @notification_content_2, 0, NOW());

SELECT CONCAT('场景2-审核拒绝: 沸点ID=', CAST(@pins_id_2 AS CHAR), ', 通知已发送') AS result;

-- ============================================
-- 场景3：用户B评论用户A的沸点（跨用户通知）
-- ============================================
-- 3.1 插入评论记录
INSERT INTO leadnews_article.ap_pins_comment (pins_id, user_id, user_name, user_avatar, content, like_count, reply_count, created_time)
VALUES (2086899943651319810, 1, 'zhangsan', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_1.png', '这是一条来自用户B的评论测试', 0, 0, NOW());

SET @comment_id = LAST_INSERT_ID();

-- 3.2 更新沸点评论数
UPDATE leadnews_article.ap_pins SET comment_count = comment_count + 1 WHERE id = 2086899943651319810;

-- 3.3 插入行为记录
INSERT INTO leadnews_article.user_behavior_record (user_id, behavior_type, target_type, target_id, target_user_id, status, created_time, updated_time)
VALUES (1, 'comment_pin', 2, 2086899943651319810, 442462209, 1, NOW(), NOW());

-- 3.4 插入站内信通知：通知用户A有人评论了他的沸点
SET @comment_notification = CONCAT('{"notification_type":"interaction","trigger_user":{"id":"1","name":"zhangsan","avatar":"https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_1.png"},"action_type":"comment_pin","source_type":"pins","target_id":"2086899943651319810","comment_id":"', CAST(@comment_id AS CHAR), '","comment_content":"这是一条来自用户B的评论测试"}');
INSERT INTO leadnews_notification.notifications (user_id, type, source_id, content, is_read, created_at)
VALUES (442462209, 4, '2086899943651319810', @comment_notification, 0, NOW());

SELECT CONCAT('场景3-跨用户评论: 评论ID=', CAST(@comment_id AS CHAR), ', 通知已发送') AS result;

SELECT '所有沸点审核和跨用户通知测试完成' AS final_result;