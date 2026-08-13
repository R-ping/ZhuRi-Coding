-- ============================================================
-- 成长等级页后端数据模型
-- 1. ap_behavior_config      行为项配置表（系统固定）
-- 2. ap_user_daily_progress  用户每日行为进度表
-- 3. ap_level_privilege      等级权益表（字段补齐）
-- 4. ap_level_config         等级区间对齐 8 级
-- ============================================================

-- ------------------------------------------------------------
-- 1. 行为项配置表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ap_behavior_config` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `action_code` VARCHAR(50)  NOT NULL COMMENT '行为编码（唯一）',
    `action_name` VARCHAR(100) NOT NULL COMMENT '行为名称',
    `group_type`  VARCHAR(20)  NOT NULL COMMENT '分组类型：社区基础/社区活跃/社区学习/社区影响力',
    `group_sort`  INT          NOT NULL DEFAULT 0 COMMENT '分组排序：1社区基础 3社区学习 4社区影响力 5社区活跃',
    `score`       DECIMAL(5,1) NOT NULL DEFAULT 0 COMMENT '单次掘友分',
    `daily_limit` INT          NOT NULL DEFAULT -1 COMMENT '每日上限，-1表示无上限',
    `icon_name`   VARCHAR(100) NOT NULL DEFAULT '' COMMENT '图标名称（前端本地资源）',
    `btn_name`    VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '按钮文案',
    `web_jump_url` VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'Web端跳转链接',
    `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '行为排序',
    `is_active`   TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用 1是 0否',
    `created_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_action_code` (`action_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行为项配置表（系统固定，所有用户一致）';

-- ------------------------------------------------------------
-- 2. 用户每日行为进度表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ap_user_daily_progress` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
    `stat_date`   DATE     NOT NULL COMMENT '统计日期',
    `action_code` VARCHAR(50) NOT NULL COMMENT '行为编码',
    `count`       INT      NOT NULL DEFAULT 0 COMMENT '当日已完成次数',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date_action` (`user_id`, `stat_date`, `action_code`),
    KEY `idx_user_date` (`user_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户每日行为进度表';

-- ------------------------------------------------------------
-- 3. 等级权益表字段补齐（MySQL 不支持 ADD COLUMN IF NOT EXISTS，
--    执行前请确认列不存在；重复执行会报 Duplicate column，可忽略）
-- ------------------------------------------------------------
ALTER TABLE `ap_level_privilege`
    ADD COLUMN `icon_name` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '图标名称（前端本地资源）' AFTER `privilege_code`,
    ADD COLUMN `poster_name` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '海报图名称（前端本地资源）' AFTER `icon_name`,
    ADD COLUMN `desc_json` TEXT COMMENT '权益说明JSON：[{desc_title,desc_content}]' AFTER `description`,
    ADD COLUMN `need_jscore_level` INT NOT NULL DEFAULT 0 COMMENT '所需逐日等级' AFTER `desc_json`,
    ADD COLUMN `web_jump_url` VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'Web端跳转链接' AFTER `need_jscore_level`,
    ADD COLUMN `app_jump_url` VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'App端跳转链接' AFTER `web_jump_url`,
    ADD COLUMN `priv_status` TINYINT NOT NULL DEFAULT 0 COMMENT '权益状态 1已解锁 0未解锁' AFTER `app_jump_url`,
    ADD COLUMN `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序' AFTER `priv_status`,
    ADD COLUMN `is_active` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用 1是 0否' AFTER `sort_order`;

-- ------------------------------------------------------------
-- 4. 行为项初始化数据（参考 升级行为response.md）
-- ------------------------------------------------------------
INSERT INTO `ap_behavior_config`
    (`action_code`, `action_name`, `group_type`, `group_sort`, `score`, `daily_limit`, `icon_name`, `btn_name`, `web_jump_url`, `sort_order`)
VALUES
    -- 社区基础（一次性，group_sort=1）
    ('upload_avatar', '上传头像', '社区基础', 1, 1, 1, 'upload_avatar', '去上传', '/user/settings/profile', 1),
    -- 社区学习（每日重置，group_sort=3）
    ('browse_article', '浏览1篇文章/课程', '社区学习', 3, 0.5, 5, 'browse_article', '去学习', '/', 1),
    -- 社区影响力（无限制，group_sort=4）
    ('be_followed', '被一位掘友关注', '社区影响力', 4, 0.1, -1, 'be_followed', '去分享', '/user/{userId}', 1),
    ('pin_liked', '沸点获得一个点赞', '社区影响力', 4, 0.1, -1, 'pin_liked', '去发布', '/user/{userId}/pins', 2),
    ('article_liked', '文章获得一个点赞', '社区影响力', 4, 0.1, -1, 'article_liked', '去创作', '/creator/content/article/essays?status=all', 3),
    -- 社区活跃（每日重置，group_sort=5）
    ('daily_login', '移动端每日登录访问', '社区活跃', 5, 2, 1, 'daily_login', '去完成', 'https://z.juejin.cn/qxeP', 1),
    ('publish_article', '发布一篇文章', '社区活跃', 5, 8, 2, 'publish_article', '去发布', '/editor/drafts/new?v=2', 2),
    ('publish_pin', '发布一条沸点', '社区活跃', 5, 8, 2, 'publish_pin', '去发布', '/pins', 3),
    ('comment_article', '评论一篇文章', '社区活跃', 5, 2, 2, 'comment_article', '去评论', '/', 4),
    ('comment_pin', '评论一条沸点', '社区活跃', 5, 2, 5, 'comment_pin', '去评论', '/pins/hot', 5),
    ('like_article', '点赞一篇文章', '社区活跃', 5, 1, 2, 'like_article', '去点赞', '/', 6),
    ('like_pin', '点赞一条沸点', '社区活跃', 5, 1, 2, 'like_pin', '去点赞', '/pins/new', 7),
    ('collect_article', '收藏一篇文章', '社区活跃', 5, 1, 2, 'collect_article', '去收藏', '/', 8),
    ('follow_user', '关注一位掘友', '社区活跃', 5, 4, 2, 'follow_user', '去关注', '/recommendation/authors/recommended', 9)
ON DUPLICATE KEY UPDATE
    `action_name` = VALUES(`action_name`),
    `group_type`  = VALUES(`group_type`),
    `score`       = VALUES(`score`),
    `daily_limit` = VALUES(`daily_limit`),
    `icon_name`   = VALUES(`icon_name`),
    `btn_name`    = VALUES(`btn_name`),
    `web_jump_url` = VALUES(`web_jump_url`);

-- ------------------------------------------------------------
-- 5. 等级权益初始化数据（参考 掘友等级经验权益查询response.md）
--    priv_id 0-10，共11项权益
-- ------------------------------------------------------------
INSERT INTO `ap_level_privilege`
    (`level_type`, `level_value`, `privilege_name`, `privilege_code`, `icon_name`, `desc_json`, `need_jscore_level`, `web_jump_url`, `app_jump_url`, `priv_status`, `sort_order`, `is_active`)
VALUES
    (1, 1, '主动发起私信', 'priv_send_private_message', 'priv_send_private_message',
     '[{"desc_title":"解锁等级","desc_content":"掘友1级"},{"desc_title":"权益说明","desc_content":"你可以主动向社区掘友发起私信，交流讨论"}]',
     1, 'path/a', 'path/b', 1, 1, 1),
    (1, 2, '发起投票', 'priv_create_poll', 'priv_create_poll',
     '[{"desc_title":"解锁等级","desc_content":"掘友2级"},{"desc_title":"权益说明","desc_content":"你可以在发布沸点时，选择使用投票工具，针对某一问题，收集掘友的意见和建议"}]',
     2, '', '', 0, 2, 1),
    (1, 3, '评论区权限设置', 'priv_comment_permission', 'priv_comment_permission',
     '[{"desc_title":"解锁等级","desc_content":"掘友3级"},{"desc_title":"权益说明","desc_content":"你可以对评论区进行管理，比如管理哪些人可以评论你在站内发布的文章或沸点等"}]',
     3, '', '', 0, 3, 1),
    (1, 4, '评论区Mark', 'priv_comment_mark', 'priv_comment_mark',
     '[{"desc_title":"解锁等级","desc_content":"掘友4级"},{"desc_title":"权益说明","desc_content":"你可以通过评论区Mark功能，标记感兴趣的文章或沸点，后续可查看内容评论进展"}]',
     4, '', '', 0, 4, 1),
    (1, 4, '使用掘金特色表情', 'priv_use_emoji', 'priv_use_emoji',
     '[{"desc_title":"解锁等级","desc_content":"掘友4级"},{"desc_title":"权益说明","desc_content":"你可以使用掘金社区中的热梗或热词表情，也可以选择掘金特色IP动态表情"}]',
     4, '', '', 0, 5, 1),
    (1, 5, '个性装扮', 'priv_customize', 'priv_customize',
     '[{"desc_title":"解锁等级","desc_content":"掘友5级"},{"desc_title":"权益说明","desc_content":"你可以选择卡片样式装扮\n你可以上传喜欢的图片，装扮掘金个人主页封面"}]',
     5, '', '', 0, 6, 1),
    (1, 5, '升级矿石奖励', 'priv_upgrade_ore', 'priv_upgrade_ore',
     '[{"desc_title":"解锁等级","desc_content":"掘友5级"},{"desc_title":"权益说明","desc_content":"你可以获得一定数量的矿石奖励，用于幸运抽奖、福利兑换、社区道具兑换等活动\n掘友五级：5000矿石\n掘友六级：10000矿石\n掘友七级：20000矿石\n掘友八级：40000矿石"}]',
     5, '', '', 1, 7, 1),
    (1, 6, '参选掘金神评官', 'priv_judge', 'priv_judge',
     '[{"desc_title":"解锁等级","desc_content":"掘友6级"},{"desc_title":"权益说明","desc_content":"你有机会申请成为掘金评论的神评官，协助官方筛选、甄别精彩、有趣、有干货的评论，让掘金社区氛围更加友好、和谐、有趣"}]',
     6, '', '', 1, 8, 1),
    (1, 6, '参选小册评审团', 'priv_course_judge', 'priv_course_judge',
     '[{"desc_title":"解锁等级","desc_content":"掘友6级"},{"desc_title":"权益说明","desc_content":"你有机会申请成为小册评审团，优先免费阅读最新小册，发表对小册内容的评价，提供其他掘友购买参考"}]',
     6, '', '', 1, 9, 1),
    (1, 7, '参选掘金内容众议官', 'priv_content_judge', 'priv_content_judge',
     '[{"desc_title":"解锁等级","desc_content":"掘友7级"},{"desc_title":"权益说明","desc_content":"你有机会申请成为掘金内容众议官，与掘金运营同学一起肩负起共建美好社区的使命。你将参与具体某一掘金文章或沸点的众议，帮助推荐优质内容，判断内容是否符合社区规范，沉淀社区治理策略。净化不良内容，以营造友好和谐的社区氛围"}]',
     7, '', '', 1, 10, 1),
    (1, 8, '荣获"掘金共建者"身份', 'priv_builder', 'priv_builder',
     '[{"desc_title":"解锁等级","desc_content":"掘友8级"},{"desc_title":"权益说明","desc_content":"你可以获得掘金共建者的终生成就，解锁荣誉身份标识，彰显你在掘金的声望，体现你在掘金的贡献。参与社区治理规范的建设"}]',
     8, '', '', 1, 11, 1)
ON DUPLICATE KEY UPDATE
    `privilege_name` = VALUES(`privilege_name`),
    `icon_name`      = VALUES(`icon_name`),
    `desc_json`      = VALUES(`desc_json`),
    `need_jscore_level` = VALUES(`need_jscore_level`);

-- ------------------------------------------------------------
-- 6. 等级区间对齐 8 级（level_spec：0/15/30/150/500/2000/7000/25000）
--    清理原 10 级数据并重新插入 8 级
-- ------------------------------------------------------------
DELETE FROM `ap_level_config` WHERE `level_type` = 1;

INSERT INTO `ap_level_config` (`level_type`, `level_value`, `min_score`, `max_score`, `title`, `description`)
VALUES
    (1, 1, 0,      14,         '预备掘友', '掘友分 0-14'),
    (1, 2, 15,     29,         '见习掘友', '掘友分 15-29'),
    (1, 3, 30,     149,        '新星掘友', '掘友分 30-149'),
    (1, 4, 150,    499,        '进阶掘友', '掘友分 150-499'),
    (1, 5, 500,    1999,       '先锋掘友', '掘友分 500-1999'),
    (1, 6, 2000,   6999,       '杰出掘友', '掘友分 2000-6999'),
    (1, 7, 7000,   24999,      '荣誉掘友', '掘友分 7000-24999'),
    (1, 8, 25000,  999999999,  '终身掘友', '掘友分 25000+');
