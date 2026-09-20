-- ========================================================
-- 逐力值等级（level_type=2）等级配置与权益数据修正
-- 依据：参考资料/逐力值权益response.md（稀土掘金逐力值权益数据）
-- 说明：
--   1) ap_level_config 中 level_type=2 的 Lv1-Lv6 分数区间整体错位，按 level_spec 修正；
--   2) ap_level_privilege 中 level_type=2 的权益全部为自造名称，按 level_privilege 替换为真实权益；
--   3) ap_user_level 中为解锁小册权限手工置为 5000 的逐力值修正为 75000（Lv7 真实最低分）。
-- ========================================================

-- 1. 修正逐力值等级配置区间（对齐 level_spec）
UPDATE ap_level_config
SET min_score = 0,     max_score = 39,      title = '新锐创作者', description = '初露锋芒，开始创作之旅', icon_url = 'level_1'
WHERE level_type = 2 AND level_value = 1;

UPDATE ap_level_config
SET min_score = 40,    max_score = 279,     title = '进阶创作者', description = '持续输出，积累经验',     icon_url = 'level_2'
WHERE level_type = 2 AND level_value = 2;

UPDATE ap_level_config
SET min_score = 280,   max_score = 1799,    title = '专业创作者', description = '稳定产出，小有成就',     icon_url = 'level_3'
WHERE level_type = 2 AND level_value = 3;

UPDATE ap_level_config
SET min_score = 1800,  max_score = 5499,    title = '资深创作者', description = '深耕领域，影响力渐增',   icon_url = 'level_4'
WHERE level_type = 2 AND level_value = 4;

UPDATE ap_level_config
SET min_score = 5500,  max_score = 27999,   title = '知名创作者', description = '内容优质，粉丝众多',     icon_url = 'level_5'
WHERE level_type = 2 AND level_value = 5;

UPDATE ap_level_config
SET min_score = 28000, max_score = 74999,   title = '头部创作者', description = '行业翘楚，引领潮流',     icon_url = 'level_6'
WHERE level_type = 2 AND level_value = 6;

UPDATE ap_level_config
SET min_score = 75000, max_score = 139999,  title = '顶级创作者', description = '内容标杆，影响深远',     icon_url = 'level_7'
WHERE level_type = 2 AND level_value = 7;

UPDATE ap_level_config
SET min_score = 140000, max_score = 99999999, title = '传奇创作者', description = '登峰造极，行业传奇',   icon_url = 'level_8'
WHERE level_type = 2 AND level_value = 8;

