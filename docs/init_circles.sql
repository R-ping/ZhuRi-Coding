-- 初始化圈子数据（正确版本）
-- 将数据插入到 ap_circle 表中，通过 category_id 关联分类
-- member_count (逐友数) 和 pins_count (沸点帖数) 初始化为 0

-- 职场 (category_id: 3)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('内推招聘广场', '汇聚各大公司内推机会，助你找到心仪工作', 3, 0, 0, 1),
('打工人的日常', '分享打工人的喜怒哀乐，职场人的精神家园', 3, 0, 0, 2);

-- 技术 (category_id: 1)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('鸿蒙开发者社区', 'HarmonyOS 开发者聚集地，分享开发经验与技术交流', 1, 0, 0, 1),
('源码共读', '一起阅读优秀开源项目源码，共同进步', 1, 0, 0, 2),
('VibeLaunch', 'Vibe 编程助手官方交流圈', 1, 0, 0, 3),
('Vibe编程交流圈', 'Vibe 编程技巧与心得分享', 1, 0, 0, 4),
('数据标注专家社区', '数据标注行业交流与技术分享', 1, 0, 0, 5);

-- 互动交流 (category_id: 2)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('逐友请回答', '有问必答，掘金好友的问答社区', 2, 0, 0, 1),
('上班摸鱼', '摸鱼时间也要有仪式感，分享你的摸鱼趣事', 2, 0, 0, 2),
('青训营-快乐出发', '青训营学员交流与成长记录', 2, 0, 0, 3),
('AI 聊天室', 'AI 技术交流与应用分享', 2, 0, 0, 4),
('Coze 交流群', 'Coze 开发者交流与作品分享', 2, 0, 0, 5),
('飞书项目开发者社区', '飞书项目管理开发者交流', 2, 0, 0, 6);

-- 理财 (category_id: 6)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('理财交流圈', '投资理财经验分享与心得交流', 6, 0, 0, 1);

-- 许愿池 (category_id: 10)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('定个小目标', '立下你的 flag，记录实现目标的过程', 10, 0, 0, 1);

-- 情感 (category_id: 11)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('逐日相亲角', '每日推荐优质单身，缘分从这里开始', 11, 0, 0, 1),
('树洞一下', '匿名倾诉与情感宣泄的秘密基地', 11, 0, 0, 2),
('情感互助协会', '情感问题交流与互助社区', 11, 0, 0, 3);

-- 掘金一下 (category_id: 12)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('反馈&建议', '产品反馈与建议收集，掘金更好的明天', 12, 0, 0, 1),
('掘金官方', '掘金官方公告与活动发布', 12, 0, 0, 2),
('我&掘金', '我与掘金的故事，记录成长轨迹', 12, 0, 0, 3),
('沸点福利', '沸点专属福利活动中心', 12, 0, 0, 4),
('程序员搬砖人生', '程序员的日常与生活感悟', 12, 0, 0, 5),
('掘金公益角', '公益活动与社会责任分享', 12, 0, 0, 6);

-- 吃喝玩乐 (category_id: 4)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('下班去哪儿玩', '下班后的好去处推荐与分享', 4, 0, 0, 1),
('舌尖上的沸点', '美食分享与探店，吃货的天堂', 4, 0, 0, 2),
('什么值得买', '好物推荐与购物心得分享', 4, 0, 0, 3),
('活动推荐', '线上线下精彩活动汇总', 4, 0, 0, 4),
('游戏玩家俱乐部', '游戏玩家聚集地，开黑与攻略分享', 4, 0, 0, 5);

-- 书影音 (category_id: 7)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('读书会', '好书共读与读书笔记分享', 7, 0, 0, 1),
('好文推荐', '精选好文分享与阅读交流', 7, 0, 0, 2),
('一起看片', '电影、剧集推荐与观后感', 7, 0, 0, 3),
('值得收藏的歌曲', '优质音乐分享与歌单推荐', 7, 0, 0, 4);

-- 搞笑 (category_id: 9)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('搞笑段子', '每日一笑，段子手聚集地', 9, 0, 0, 1),
('沙雕表情包', '表情包大战，斗图神器合集', 9, 0, 0, 2);

-- 生活 (category_id: 8)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('照片展览馆', '摄影作品展示与拍摄技巧交流', 8, 0, 0, 1),
('今天学到了', '每日学习新技能的记录与分享', 8, 0, 0, 2),
('萌宠报道', '可爱宠物日常与养宠经验', 8, 0, 0, 3),
('体育运动俱乐部', '运动健身交流与赛事分享', 8, 0, 0, 4);

-- 资讯 (category_id: 5)
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('应用安利', '优质APP推荐与使用心得', 5, 0, 0, 1),
('今日新鲜事', '每日热点资讯与趣闻分享', 5, 0, 0, 2),
('科技交流圈', '科技前沿动态与趋势讨论', 5, 0, 0, 3);

-- 推荐圈子 (category_id: 13)
-- 将技术、职场等热门分类下的圈子也标记为推荐
INSERT INTO ap_circle (name, description, category_id, member_count, pins_count, sort_order) VALUES
('推荐-鸿蒙开发者社区', 'HarmonyOS 开发者聚集地（推荐）', 13, 0, 0, 1),
('推荐-源码共读', '一起阅读优秀开源项目源码（推荐）', 13, 0, 0, 2),
('推荐-逐友请回答', '有问必答，掘金好友的问答社区（推荐）', 13, 0, 0, 3),
('推荐-掘金官方', '掘金官方公告与活动发布（推荐）', 13, 0, 0, 4),
('推荐-反馈&建议', '产品反馈与建议收集（推荐）', 13, 0, 0, 5);
