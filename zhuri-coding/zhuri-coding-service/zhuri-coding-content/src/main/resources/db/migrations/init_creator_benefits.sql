-- ========================================================
-- 创作者等级权益数据库脚本
-- 包含：表结构变更、等级配置、权益配置、行为任务配置
-- ========================================================

-- 1. 为 ap_user_level 表添加逐力值明细字段（如不存在）
ALTER TABLE ap_user_level
    ADD COLUMN action_score INT DEFAULT 0 COMMENT '行为贡献分',
    ADD COLUMN influence_score INT DEFAULT 0 COMMENT '影响力分',
    ADD COLUMN quality_score INT DEFAULT 0 COMMENT '内容质量分',
    ADD COLUMN violation_score INT DEFAULT 0 COMMENT '违规扣分';
ALTER TABLE ap_level_config
    ADD COLUMN is_active TINYINT(1) DEFAULT 1 COMMENT '是否启用';


-- 2. 创建用户逐力值日志表
CREATE TABLE IF NOT EXISTS ap_user_power_log (
    id              BIGINT          NOT NULL COMMENT '主键ID',
    user_id         BIGINT          NOT NULL COMMENT '用户ID',
    record_date     DATE            NOT NULL COMMENT '记录日期',
    action_score    INT             DEFAULT 0 COMMENT '行为贡献分',
    influence_score INT             DEFAULT 0 COMMENT '影响力分',
    quality_score   INT             DEFAULT 0 COMMENT '内容质量分',
    violation_score INT             DEFAULT 0 COMMENT '违规扣分',
    power_value     INT             DEFAULT 0 COMMENT '逐力值',
    power_level     INT             DEFAULT 1 COMMENT '逐力等级',
    created_time    DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_record_date (record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户逐力值日志表';

-- 3. 创作者等级配置（level_type=2 创作者等级，8级体系）
-- 等级分值范围参考掘力值参考文档
INSERT INTO ap_level_config (level_type, level_value, title, description, min_score, max_score, icon_url, is_active, created_time)
SELECT 2, 1, '新锐创作者', '初露锋芒，开始创作之旅', 0, 39, 'level_1', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_config WHERE level_type = 2 AND level_value = 1);

INSERT INTO ap_level_config (level_type, level_value, title, description, min_score, max_score, icon_url, is_active, created_time)
SELECT 2, 2, '进阶创作者', '持续输出，积累经验', 40, 279, 'level_2', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_config WHERE level_type = 2 AND level_value = 2);

INSERT INTO ap_level_config (level_type, level_value, title, description, min_score, max_score, icon_url, is_active, created_time)
SELECT 2, 3, '专业创作者', '稳定产出，小有成就', 280, 1799, 'level_3', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_config WHERE level_type = 2 AND level_value = 3);

INSERT INTO ap_level_config (level_type, level_value, title, description, min_score, max_score, icon_url, is_active, created_time)
SELECT 2, 4, '资深创作者', '深耕领域，影响力渐增', 1800, 5499, 'level_4', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_config WHERE level_type = 2 AND level_value = 4);

INSERT INTO ap_level_config (level_type, level_value, title, description, min_score, max_score, icon_url, is_active, created_time)
SELECT 2, 5, '知名创作者', '内容优质，粉丝众多', 5500, 27999, 'level_5', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_config WHERE level_type = 2 AND level_value = 5);

INSERT INTO ap_level_config (level_type, level_value, title, description, min_score, max_score, icon_url, is_active, created_time)
SELECT 2, 6, '头部创作者', '行业翘楚，引领潮流', 28000, 74999, 'level_6', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_config WHERE level_type = 2 AND level_value = 6);

INSERT INTO ap_level_config (level_type, level_value, title, description, min_score, max_score, icon_url, is_active, created_time)
SELECT 2, 7, '顶级创作者', '内容标杆，影响深远', 75000, 139999, 'level_7', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_config WHERE level_type = 2 AND level_value = 7);

INSERT INTO ap_level_config (level_type, level_value, title, description, min_score, max_score, icon_url, is_active, created_time)
SELECT 2, 8, '传奇创作者', '登峰造极，行业传奇', 140000, 99999999, 'level_8', 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_config WHERE level_type = 2 AND level_value = 8);

