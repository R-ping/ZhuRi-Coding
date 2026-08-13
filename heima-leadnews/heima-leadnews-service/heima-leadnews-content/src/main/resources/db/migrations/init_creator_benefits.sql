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
-- 等级1：基础权益
INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 1, '基础创作者标识', 'creator_badge_1', 'icon_badge_1', 'poster_level_1', '获得新锐创作者徽章', '[{"desc_title":"专属徽章","desc_content":"新锐创作者身份标识"}]', 0, '', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 1 AND privilege_code = 'creator_badge_1');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 1, '发布文章权限', 'publish_article', 'icon_publish', 'poster_publish', '可发布原创文章', '[{"desc_title":"发布权限","desc_content":"拥有发布原创文章的权限"}]', 0, '', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 1 AND privilege_code = 'publish_article');

-- 等级2：进阶权益
INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 2, '自定义简介', 'custom_bio', 'icon_custom', 'poster_custom', '可自定义个人简介和背景图', '[{"desc_title":"个性化设置","desc_content":"自定义个人主页背景图和简介"}]', 0, '', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 2 AND privilege_code = 'custom_bio');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 2, '文章分类管理', 'category_manage', 'icon_category', 'poster_category', '可管理文章分类和标签', '[{"desc_title":"分类管理","desc_content":"创建和管理专属文章分类"}]', 0, '', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 2 AND privilege_code = 'category_manage');

-- 等级3：专业权益
INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 3, '数据分析报告', 'data_report', 'icon_report', 'poster_report', '查看详细的文章数据分析', '[{"desc_title":"数据分析","desc_content":"查看文章阅读量、互动等详细数据"}]', 0, '', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 3 AND privilege_code = 'data_report');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 3, '收益提现', 'withdraw', 'icon_withdraw', 'poster_withdraw', '文章收益可提现', '[{"desc_title":"收益提现","desc_content":"文章创作收益可申请提现"}]', 0, '', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 3 AND privilege_code = 'withdraw');

-- 等级4：资深权益
INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 4, '专栏申请', 'column_apply', 'icon_column', 'poster_column', '可申请开通专属专栏', '[{"desc_title":"专属专栏","desc_content":"申请开通个人专属专栏"}]', 0, '', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 4 AND privilege_code = 'column_apply');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 4, '原创认证', 'original_cert', 'icon_cert', 'poster_cert', '可申请原创认证', '[{"desc_title":"原创认证","desc_content":"申请文章原创认证标识"}]', 0, '', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 4 AND privilege_code = 'original_cert');

-- 等级5：知名权益
INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 5, '付费专栏', 'paid_column', 'icon_paid', 'poster_paid', '可开设付费专栏', '[{"desc_title":"付费专栏","desc_content":"开设付费专栏获取收益"}]', 0, '', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 5 AND privilege_code = 'paid_column');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 5, '优先推荐', 'recommend_priority', 'icon_recommend', 'poster_recommend', '文章获得平台优先推荐', '[{"desc_title":"优先推荐","desc_content":"文章获得平台流量扶持"}]', 0, '', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 5 AND privilege_code = 'recommend_priority');

-- 等级6：头部权益
INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 6, '平台签约', 'platform_sign', 'icon_sign', 'poster_sign', '获得平台签约机会', '[{"desc_title":"平台签约","desc_content":"优质创作者平台签约机会"}]', 0, '', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 6 AND privilege_code = 'platform_sign');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 6, '商业合作', 'commerce_coop', 'icon_coop', 'poster_coop', '开放商业合作权限', '[{"desc_title":"商业合作","desc_content":"开放品牌商业合作权限"}]', 0, '', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 6 AND privilege_code = 'commerce_coop');

-- 等级7：顶级权益
INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 7, '专属客服', 'vip_service', 'icon_vip', 'poster_vip', '享受一对一专属客服服务', '[{"desc_title":"专属服务","desc_content":"一对一专属客服和运营支持"}]', 0, '', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 7 AND privilege_code = 'vip_service');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 7, '线下活动', 'offline_event', 'icon_event', 'poster_event', '优先参与平台线下活动', '[{"desc_title":"线下活动","desc_content":"优先受邀参加平台线下活动"}]', 0, '', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 7 AND privilege_code = 'offline_event');

-- 等级8：传奇权益
INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 8, '行业影响力', 'industry_influence', 'icon_influence', 'poster_influence', '成为平台标杆，引领行业', '[{"desc_title":"行业标杆","desc_content":"成为平台标杆创作者，引领行业发展"}]', 0, '', '', 1, 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 8 AND privilege_code = 'industry_influence');

INSERT INTO ap_level_privilege (level_type, level_value, privilege_name, privilege_code, icon_name, poster_name, description, desc_json, need_jscore_level, web_jump_url, app_jump_url, priv_status, sort_order, is_active, created_time)
SELECT 2, 8, '官方推荐', 'official_recommend', 'icon_official', 'poster_official', '获得平台官方全方位推荐', '[{"desc_title":"官方推荐","desc_content":"平台官方全方位推荐资源"}]', 0, '', '', 1, 2, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM ap_level_privilege WHERE level_type = 2 AND level_value = 8 AND privilege_code = 'official_recommend');

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