-- 2. 替换逐力值等级权益（对齐 level_privilege，need_jscore_level=-1 表示不受逐友等级限制）
DELETE FROM ap_level_privilege WHERE level_type = 2;

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time) VALUES
(2, 1, '文章添加投票',   'can_create_poll',      'icon_vote',  'poster_vote',    '文章创作时可以使用添加投票功能，更好地与掘友互动', '[{"desc_title":"什么是「文章添加投票」？","desc_content":"在进行文章创作时，可以在编辑器中使用添加投票功能，帮助你更好地与掘友互动。"},{"desc_title":"如何解锁「文章添加投票」？","desc_content":"创作等级达成LV1，即可解锁「文章添加投票」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 1, 1, NOW()),
(2, 2, '文章添加视频',   'can_add_video',        'icon_video', 'poster_video',   '文章创作时可以在编辑器中使用添加视频功能，更好地进行内容分享', '[{"desc_title":"什么是「文章添加视频」？","desc_content":"在进行文章创作时，可以在编辑器中使用添加视频功能，将帮助你更好地进行内容分享。"},{"desc_title":"如何解锁「文章添加视频」？","desc_content":"创作等级达成LV2，即可解锁「文章添加视频」权益。"},{"desc_title":"使用说明","desc_content":"前往编辑器页面，点击上传视频icon，唤起相关入口进行上传，目前仅支持西瓜视频链接上传。"}]', -1, '/editor/drafts/new?v=2', '', 1, 1, 1, NOW()),
(2, 2, '文章加2个标签',  'can_add_2_tags',       'icon_tag2',  'poster_tag2',    '发布文章时可以添加多个标签，有利于获得更多流量', '[{"desc_title":"什么是「文章加多标签」？","desc_content":"在发布文章时可以添加多个标签，添加多个标签有利于获得更多流量，你的内容将会被更多掘友看到。"},{"desc_title":"如何解锁「文章加多标签」？","desc_content":"创作等级达成LV2，即可解锁「文章加2个标签」权益。创作等级达成LV3，权益升级为「文章加3个标签」。"},{"desc_title":"使用说明","desc_content":"在文章编辑器页面-发布弹窗，可以进行添加标签操作。"}]', -1, '/editor/drafts/new?v=2', '', 1, 2, 1, NOW()),
(2, 3, '文章加3个标签',  'can_add_3_tags',       'icon_tag3',  'poster_tag3',    '发布文章时可以添加3个标签，有利于获得更多流量', '[{"desc_title":"什么是「文章加多标签」？","desc_content":"在发布文章时可以添加多个标签，添加多个标签有利于获得更多流量，你的内容将会被更多掘友看到。"},{"desc_title":"如何解锁「文章加多标签」？","desc_content":"创作等级达成LV2，即可解锁「文章加2个标签」权益。创作等级达成LV3，权益升级为「文章加3个标签」。"},{"desc_title":"使用说明","desc_content":"在文章编辑器页面-发布弹窗，可以进行添加标签操作。"}]', -1, '/editor/drafts/new?v=2', '', 1, 1, 1, NOW()),
(2, 3, '文章定时发布',   'can_schedule_publish', 'icon_schedule', 'poster_schedule', '可以将创作好的文章设置特定时间发布，让你更灵活、更有计划地进行文章创作', '[{"desc_title":"什么是「文章定时发布」？","desc_content":"可以将创作好的文章设置特定时间发布，让你更灵活、更有计划地进行文章创作。"},{"desc_title":"如何解锁「文章定时发布」？","desc_content":"创作等级达成LV3，即可解锁「文章定时发布」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 2, 1, NOW()),
(2, 4, '自动推荐到首页', 'can_be_recommended',   'icon_recommend', 'poster_recommend', '文章发布成功后，符合标准的文章将在第一时间被自动推荐到首页', '[{"desc_title":"什么是「自动推荐到首页」？","desc_content":"文章发布成功后，对于符合标准的文章将在第一时间被自动推荐到首页。"},{"desc_title":"如何解锁「自动推荐到首页」？","desc_content":"创作等级达成LV4，即可解锁「自动推荐到首页」权益。"},{"desc_title":"使用说明","desc_content":"只要解锁该权益，对于符合推荐标准的文章将在第一时间被自动推荐；对于不符合推荐标准的文章将会进行人工review。"}]', -1, '/editor/drafts/new?v=2', '', 1, 1, 1, NOW()),
(2, 4, '流量加油包基础版', 'traffic_boost_basic', 'icon_boost', 'poster_boost',    '作者可以使用流量加油包加持自己的内容曝光，你的内容将会被推荐给更多用户', '[{"desc_title":"什么是「流量加油包」？","desc_content":"作者可以使用流量加油包加持自己的内容曝光，你的内容将会被推荐给更多用户。"},{"desc_title":"如何解锁「流量加油包」？","desc_content":"创作等级达成LV4，即可解锁「流量加油包基础版」权益。创作等级达成LV5，权益升级，荣升为「流量加油包升级版」。创作等级达成LV6，权益再升级，荣升为「流量加油包加强版」。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 2, 1, NOW()),
(2, 5, '流量加油包升级版', 'traffic_boost_upgrade', 'icon_boost', 'poster_boost',  '升级版流量加油包，加持内容曝光，推荐给更多用户', '[{"desc_title":"什么是「流量加油包」？","desc_content":"作者可以使用流量加油包加持自己的内容曝光，你的内容将会被推荐给更多用户。"},{"desc_title":"如何解锁「流量加油包」？","desc_content":"创作等级达成LV4，即可解锁「流量加油包基础版」权益。创作等级达成LV5，权益升级，荣升为「流量加油包升级版」。创作等级达成LV6，权益再升级，荣升为「流量加油包加强版」。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 1, 1, NOW()),
(2, 5, '优秀创作者',     'excellent_creator',    'icon_excellent', 'poster_excellent', '优秀创作者是社区重要的创作者成就，荣誉标示展示在个人主页与创作者中心', '[{"desc_title":"什么是「优秀创作者」？","desc_content":"优秀创作者是掘金社区重要的创作者成就，相关荣誉标示将会展示在你的个人主页、创作者中心，会让更多掘友快速认识你。"},{"desc_title":"如何解锁「优秀创作者」？","desc_content":"创作等级达成LV5，且无违规行为，即可解锁「优秀创作者」成就。"},{"desc_title":"使用说明","desc_content":"只要解锁该权益，相关荣誉标示将会展示在你的个人主页、创作者中心。"}]', -1, '/user/{userId}', '', 1, 2, 1, NOW()),
(2, 6, '流量加油包加强版', 'traffic_boost_plus', 'icon_boost', 'poster_boost',    '加强版流量加油包，加持内容曝光，推荐给更多用户', '[{"desc_title":"什么是「流量加油包」？","desc_content":"作者可以使用流量加油包加持自己的内容曝光，你的内容将会被推荐给更多用户。"},{"desc_title":"如何解锁「流量加油包」？","desc_content":"创作等级达成LV4，即可解锁「流量加油包基础版」权益。创作等级达成LV5，权益升级，荣升为「流量加油包升级版」。创作等级达成LV6，权益再升级，荣升为「流量加油包加强版」。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 1, 1, NOW()),
(2, 6, '自定义域名',     'custom_domain',        'icon_domain', 'poster_domain',  '可以设置个人主页的域名，个性化的名称有利于让更多掘友快速记住你', '[{"desc_title":"什么是「自定义域名」？","desc_content":"可以设置个人主页的域名，个性化的名称有利于让更多掘友快速记住你。"},{"desc_title":"如何解锁「自定义域名」？","desc_content":"创作等级达成LV6，即可解锁「自定义域名」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 2, 1, NOW()),
(2, 6, '作者群发消息',   'author_group_msg',     'icon_msg',   'poster_msg',     '权益开通后，作者可以按照一定频率给关注者群发消息，让你的读者快速获取一手消息', '[{"desc_title":"什么是「作者群发消息」？","desc_content":"权益开通后，作者可以按照一定频率给关注者群发消息，让你的读者快速获取一手消息。"},{"desc_title":"如何解锁「作者群发消息」？","desc_content":"创作等级达成LV6，即可解锁「作者群发消息」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 3, 1, NOW()),
(2, 7, '创作小册',       'can_create_course',    'icon_course', 'poster_course',  '作者可以在平台创作体系化的小册内容，经运营审核通过后可进行售卖并获得收益', '[{"desc_title":"什么是「创作小册」？","desc_content":"作者可以在掘金创作体系化的小册内容，经运营审核通过后，相关小册可以在掘金上进行售卖，可以按照一定的规则获取收益。"},{"desc_title":"如何解锁「创作小册」？","desc_content":"创作等级达成LV7，即可解锁「创作小册」权益。"},{"desc_title":"使用说明","desc_content":"在创作中心下拉列表找到写小册入口，可以在小册编辑器进行创作。"}]', -1, '/creator/course/edit', '', 1, 1, 1, NOW()),
(2, 7, '自定义推广',     'custom_promotion',     'icon_promo', 'poster_promo',   '权益开通后，作者可以在所创作的文章页下方设置一个推广模块，用于推荐自己的内容', '[{"desc_title":"什么是「自定义推广」？","desc_content":"权益开通后，作者可以在所创作的文章页下方设置一个推广模块，用于推荐自己的内容。"},{"desc_title":"如何解锁「自定义推广」？","desc_content":"创作等级达成LV7，即可解锁「自定义推广」权益。"},{"desc_title":"使用说明","desc_content":"功能快马加鞭建设中"}]', -1, '', '', 0, 2, 1, NOW()),
(2, 8, '提交标签',       'submit_tag',           'icon_tag_submit', 'poster_tag_submit', '可以对平台的标签提供建议，提交新的标签', '[{"desc_title":"什么是「提交标签」？","desc_content":"可以对掘金的标签提供建议，提交新的标签。"},{"desc_title":"如何解锁「提交标签」？","desc_content":"创作等级达成LV8，即可解锁「提交标签」权益。"},{"desc_title":"使用说明","desc_content":"填写对掘金标签建议，我们会认真倾听你的声音。"}]', -1, '', '', 1, 1, 1, NOW()),
(2, 8, '社区共建者',     'community_builder',    'icon_community', 'poster_community', '社区共建者是重磅社区成就，标示展示在个人主页与创作者中心', '[{"desc_title":"什么是「社区共建者」？","desc_content":"社区共建者是重磅社区成就，相关标示将会展示在你的个人主页、创作者中心，会让更多掘友快速认识你，你也将拥有更多深度参与掘金共建的机会，如参与社区治理规范的建设等。"},{"desc_title":"如何解锁「社区共建者」？","desc_content":"创作等级达成LV8，即可解锁「社区共建者」成就。"},{"desc_title":"使用说明","desc_content":"只要解锁该权益，相关荣誉标示将会展示在你的个人主页、创作者中心。"}]', -1, '/user/{userId}', '', 1, 2, 1, NOW());

-- 3. 修正为解锁小册权限而手工赋值的逐力值（5000 -> 75000，对齐 Lv7 最低分）
UPDATE ap_user_level
SET power_value = 75000, updated_time = NOW()
WHERE user_id = 1700683778 AND power_level = 7 AND power_value = 5000;