-- 4. 创作者权益配置（level_type=2，按等级配置权益）
-- 权益体系与 参考资料/逐力值权益response.md 一致，need_jscore_level=-1 表示不受逐友等级限制
INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 1, '文章添加投票', 'can_create_poll', 'icon_vote', 'poster_vote', '文章创作时可以使用添加投票功能，更好地与掘友互动', '[{"desc_title":"什么是「文章添加投票」？","desc_content":"在进行文章创作时，可以在编辑器中使用添加投票功能，帮助你更好地与掘友互动。"},{"desc_title":"如何解锁「文章添加投票」？","desc_content":"创作等级达成LV1，即可解锁「文章添加投票」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 1 AND privilege_code = 'can_create_poll');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 2, '文章添加视频', 'can_add_video', 'icon_video', 'poster_video', '文章创作时可以在编辑器中使用添加视频功能，更好地进行内容分享', '[{"desc_title":"什么是「文章添加视频」？","desc_content":"在进行文章创作时，可以在编辑器中使用添加视频功能，将帮助你更好地进行内容分享。"},{"desc_title":"如何解锁「文章添加视频」？","desc_content":"创作等级达成LV2，即可解锁「文章添加视频」权益。"},{"desc_title":"使用说明","desc_content":"前往编辑器页面，点击上传视频icon，唤起相关入口进行上传，目前仅支持西瓜视频链接上传。"}]', -1, '/editor/drafts/new?v=2', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 2 AND privilege_code = 'can_add_video');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 2, '文章加2个标签', 'can_add_2_tags', 'icon_tag2', 'poster_tag2', '发布文章时可以添加多个标签，有利于获得更多流量', '[{"desc_title":"什么是「文章加多标签」？","desc_content":"在发布文章时可以添加多个标签，添加多个标签有利于获得更多流量，你的内容将会被更多掘友看到。"},{"desc_title":"如何解锁「文章加多标签」？","desc_content":"创作等级达成LV2，即可解锁「文章加2个标签」权益。创作等级达成LV3，权益升级为「文章加3个标签」。"},{"desc_title":"使用说明","desc_content":"在文章编辑器页面-发布弹窗，可以进行添加标签操作。"}]', -1, '/editor/drafts/new?v=2', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 2 AND privilege_code = 'can_add_2_tags');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 3, '文章加3个标签', 'can_add_3_tags', 'icon_tag3', 'poster_tag3', '发布文章时可以添加3个标签，有利于获得更多流量', '[{"desc_title":"什么是「文章加多标签」？","desc_content":"在发布文章时可以添加多个标签，添加多个标签有利于获得更多流量，你的内容将会被更多掘友看到。"},{"desc_title":"如何解锁「文章加多标签」？","desc_content":"创作等级达成LV2，即可解锁「文章加2个标签」权益。创作等级达成LV3，权益升级为「文章加3个标签」。"},{"desc_title":"使用说明","desc_content":"在文章编辑器页面-发布弹窗，可以进行添加标签操作。"}]', -1, '/editor/drafts/new?v=2', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 3 AND privilege_code = 'can_add_3_tags');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 3, '文章定时发布', 'can_schedule_publish', 'icon_schedule', 'poster_schedule', '可以将创作好的文章设置特定时间发布，让你更灵活、更有计划地进行文章创作', '[{"desc_title":"什么是「文章定时发布」？","desc_content":"可以将创作好的文章设置特定时间发布，让你更灵活、更有计划地进行文章创作。"},{"desc_title":"如何解锁「文章定时发布」？","desc_content":"创作等级达成LV3，即可解锁「文章定时发布」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 3 AND privilege_code = 'can_schedule_publish');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 4, '自动推荐到首页', 'can_be_recommended', 'icon_recommend', 'poster_recommend', '文章发布成功后，符合标准的文章将在第一时间被自动推荐到首页', '[{"desc_title":"什么是「自动推荐到首页」？","desc_content":"文章发布成功后，对于符合标准的文章将在第一时间被自动推荐到首页。"},{"desc_title":"如何解锁「自动推荐到首页」？","desc_content":"创作等级达成LV4，即可解锁「自动推荐到首页」权益。"},{"desc_title":"使用说明","desc_content":"只要解锁该权益，对于符合推荐标准的文章将在第一时间被自动推荐；对于不符合推荐标准的文章将会进行人工review。"}]', -1, '/editor/drafts/new?v=2', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 4 AND privilege_code = 'can_be_recommended');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 4, '流量加油包基础版', 'traffic_boost_basic', 'icon_boost', 'poster_boost', '作者可以使用流量加油包加持自己的内容曝光，你的内容将会被推荐给更多用户', '[{"desc_title":"什么是「流量加油包」？","desc_content":"作者可以使用流量加油包加持自己的内容曝光，你的内容将会被推荐给更多用户。"},{"desc_title":"如何解锁「流量加油包」？","desc_content":"创作等级达成LV4，即可解锁「流量加油包基础版」权益。创作等级达成LV5，权益升级，荣升为「流量加油包升级版」。创作等级达成LV6，权益再升级，荣升为「流量加油包加强版」。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 4 AND privilege_code = 'traffic_boost_basic');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 5, '流量加油包升级版', 'traffic_boost_upgrade', 'icon_boost', 'poster_boost', '升级版流量加油包，加持内容曝光，推荐给更多用户', '[{"desc_title":"什么是「流量加油包」？","desc_content":"作者可以使用流量加油包加持自己的内容曝光，你的内容将会被推荐给更多用户。"},{"desc_title":"如何解锁「流量加油包」？","desc_content":"创作等级达成LV4，即可解锁「流量加油包基础版」权益。创作等级达成LV5，权益升级，荣升为「流量加油包升级版」。创作等级达成LV6，权益再升级，荣升为「流量加油包加强版」。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 5 AND privilege_code = 'traffic_boost_upgrade');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 5, '优秀创作者', 'excellent_creator', 'icon_excellent', 'poster_excellent', '优秀创作者是社区重要的创作者成就，荣誉标示展示在个人主页与创作者中心', '[{"desc_title":"什么是「优秀创作者」？","desc_content":"优秀创作者是掘金社区重要的创作者成就，相关荣誉标示将会展示在你的个人主页、创作者中心，会让更多掘友快速认识你。"},{"desc_title":"如何解锁「优秀创作者」？","desc_content":"创作等级达成LV5，且无违规行为，即可解锁「优秀创作者」成就。"},{"desc_title":"使用说明","desc_content":"只要解锁该权益，相关荣誉标示将会展示在你的个人主页、创作者中心。"}]', -1, '/user/{userId}', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 5 AND privilege_code = 'excellent_creator');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 6, '流量加油包加强版', 'traffic_boost_plus', 'icon_boost', 'poster_boost', '加强版流量加油包，加持内容曝光，推荐给更多用户', '[{"desc_title":"什么是「流量加油包」？","desc_content":"作者可以使用流量加油包加持自己的内容曝光，你的内容将会被推荐给更多用户。"},{"desc_title":"如何解锁「流量加油包」？","desc_content":"创作等级达成LV4，即可解锁「流量加油包基础版」权益。创作等级达成LV5，权益升级，荣升为「流量加油包升级版」。创作等级达成LV6，权益再升级，荣升为「流量加油包加强版」。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 6 AND privilege_code = 'traffic_boost_plus');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 6, '自定义域名', 'custom_domain', 'icon_domain', 'poster_domain', '可以设置个人主页的域名，个性化的名称有利于让更多掘友快速记住你', '[{"desc_title":"什么是「自定义域名」？","desc_content":"可以设置个人主页的域名，个性化的名称有利于让更多掘友快速记住你。"},{"desc_title":"如何解锁「自定义域名」？","desc_content":"创作等级达成LV6，即可解锁「自定义域名」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 6 AND privilege_code = 'custom_domain');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 6, '作者群发消息', 'author_group_msg', 'icon_msg', 'poster_msg', '权益开通后，作者可以按照一定频率给关注者群发消息，让你的读者快速获取一手消息', '[{"desc_title":"什么是「作者群发消息」？","desc_content":"权益开通后，作者可以按照一定频率给关注者群发消息，让你的读者快速获取一手消息。"},{"desc_title":"如何解锁「作者群发消息」？","desc_content":"创作等级达成LV6，即可解锁「作者群发消息」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 3, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 6 AND privilege_code = 'author_group_msg');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 7, '创作小册', 'can_create_course', 'icon_course', 'poster_course', '作者可以在平台创作体系化的小册内容，经运营审核通过后可进行售卖并获得收益', '[{"desc_title":"什么是「创作小册」？","desc_content":"作者可以在掘金创作体系化的小册内容，经运营审核通过后，相关小册可以在掘金上进行售卖，可以按照一定的规则获取收益。"},{"desc_title":"如何解锁「创作小册」？","desc_content":"创作等级达成LV7，即可解锁「创作小册」权益。"},{"desc_title":"使用说明","desc_content":"在创作中心下拉列表找到写小册入口，可以在小册编辑器进行创作。"}]', -1, '/creator/course/edit', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 7 AND privilege_code = 'can_create_course');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 7, '自定义推广', 'custom_promotion', 'icon_promo', 'poster_promo', '权益开通后，作者可以在所创作的文章页下方设置一个推广模块，用于推荐自己的内容', '[{"desc_title":"什么是「自定义推广」？","desc_content":"权益开通后，作者可以在所创作的文章页下方设置一个推广模块，用于推荐自己的内容。"},{"desc_title":"如何解锁「自定义推广」？","desc_content":"创作等级达成LV7，即可解锁「自定义推广」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 7 AND privilege_code = 'custom_promotion');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 8, '提交标签', 'submit_tag', 'icon_tag_submit', 'poster_tag_submit', '可以对平台的标签提供建议，提交新的标签', '[{"desc_title":"什么是「提交标签」？","desc_content":"可以对掘金的标签提供建议，提交新的标签。"},{"desc_title":"如何解锁「提交标签」？","desc_content":"创作等级达成LV8，即可解锁「提交标签」权益。"},{"desc_title":"使用说明","desc_content":"填写对掘金标签建议，我们会认真倾听你的声音。"}]', -1, '', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 8 AND privilege_code = 'submit_tag');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 8, '社区共建者', 'community_builder', 'icon_community', 'poster_community', '社区共建者是重磅社区成就，标示展示在个人主页与创作者中心', '[{"desc_title":"什么是「社区共建者」？","desc_content":"社区共建者是重磅社区成就，相关标示将会展示在你的个人主页、创作者中心，会让更多掘友快速认识你，你也将拥有更多深度参与掘金共建的机会，如参与社区治理规范的建设等。"},{"desc_title":"如何解锁「社区共建者」？","desc_content":"创作等级达成LV8，即可解锁「社区共建者」成就。"},{"desc_title":"使用说明","desc_content":"只要解锁该权益，相关荣誉标示将会展示在你的个人主页、创作者中心。"}]', -1, '/user/{userId}', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 8 AND privilege_code = 'community_builder');

-- 5. 行为任务配置（创作者成长任务）
INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'publish_article', '发布文章', '内容创作', 2, 10, 10, 'icon_publish_article', '去发布', '/publish', 1, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'publish_article');

INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'original_article', '发布原创文章', '内容创作', 2, 20, 5, 'icon_original', '去创作', '/publish', 2, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'original_article');

INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'article_read', '文章被阅读', '社区活跃', 5, 1, -1, 'icon_read', '查看详情', '', 3, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'article_read');

INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'article_like', '文章被点赞', '社区活跃', 5, 2, -1, 'icon_like', '查看详情', '', 4, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'article_like');

INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'article_comment', '文章被评论', '社区活跃', 5, 3, -1, 'icon_comment', '查看详情', '', 5, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'article_comment');

INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'article_share', '文章被分享', '社区活跃', 5, 2, -1, 'icon_share', '查看详情', '', 6, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'article_share');

INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'follower_increase', '粉丝增长', '社区影响力', 4, 5, -1, 'icon_follower', '查看详情', '', 7, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'follower_increase');

INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'article_selected', '文章被加精', '社区影响力', 4, 50, 3, 'icon_selected', '查看详情', '', 8, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'article_selected');

INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'attend_activity', '参与平台活动', '社区基础', 1, 30, 5, 'icon_activity', '去参与', '/activity', 9, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'attend_activity');

INSERT INTO ap_behavior_config (action_code, action_name, group_type, group_sort, score, daily_limit, icon_name, btn_name, web_jump_url, sort_order, is_active, created_time, updated_time)
SELECT 'study_course', '学习创作课程', '社区学习', 3, 15, 10, 'icon_study', '去学习', '/course', 10, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_behavior_config WHERE action_code = 'study_course');