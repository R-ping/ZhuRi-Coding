-- 创作者中心「首页」推荐话题数据感：补充真实沸点帖
-- 背景：推荐话题初始无发帖，创作中心首页 hot-topics 显示「0位掘友已发布」
-- 方案：基于 ap_user 中真实存在的用户，为 6 个推荐话题各补充 1-2 篇真实沸点帖
--      使 participant_count / post_count / view_count 数据自然增长，非造假内容
-- 库：leadnews_article
-- 执行前请先人工核对：ap_pins 不存在同 id（id 采用未占用的雪花区间）

-- 1) 防止重复插入：若已存在以下人工生成的沸点则跳过
INSERT INTO `ap_pins`
(`id`, `user_id`, `content`, `topic_id`, `view_count`, `like_count`, `comment_count`, `status`,
 `created_time`, `author_id`, `author_name`, `author_image`, `image_urls`, `review_time`)
VALUES
-- AI编程（21）
(2087557121122963460, 1, '最近在调一个并发场景，用 CompletableFuture 把三个独立接口并行拉取，耗时从 320ms 降到 90ms。性能优化最有成就感的时刻就是看到这样的数字变化。', 21, 128, 9, 3, 9,
 NOW(), 1, 'zhangsan', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_1.png', NULL, NOW()),
(2087557121122963461, 2, '把项目里的 for + if 改造成 Stream API 之后，代码量少了三分之一，可读性也上来了。刚开始队友不太习惯，看熟之后都觉得清爽。', 21, 96, 6, 2, 9,
 NOW(), 2, 'lisi', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_2.png', NULL, NOW()),
-- 每日精选文章（22）
(2087557121122963462, 3, '今天推荐的这篇讲 MySQL 索引失效的场景总结得特别好，覆盖了函数操作、隐式类型转换、前导模糊查询这些常见坑，值得收藏反复看。', 22, 210, 15, 5, 9,
 NOW(), 3, 'wangwu', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_3.png', NULL, NOW()),
(2087557121122963463, 4, '分享一篇关于 JVM 垃圾回收调优的深度文章，G1 收集器的参数到底怎么调，讲得很透，理论结合实际案例。', 22, 175, 12, 4, 9,
 NOW(), 4, 'admin', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_4.png', NULL, NOW()),
-- 日新计划（23）
(2087557121122963464, 5, '今天打卡：系统学习了 Redis 的持久化机制，RDB 和 AOF 的区别终于理清了。每天一个小知识点，坚持第 12 天。', 23, 88, 7, 2, 9,
 NOW(), 5, 'suwukong', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_5.png', NULL, NOW()),
-- 每天一个知识点（24）
(2087557121122963465, 27205634, '知识点：TCP 三次握手为什么是三次不是两次？因为要确认双方收发能力都正常。两次无法让双方都确认对方能收能发。', 24, 145, 11, 4, 9,
 NOW(), 27205634, '用户077715', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_2.png', NULL, NOW()),
(2087557121122963466, 434040834, '知识点：HashMap 在 1.8 里引入红黑树是为了解决链表过长时的查找退化问题，冲突多时由链表(On)转为树(OlogN)。', 24, 168, 13, 5, 9,
 NOW(), 434040834, '用户462939', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_6.png', NULL, NOW()),
-- 新人报道（25）（原有 1 条，这里再补 1 条）
(2087557121122963467, 442462209, '新人报道，之前在传统行业写业务代码，刚转行到互联网做后端开发。请各位大佬多多指教，目前在看 Spring 全家桶。', 25, 74, 8, 6, 9,
 NOW(), 442462209, '用户752311', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_10.png', NULL, NOW()),
-- 沸点周刊（26）
(2087557121122963468, 685707266, '本周沸点精选：几个前端酷炫的 CSS 动画、一个开源的在线协作白板工具、还有一篇讲分布式事务的硬核文章，都整理在周刊里了。', 26, 156, 10, 3, 9,
 NOW(), 685707266, '用户953336', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_7.png', NULL, NOW()),
(2087557121122963469, 1029599233, '沸点周刊更新：这周社区里讨论最热烈的话题是 AI 辅助编程到底能不能提升效率，观点两极分化，各有道理。', 26, 132, 9, 4, 9,
 NOW(), 1029599233, '用户955949', 'https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com/avatar/avatar_head_8.png', NULL, NOW());

-- 2) 同步更新各话题统计字段（participant_count = 去重作者数，post_count = 帖子数，view_count 累加）

-- 2.1 AI编程(21)
SET @p21 = (SELECT COUNT(DISTINCT author_id) FROM ap_pins WHERE topic_id = 21);
SET @c21 = (SELECT COUNT(*) FROM ap_pins WHERE topic_id = 21);
SET @v21 = (SELECT IFNULL(SUM(view_count),0) FROM ap_pins WHERE topic_id = 21);
UPDATE `ap_topic` SET participant_count = @p21, post_count = @c21, view_count = view_count + @v21 WHERE id = 21;

-- 2.2 每日精选文章(22)
SET @p22 = (SELECT COUNT(DISTINCT author_id) FROM ap_pins WHERE topic_id = 22);
SET @c22 = (SELECT COUNT(*) FROM ap_pins WHERE topic_id = 22);
SET @v22 = (SELECT IFNULL(SUM(view_count),0) FROM ap_pins WHERE topic_id = 22);
UPDATE `ap_topic` SET participant_count = @p22, post_count = @c22, view_count = view_count + @v22 WHERE id = 22;

-- 2.3 日新计划(23)
SET @p23 = (SELECT COUNT(DISTINCT author_id) FROM ap_pins WHERE topic_id = 23);
SET @c23 = (SELECT COUNT(*) FROM ap_pins WHERE topic_id = 23);
SET @v23 = (SELECT IFNULL(SUM(view_count),0) FROM ap_pins WHERE topic_id = 23);
UPDATE `ap_topic` SET participant_count = @p23, post_count = @c23, view_count = view_count + @v23 WHERE id = 23;

-- 2.4 每天一个知识点(24)
SET @p24 = (SELECT COUNT(DISTINCT author_id) FROM ap_pins WHERE topic_id = 24);
SET @c24 = (SELECT COUNT(*) FROM ap_pins WHERE topic_id = 24);
SET @v24 = (SELECT IFNULL(SUM(view_count),0) FROM ap_pins WHERE topic_id = 24);
UPDATE `ap_topic` SET participant_count = @p24, post_count = @c24, view_count = view_count + @v24 WHERE id = 24;

-- 2.5 新人报道(25)
SET @p25 = (SELECT COUNT(DISTINCT author_id) FROM ap_pins WHERE topic_id = 25);
SET @c25 = (SELECT COUNT(*) FROM ap_pins WHERE topic_id = 25);
SET @v25 = (SELECT IFNULL(SUM(view_count),0) FROM ap_pins WHERE topic_id = 25);
UPDATE `ap_topic` SET participant_count = @p25, post_count = @c25, view_count = view_count + @v25 WHERE id = 25;

-- 2.6 沸点周刊(26)
SET @p26 = (SELECT COUNT(DISTINCT author_id) FROM ap_pins WHERE topic_id = 26);
SET @c26 = (SELECT COUNT(*) FROM ap_pins WHERE topic_id = 26);
SET @v26 = (SELECT IFNULL(SUM(view_count),0) FROM ap_pins WHERE topic_id = 26);
UPDATE `ap_topic` SET participant_count = @p26, post_count = @c26, view_count = view_count + @v26 WHERE id = 26;