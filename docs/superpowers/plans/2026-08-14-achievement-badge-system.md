# 成就勋章系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增成就勋章系统，在个人主页头部展示等级徽章与勋章墙（仅荣誉展示，实时计算解锁状态）。

**Architecture:** 新增勋章定义表 `ap_achievement`（11 枚静态勋章种子数据 + 2 枚动态等级徽章），content 服务新增 `AchievementService` 实时聚合各维度统计（文章/沸点/获赞/粉丝/连续签到/等级）判定解锁状态；连续签到天数通过 Feign 从 reward 服务获取；新增公开只读接口 `GET /content/api/v1/user/{userId}/achievements`；个人主页头部改造为等级徽章 + 勋章入口 + 勋章墙弹窗。

**Tech Stack:** Java 21 + Spring Boot 3 + MyBatis-Plus + OpenFeign；Vue 2.7 + Element UI + Less；MySQL（leadnews_article / leadnews_reward）。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/create_ap_achievement_table.sql` | 勋章定义表 DDL + 11 枚种子数据（新建） |
| `heima-leadnews-service/heima-leadnews-content/src/main/resources/db/schema.sql` | 全量结构汇总（重新导出） |
| `heima-leadnews-model/.../com/heima/model/achievement/pojos/ApAchievement.java` | 勋章定义实体（新建） |
| `heima-leadnews-model/.../com/heima/model/achievement/vos/AchievementItemVO.java` | 单枚静态勋章返回结构（新建） |
| `heima-leadnews-model/.../com/heima/model/achievement/vos/AchievementLevelVO.java` | 等级徽章返回结构（新建） |
| `heima-leadnews-model/.../com/heima/model/achievement/vos/AchievementDataVO.java` | 成就接口整体返回结构（新建） |
| `heima-leadnews-content/.../mapper/achievement/ApAchievementMapper.java` | 勋章定义 Mapper（新建） |
| `heima-leadnews-feign-api/.../com/heima/apis/reward/IRewardClient.java` | 新增连续签到天数 Feign 方法（修改） |
| `heima-leadnews-feign-api/.../com/heima/apis/reward/fallback/IRewardClientFallback.java` | 新增降级实现（修改） |
| `heima-leadnews-reward/.../service/CheckinService.java` | 新增连续签到天数方法声明（修改） |
| `heima-leadnews-reward/.../service/impl/CheckinServiceImpl.java` | 新增连续签到天数实现（修改） |
| `heima-leadnews-reward/.../controller/v1/RewardCheckinFeignController.java` | reward 服务 Feign 暴露连续签到天数（新建） |
| `heima-leadnews-content/.../service/achievement/AchievementService.java` | 成就判定服务接口（新建） |
| `heima-leadnews-content/.../service/achievement/impl/AchievementServiceImpl.java` | 成就判定服务实现（新建） |
| `heima-leadnews-content/.../controller/v1/user/AchievementController.java` | 成就接口控制器（新建） |
| `heima-leadnews-gateway/heima-leadnews-app-gateway/.../filter/AuthorizeFilter.java` | 网关公开路径放行成就接口（修改） |
| `heima-leadnews-content/.../service/article/impl/ArticleStatisticsServiceImpl.java` | badgeCount 由恒 0 改为真实已解锁数（修改） |
| `heima-leadnews-content/src/test/java/com/heima/content/service/achievement/impl/AchievementServiceImplTest.java` | 成就判定单测（新建） |
| `src/apis/achievement.js` | 前端成就接口封装（新建） |
| `src/pages/user/index.vue` | 个人主页头部等级徽章 + 勋章入口 + 勋章墙弹窗（修改） |

---

## Task 1: 数据库迁移 — ap_achievement 表 + 种子数据

**Files:**
- Create: `heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/create_ap_achievement_table.sql`
- Modify (重新导出): `heima-leadnews-service/heima-leadnews-content/src/main/resources/db/schema.sql`

- [ ] **Step 1: 编写迁移脚本**

新建文件 `heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/create_ap_achievement_table.sql`：

```sql
-- =====================================================
-- 成就勋章定义表迁移脚本
-- ap_achievement：勋章定义表 + 11 枚静态勋章种子数据
-- 2 枚等级徽章（逐友/逐力值）不落库，由服务动态构造
-- =====================================================

CREATE TABLE `ap_achievement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code` varchar(64) NOT NULL COMMENT '勋章唯一编码',
  `name` varchar(64) NOT NULL COMMENT '勋章名称',
  `category` tinyint NOT NULL DEFAULT '2' COMMENT '分类：1=新人成长，2=活跃成就',
  `icon` varchar(16) NOT NULL DEFAULT '' COMMENT '图标（emoji字符）',
  `description` varchar(200) NOT NULL DEFAULT '' COMMENT '解锁条件文案',
  `trigger_type` varchar(32) NOT NULL COMMENT '触发类型：publish_article/publish_content/checkin_streak/likes/followers',
  `threshold` int NOT NULL DEFAULT '0' COMMENT '解锁阈值',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '展示排序',
  `is_active` tinyint NOT NULL DEFAULT '1' COMMENT '是否启用：1=启用 0=禁用',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成就勋章定义表';

-- 种子数据：新人成长类（2枚）
INSERT INTO `ap_achievement` (`code`,`name`,`category`,`icon`,`description`,`trigger_type`,`threshold`,`sort_order`) VALUES
('first_content','初来乍到',1,'🚀','首次发布文章或沸点','publish_content',1,1),
('checkin_streak_30','连续签到30天',1,'📅','连续签到满30天','checkin_streak',30,2);

-- 种子数据：活跃成就类（9枚）
INSERT INTO `ap_achievement` (`code`,`name`,`category`,`icon`,`description`,`trigger_type`,`threshold`,`sort_order`) VALUES
('publish_10','笔耕不辍',2,'✍️','累计发布文章10篇','publish_article',10,3),
('publish_50','创作达人',2,'📚','累计发布文章50篇','publish_article',50,4),
('publish_100','大神作家',2,'👑','累计发布文章100篇','publish_article',100,5),
('likes_100','初获认可',2,'👍','文章累计获赞100','likes',100,6),
('likes_1000','广受好评',2,'🌟','文章累计获赞1000','likes',1000,7),
('likes_10000','万人追捧',2,'🔥','文章累计获赞10000','likes',10000,8),
('followers_100','小有名气',2,'💡','粉丝数达到100','followers',100,9),
('followers_500','人气爆棚',2,'🎉','粉丝数达到500','followers',500,10),
('followers_1000','顶流作家',2,'🏆','粉丝数达到1000','followers',1000,11);
```

- [ ] **Step 2: 执行迁移脚本（用 mysql source 保留中文编码）**

在 PowerShell 中执行（库名 `leadnews_article`）：

```powershell
mysql -h 127.0.0.1 -u root -p123456 --default-character-set=utf8mb4 leadnews_article -e "source e:/heima-leadnews-portal/heima-leadnews-app/heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/create_ap_achievement_table.sql"
```

验证：

```powershell
mysql -h 127.0.0.1 -u root -p123456 --default-character-set=utf8mb4 leadnews_article -e "SELECT code,name,category,threshold FROM ap_achievement ORDER BY sort_order;"
```

Expected: 11 行种子数据，中文名称正常显示（无乱码）。

- [ ] **Step 3: 重新导出 schema.sql**

```powershell
mysqldump -h 127.0.0.1 -u root -p123456 --no-data --skip-comments --skip-add-drop-table --default-character-set=utf8mb4 leadnews_article > heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/db/schema.sql
```

Expected: `schema.sql` 末尾包含 `ap_achievement` 建表语句（只读汇总，不手动改）。

- [ ] **Step 4: Commit**

```bash
git add heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/db
git commit -m "feat(achievement): add ap_achievement definition table and seed data"
```

---

## Task 2: 模型 — ApAchievement 实体 + 成就 VO

**Files:**
- Create: `heima-leadnews-model/src/main/java/com/heima/model/achievement/pojos/ApAchievement.java`
- Create: `heima-leadnews-model/src/main/java/com/heima/model/achievement/vos/AchievementItemVO.java`
- Create: `heima-leadnews-model/src/main/java/com/heima/model/achievement/vos/AchievementLevelVO.java`
- Create: `heima-leadnews-model/src/main/java/com/heima/model/achievement/vos/AchievementDataVO.java`

- [ ] **Step 1: 创建实体 ApAchievement**

```java
package com.heima.model.achievement.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 成就勋章定义表（静态勋章，等级徽章不落库）
 */
@Data
@TableName("ap_achievement")
public class ApAchievement implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 勋章唯一编码 */
    @TableField("code")
    private String code;

    /** 勋章名称 */
    @TableField("name")
    private String name;

    /** 分类：1=新人成长，2=活跃成就 */
    @TableField("category")
    private Integer category;

    /** 图标（emoji 字符） */
    @TableField("icon")
    private String icon;

    /** 解锁条件文案 */
    @TableField("description")
    private String description;

    /** 触发类型：publish_article/publish_content/checkin_streak/likes/followers */
    @TableField("trigger_type")
    private String triggerType;

    /** 解锁阈值 */
    @TableField("threshold")
    private Integer threshold;

    /** 展示排序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 是否启用：1=启用 0=禁用 */
    @TableField("is_active")
    private Boolean isActive;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}
```

- [ ] **Step 2: 创建 AchievementItemVO**

```java
package com.heima.model.achievement.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 单枚静态勋章展示结构（字段值严禁为 null，遵循全局序列化规范）
 */
@Data
public class AchievementItemVO implements Serializable {

    /** 勋章唯一编码 */
    private String code = "";

    /** 勋章名称 */
    private String name = "";

    /** 分类：1=新人成长，2=活跃成就 */
    private Integer category = 2;

    /** 图标（emoji 字符） */
    private String icon = "";

    /** 解锁条件文案 */
    private String description = "";

    /** 是否已解锁 */
    private Boolean unlocked = false;

    /** 当前进度值 */
    private Long progress = 0L;

    /** 解锁阈值 */
    private Integer threshold = 0;
}
```

- [ ] **Step 3: 创建 AchievementLevelVO**

```java
package com.heima.model.achievement.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 等级徽章展示结构（逐友等级 / 逐力值等级，动态展示当前等级）
 */
@Data
public class AchievementLevelVO implements Serializable {

    /** 等级类型：daily=逐友等级，power=逐力值等级 */
    private String type = "";

    /** 等级名称（如 逐友等级） */
    private String name = "";

    /** 当前等级值 */
    private Integer level = 1;

    /** 等级名（如 见习掘友） */
    private String levelTitle = "";
}
```

- [ ] **Step 4: 创建 AchievementDataVO**

```java
package com.heima.model.achievement.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 成就接口整体返回结构
 */
@Data
public class AchievementDataVO implements Serializable {

    /** 已解锁静态勋章数 */
    private Integer unlockedCount = 0;

    /** 勋章总数（静态勋章 + 等级徽章） */
    private Integer totalCount = 0;

    /** 静态勋章列表（11 枚） */
    private List<AchievementItemVO> list = new ArrayList<>();

    /** 等级徽章列表（2 枚） */
    private List<AchievementLevelVO> levels = new ArrayList<>();
}
```

- [ ] **Step 5: 编译验证**

```powershell
mvn -q -pl heima-leadnews-model -am compile
```

Expected: BUILD SUCCESS（无编译错误）。

- [ ] **Step 6: Commit**

```bash
git add heima-leadnews/heima-leadnews-model/src/main/java/com/heima/model/achievement
git commit -m "feat(achievement): add ApAchievement entity and achievement VOs"
```

---

## Task 3: Mapper — ApAchievementMapper

**Files:**
- Create: `heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/mapper/achievement/ApAchievementMapper.java`

- [ ] **Step 1: 创建 Mapper**

```java
package com.heima.content.mapper.achievement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.achievement.pojos.ApAchievement;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApAchievementMapper extends BaseMapper<ApAchievement> {
}
```

- [ ] **Step 2: 编译验证**

```powershell
mvn -q -pl heima-leadnews-service/heima-leadnews-content -am compile
```

Expected: BUILD SUCCESS。

---

## Task 4: Reward 服务 — 暴露连续签到天数 Feign 接口

**Files:**
- Modify: `heima-leadnews-feign-api/src/main/java/com/heima/apis/reward/IRewardClient.java`
- Modify: `heima-leadnews-feign-api/src/main/java/com/heima/apis/reward/fallback/IRewardClientFallback.java`
- Modify: `heima-leadnews-service/heima-leadnews-reward/src/main/java/com/heima/reward/service/CheckinService.java`
- Modify: `heima-leadnews-service/heima-leadnews-reward/src/main/java/com/heima/reward/service/impl/CheckinServiceImpl.java`
- Create: `heima-leadnews-service/heima-leadnews-reward/src/main/java/com/heima/reward/controller/v1/RewardCheckinFeignController.java`

- [ ] **Step 1: 在 IRewardClient 增加方法**

在 `com.heima.apis.reward.IRewardClient` 接口末尾追加：

```java
    /**
     * 获取用户连续签到天数（成就勋章-连续签到30天判定用）
     */
    @GetMapping("/api/v1/reward/user/{userId}/checkin/continuous")
    ResponseResult getContinuousCheckinDays(@PathVariable("userId") Long userId);
```

- [ ] **Step 2: 在 IRewardClientFallback 增加降级**

在 `com.heima.apis.reward.fallback.IRewardClientFallback` 类末尾追加：

```java
    @Override
    public ResponseResult getContinuousCheckinDays(Long userId) {
        log.error("奖励服务不可用，获取连续签到天数失败，userId={}", userId);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("continuousDays", 0);
        return ResponseResult.okResult(result);
    }
```

- [ ] **Step 3: 在 CheckinService 增加方法声明**

在 `com.heima.reward.service.CheckinService` 接口末尾追加：

```java
    /** 获取用户连续签到天数（含今日，供其他服务 Feign 调用） */
    ResponseResult getContinuousCheckinDays(Long userId);
```

- [ ] **Step 4: 在 CheckinServiceImpl 实现**

在 `com.heima.reward.service.impl.CheckinServiceImpl` 中（`getTodayStatus` 方法之后）新增：

```java
    /**
     * 获取用户连续签到天数（含今日，供其他服务 Feign 调用，成就勋章判定用）
     */
    @Override
    public ResponseResult getContinuousCheckinDays(Long userId) {
        LocalDate today = getToday();
        boolean todaySigned = signRecordMapper.selectCount(
                new LambdaQueryWrapper<SignRecord>()
                        .eq(SignRecord::getUserId, userId)
                        .eq(SignRecord::getSignDate, today)
        ) > 0;
        int continuousDays = calculateContinuousDays(userId, today);
        int display = todaySigned ? continuousDays + 1 : continuousDays;

        Map<String, Object> data = new HashMap<>();
        data.put("continuousDays", display);
        return ResponseResult.okResult(data);
    }
```

- [ ] **Step 5: 新建 RewardCheckinFeignController**

```java
package com.heima.reward.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.reward.service.CheckinService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签到连续天数 Feign 接口（供其他服务远程调用，如成就勋章判定）
 */
@RestController
@RequestMapping("/api/v1/reward")
public class RewardCheckinFeignController {

    @Autowired
    private CheckinService checkinService;

    /** 获取用户连续签到天数（含今日） */
    @GetMapping("/user/{userId}/checkin/continuous")
    public ResponseResult getContinuousCheckinDays(@PathVariable("userId") Long userId) {
        return checkinService.getContinuousCheckinDays(userId);
    }
}
```

- [ ] **Step 6: 编译验证**

```powershell
mvn -q -pl heima-leadnews-feign-api,heima-leadnews-service/heima-leadnews-reward -am compile
```

Expected: BUILD SUCCESS。

---

## Task 5: 服务 — AchievementService 实现

**Files:**
- Create: `heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/service/achievement/AchievementService.java`
- Create: `heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/service/achievement/impl/AchievementServiceImpl.java`

- [ ] **Step 1: 创建接口**

```java
package com.heima.content.service.achievement;

import com.heima.model.achievement.vos.AchievementDataVO;

/**
 * 成就勋章判定服务 — 实时统计各维度数据与 ap_achievement 定义比对得出解锁状态
 */
public interface AchievementService {

    /**
     * 获取用户成就勋章（11 枚静态勋章 + 2 枚等级徽章）
     *
     * @param userId 目标用户ID
     * @return 勋章数据（已解锁数/总数/列表/等级徽章）
     */
    AchievementDataVO getUserAchievements(Long userId);
}
```

- [ ] **Step 2: 创建实现类**

```java
package com.heima.content.service.achievement.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.reward.IRewardClient;
import com.heima.content.mapper.achievement.ApAchievementMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.achievement.AchievementService;
import com.heima.content.service.level.LevelService;
import com.heima.model.achievement.pojos.ApAchievement;
import com.heima.model.achievement.vos.AchievementDataVO;
import com.heima.model.achievement.vos.AchievementItemVO;
import com.heima.model.achievement.vos.AchievementLevelVO;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.pojos.ApBehaviorLikes;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.pins.pojos.ApPins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 成就勋章判定服务实现
 * 统计口径与个人主页一致：获赞用 ap_behavior_likes、粉丝用 ap_user_follow、签到用 reward 服务
 */
@Slf4j
@Service
public class AchievementServiceImpl implements AchievementService {

    @Autowired
    private ApAchievementMapper achievementMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private LevelService levelService;

    @Autowired
    private IRewardClient rewardClient;

    @Override
    public AchievementDataVO getUserAchievements(Long userId) {
        // 1. 采集各维度统计值（批量聚合，避免 N+1）
        long publishedArticles = apArticleMapper.selectCount(
                new LambdaQueryWrapper<ApArticle>()
                        .eq(ApArticle::getAuthorId, userId)
                        .eq(ApArticle::getIsDeleted, false));
        long publishedPins = apPinsMapper.selectCount(
                new LambdaQueryWrapper<ApPins>()
                        .eq(ApPins::getAuthorId, userId)
                        .eq(ApPins::getIsDeleted, false));
        long articleLikes = calcArticleLikes(userId);
        long followers = apFollowMapper.selectCount(
                new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getFollowUserId, userId));
        int checkinStreak = getCheckinStreak(userId);

        // 2. 读取勋章定义并按序组装解锁状态
        List<ApAchievement> definitions = achievementMapper.selectList(
                new LambdaQueryWrapper<ApAchievement>()
                        .eq(ApAchievement::getIsActive, true)
                        .orderByAsc(ApAchievement::getSortOrder));
        List<AchievementItemVO> list = new ArrayList<>();
        long unlockedCount = 0;
        for (ApAchievement def : definitions) {
            long progress = resolveProgress(def.getTriggerType(),
                    publishedArticles, publishedPins, articleLikes, followers, checkinStreak);
            boolean unlocked = progress >= def.getThreshold();
            if (unlocked) {
                unlockedCount++;
            }
            list.add(toItem(def, progress, unlocked));
        }

        // 3. 等级徽章（复用等级服务，动态展示当前等级）
        List<AchievementLevelVO> levels = buildLevels(userId);

        // 4. 组装返回
        AchievementDataVO data = new AchievementDataVO();
        data.setUnlockedCount((int) unlockedCount);
        data.setTotalCount(list.size() + levels.size());
        data.setList(list);
        data.setLevels(levels);
        return data;
    }

    /**
     * 计算用户所有文章（未删除）累计获赞数
     * 一次性查出文章集合再按 entryId 批量统计，避免 N+1
     */
    private long calcArticleLikes(Long userId) {
        List<ApArticle> articles = apArticleMapper.selectList(
                new LambdaQueryWrapper<ApArticle>()
                        .eq(ApArticle::getAuthorId, userId)
                        .eq(ApArticle::getIsDeleted, false));
        if (articles == null || articles.isEmpty()) {
            return 0L;
        }
        List<Long> articleIds = articles.stream()
                .map(ApArticle::getId)
                .collect(Collectors.toList());
        return apBehaviorLikesMapper.selectCount(
                new LambdaQueryWrapper<ApBehaviorLikes>()
                        .in(ApBehaviorLikes::getEntryId, articleIds)
                        .eq(ApBehaviorLikes::getType, 0)
                        .eq(ApBehaviorLikes::getOperation, 0));
    }

    /**
     * 获取连续签到天数（远程调用 reward 服务，失败时降级为 0）
     */
    private int getCheckinStreak(Long userId) {
        try {
            ResponseResult res = rewardClient.getContinuousCheckinDays(userId);
            if (res != null && res.getCode() == 200 && res.getData() instanceof Map) {
                Object val = ((Map<?, ?>) res.getData()).get("continuousDays");
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        } catch (Exception e) {
            log.warn("获取连续签到天数失败，userId={}, error={}", userId, e.getMessage());
        }
        return 0;
    }

    /**
     * 按触发类型解析当前进度值
     */
    private long resolveProgress(String triggerType, long publishedArticles, long publishedPins,
                                 long articleLikes, long followers, int checkinStreak) {
        if ("publish_article".equals(triggerType)) {
            return publishedArticles;
        }
        if ("publish_content".equals(triggerType)) {
            return publishedArticles + publishedPins;
        }
        if ("checkin_streak".equals(triggerType)) {
            return checkinStreak;
        }
        if ("likes".equals(triggerType)) {
            return articleLikes;
        }
        if ("followers".equals(triggerType)) {
            return followers;
        }
        return 0L;
    }

    /**
     * 将定义记录转为返回 VO
     */
    private AchievementItemVO toItem(ApAchievement def, long progress, boolean unlocked) {
        AchievementItemVO vo = new AchievementItemVO();
        vo.setCode(def.getCode());
        vo.setName(def.getName());
        vo.setCategory(def.getCategory());
        vo.setIcon(def.getIcon());
        vo.setDescription(def.getDescription());
        vo.setUnlocked(unlocked);
        vo.setProgress(progress);
        vo.setThreshold(def.getThreshold());
        return vo;
    }

    /**
     * 动态构造两枚等级徽章（逐友/逐力值），复用 getUserLevelInfo
     */
    private List<AchievementLevelVO> buildLevels(Long userId) {
        List<AchievementLevelVO> levels = new ArrayList<>();
        try {
            Map<String, Object> levelInfo = levelService.getUserLevelInfo(userId);
            levels.add(new AchievementLevelVO() {{
                setType("daily");
                setName("逐友等级");
                setLevel(toInt(levelInfo.get("dailyLevel"), 1));
                setLevelTitle(str(levelInfo.get("dailyTitle")));
            }});
            levels.add(new AchievementLevelVO() {{
                setType("power");
                setName("逐力值等级");
                setLevel(toInt(levelInfo.get("powerLevel"), 1));
                setLevelTitle(str(levelInfo.get("powerTitle")));
            }});
        } catch (Exception e) {
            log.warn("获取用户等级信息失败，userId={}, error={}", userId, e.getMessage());
            levels.add(new AchievementLevelVO() {{
                setType("daily");
                setName("逐友等级");
                setLevel(1);
                setLevelTitle("");
            }});
            levels.add(new AchievementLevelVO() {{
                setType("power");
                setName("逐力值等级");
                setLevel(1);
                setLevelTitle("");
            }});
        }
        return levels;
    }

    private int toInt(Object val, int defaultValue) {
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private String str(Object val) {
        return val != null ? val.toString() : "";
    }
}
```

- [ ] **Step 3: 编译验证**

```powershell
mvn -q -pl heima-leadnews-service/heima-leadnews-content -am compile
```

Expected: BUILD SUCCESS。

---

## Task 6: 接口 — AchievementsController + 网关公开放行

**Files:**
- Create: `heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/controller/v1/user/AchievementController.java`
- Modify: `heima-leadnews-gateway/heima-leadnews-app-gateway/src/main/java/com/heima/app/gateway/filter/AuthorizeFilter.java`

- [ ] **Step 1: 创建控制器**

```java
package com.heima.content.controller.v1.user;

import com.heima.content.service.achievement.AchievementService;
import com.heima.model.achievement.vos.AchievementDataVO;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成就勋章接口（公开只读，未登录也可浏览他人主页勋章，利于社区展示）
 */
@RestController
@RequestMapping("/api/v1/user")
public class AchievementController {

    @Autowired
    private AchievementService achievementService;

    /** GET /api/v1/user/{userId}/achievements */
    @GetMapping("/{userId}/achievements")
    public ResponseResult<AchievementDataVO> getAchievements(@PathVariable Long userId) {
        return ResponseResult.okResult(achievementService.getUserAchievements(userId));
    }
}
```

- [ ] **Step 2: 网关公开放行**

在 `AuthorizeFilter.isPublicPath(String path)` 的 `return` 表达式末尾（`|| path.startsWith("/content/api/v1/course/detail");` 之前）追加一行：

```java
            // 成就勋章公开只读接口（未登录也可浏览他人主页勋章）
            || path.matches("/content/api/v1/user/\\d+/achievements")
```

- [ ] **Step 3: 编译验证**

```powershell
mvn -q -pl heima-leadnews-service/heima-leadnews-content,heima-leadnews-gateway/heima-leadnews-app-gateway -am compile
```

Expected: BUILD SUCCESS。

---

## Task 7: 改造 badgeCount — ArticleStatisticsServiceImpl

**Files:**
- Modify: `heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/service/article/impl/ArticleStatisticsServiceImpl.java`

- [ ] **Step 1: 注入 AchievementService**

在类中（`@Autowired private LevelService levelService;` 之后）新增：

```java
    @Autowired
    private AchievementService achievementService;
```

并在文件头部 import 区域新增：

```java
import com.heima.content.service.achievement.AchievementService;
```

- [ ] **Step 2: badgeCount 由恒 0 改为真实已解锁勋章数**

将 `getUserStatistics` 中的：

```java
        // 8. badgeCount（徽章数，暂未实现徽章系统）
        result.put("badgeCount", 0);
```

替换为：

```java
        // 8. badgeCount（已解锁成就勋章数）
        try {
            result.put("badgeCount", achievementService.getUserAchievements(userId).getUnlockedCount());
        } catch (Exception e) {
            log.warn("获取用户成就勋章数失败，userId={}, error={}", userId, e.getMessage());
            result.put("badgeCount", 0);
        }
```

- [ ] **Step 3: 编译验证**

```powershell
mvn -q -pl heima-leadnews-service/heima-leadnews-content -am compile
```

Expected: BUILD SUCCESS。

---

## Task 8: 单元测试 — AchievementServiceImplTest

**Files:**
- Create: `heima-leadnews-service/heima-leadnews-content/src/test/java/com/heima/content/service/achievement/impl/AchievementServiceImplTest.java`

- [ ] **Step 1: 编写测试（Mockito 单测，参照既有 ArticleInteractionControllerTest 风格）**

```java
package com.heima.content.service.achievement.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.heima.apis.reward.IRewardClient;
import com.heima.content.mapper.achievement.ApAchievementMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.level.LevelService;
import com.heima.model.achievement.pojos.ApAchievement;
import com.heima.model.achievement.vos.AchievementDataVO;
import com.heima.model.common.dtos.ResponseResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AchievementServiceImplTest {

    @Mock
    private ApAchievementMapper achievementMapper;
    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApPinsMapper apPinsMapper;
    @Mock
    private ApBehaviorLikesMapper apBehaviorLikesMapper;
    @Mock
    private ApFollowMapper apFollowMapper;
    @Mock
    private LevelService levelService;
    @Mock
    private IRewardClient rewardClient;

    @InjectMocks
    private AchievementServiceImpl achievementService;

    private ApAchievement def(String code, String triggerType, int threshold) {
        ApAchievement d = new ApAchievement();
        d.setCode(code);
        d.setName(code);
        d.setCategory(2);
        d.setIcon("x");
        d.setDescription("desc");
        d.setTriggerType(triggerType);
        d.setThreshold(threshold);
        return d;
    }

    private Map<String, Object> defaultLevelInfo() {
        Map<String, Object> m = new HashMap<>();
        m.put("dailyLevel", 2);
        m.put("dailyTitle", "见习掘友");
        m.put("powerLevel", 1);
        m.put("powerTitle", "新秀");
        return m;
    }

    private void stubBase() {
        when(apArticleMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(apPinsMapper.selectCount(any())).thenReturn(0L);
        when(apFollowMapper.selectCount(any())).thenReturn(0L);
        when(levelService.getUserLevelInfo(anyLong())).thenReturn(defaultLevelInfo());
        Map<String, Object> streak = new HashMap<>();
        streak.put("continuousDays", 0);
        when(rewardClient.getContinuousCheckinDays(anyLong())).thenReturn(ResponseResult.okResult(streak));
    }

    @Test
    @DisplayName("初来乍到：发布1篇文章即解锁，进度=1")
    void firstContentUnlockedWhenOneArticle() {
        List<ApAchievement> defs = new ArrayList<>();
        defs.add(def("first_content", "publish_content", 1));
        when(achievementMapper.selectList(any())).thenReturn(defs);
        when(apArticleMapper.selectCount(any())).thenReturn(1L);
        stubBase();

        AchievementDataVO vo = achievementService.getUserAchievements(1001L);

        assertEquals(1, vo.getList().size());
        assertTrue(vo.getList().get(0).getUnlocked());
        assertEquals(1L, vo.getList().get(0).getProgress());
        assertEquals(1, vo.getUnlockedCount());
        assertEquals(2, vo.getLevels().size());
    }

    @Test
    @DisplayName("笔耕不辍：文章数不足阈值不解锁，进度保留")
    void publishTenNotUnlockedWhenFiveArticles() {
        List<ApAchievement> defs = new ArrayList<>();
        defs.add(def("publish_10", "publish_article", 10));
        when(achievementMapper.selectList(any())).thenReturn(defs);
        when(apArticleMapper.selectCount(any())).thenReturn(5L);
        stubBase();

        AchievementDataVO vo = achievementService.getUserAchievements(1001L);

        assertEquals(1, vo.getList().size());
        assertFalse(vo.getList().get(0).getUnlocked());
        assertEquals(5L, vo.getList().get(0).getProgress());
        assertEquals(0, vo.getUnlockedCount());
    }

    @Test
    @DisplayName("连续签到30天：reward 返回连续35天时解锁")
    void checkinStreakUnlocked() {
        List<ApAchievement> defs = new ArrayList<>();
        defs.add(def("checkin_streak_30", "checkin_streak", 30));
        when(achievementMapper.selectList(any())).thenReturn(defs);
        when(apArticleMapper.selectCount(any())).thenReturn(0L);
        stubBase();
        Map<String, Object> streak = new HashMap<>();
        streak.put("continuousDays", 35);
        when(rewardClient.getContinuousCheckinDays(anyLong())).thenReturn(ResponseResult.okResult(streak));

        AchievementDataVO vo = achievementService.getUserAchievements(1001L);

        assertTrue(vo.getList().get(0).getUnlocked());
        assertEquals(35L, vo.getList().get(0).getProgress());
    }
}
```

- [ ] **Step 2: 运行测试**

```powershell
mvn -q -pl heima-leadnews-service/heima-leadnews-content -am test -Dtest=AchievementServiceImplTest
```

Expected: `Tests run: 3, Failures: 0`（BUILD SUCCESS）。

---

## Task 9: 前端 API 封装

**Files:**
- Create: `src/apis/achievement.js`

- [ ] **Step 1: 创建接口封装**

```js
import request from '@/common/article_request'

/**
 * 获取用户成就勋章（11 枚静态勋章 + 2 枚等级徽章）
 * 公开只读接口，支持匿名访问他人主页
 * @param {number|string} userId 目标用户ID
 */
export const getUserAchievements = (userId) => {
  return request.get(`/api/v1/user/${userId}/achievements`)
}
```

---

## Task 10: 前端个人主页 — 等级徽章 + 勋章入口 + 勋章墙弹窗

**Files:**
- Modify: `src/pages/user/index.vue`

- [ ] **Step 1: 引入接口**

在 `<script>` 的 import 区域（`import { getUserStatistics } from '@/apis/user'` 之后）新增：

```js
import { getUserAchievements } from '@/apis/achievement'
```

- [ ] **Step 2: data 增加勋章数据**

在 `data()` 的 `levelInfo` 之后新增：

```js
            achievementDialog: false,
            achievements: {
                unlockedCount: 0,
                totalCount: 0,
                list: [],
                levels: []
            },
```

- [ ] **Step 3: computed 增加等级徽章取值**

在 `computed` 的 `profileUserId` 之后新增：

```js
        // 逐友等级徽章（取自成就接口，动态展示当前等级）
        dailyLevelBadge() {
            return this.achievements.levels.find(l => l.type === 'daily') || null
        },
        // 逐力值等级徽章
        powerLevelBadge() {
            return this.achievements.levels.find(l => l.type === 'power') || null
        }
```

- [ ] **Step 4: 模板 — 替换头部等级占位**

将：

```html
                        <div class="user-name">{{ userInfo.nickName || '用户' }}</div>
                        <div class="user-level">掘友等级 Lv.2</div>
```

替换为：

```html
                        <div class="user-name">{{ userInfo.nickName || '用户' }}</div>
                        <div class="user-level-row">
                            <span class="user-level" v-if="dailyLevelBadge">
                                {{ dailyLevelBadge.name }} Lv.{{ dailyLevelBadge.level }}<template v-if="dailyLevelBadge.levelTitle"> · {{ dailyLevelBadge.levelTitle }}</template>
                            </span>
                            <span class="user-level power" v-if="powerLevelBadge">
                                {{ powerLevelBadge.name }} Lv.{{ powerLevelBadge.level }}<template v-if="powerLevelBadge.levelTitle"> · {{ powerLevelBadge.levelTitle }}</template>
                            </span>
                        </div>
```

- [ ] **Step 5: 模板 — 勋章入口可点击**

将：

```html
                            <span class="stat-item">
                                <span class="stat-num">{{ stats.badgeCount }}</span>
                                <span class="stat-text">获得徽章</span>
                            </span>
```

替换为：

```html
                            <span class="stat-item badge-entry" @click="openAchievementDialog">
                                <span class="stat-num">{{ stats.badgeCount }}/11</span>
                                <span class="stat-text">勋章</span>
                            </span>
```

- [ ] **Step 6: 模板 — 新增勋章墙弹窗**

在第二个 `</el-dialog>`（收藏集详情弹窗，line ~418）之后、`</div></template>` 之前新增：

```html
        <el-dialog
            title="我的勋章"
            :visible.sync="achievementDialog"
            width="720px"
            custom-class="achievement-dialog"
        >
            <div class="achievement-wall">
                <div class="ach-level-section" v-if="achievements.levels.length">
                    <div class="ach-level-card" v-for="lv in achievements.levels" :key="lv.type">
                        <div class="ach-level-icon">{{ lv.type === 'daily' ? '☀️' : '💪' }}</div>
                        <div class="ach-level-info">
                            <div class="ach-level-name">{{ lv.name }}</div>
                            <div class="ach-level-title">{{ lv.levelTitle || (lv.name + ' Lv.' + lv.level) }}</div>
                            <div class="ach-level-value">Lv.{{ lv.level }}</div>
                        </div>
                    </div>
                </div>
                <div class="ach-grid">
                    <div class="ach-item" :class="{ unlocked: item.unlocked }" v-for="item in achievements.list" :key="item.code">
                        <div class="ach-icon">{{ item.icon }}</div>
                        <div class="ach-name">{{ item.name }}</div>
                        <div class="ach-desc">{{ item.description }}</div>
                        <div class="ach-progress" v-if="item.unlocked">已解锁</div>
                        <div class="ach-progress locked" v-else>{{ item.progress }}/{{ item.threshold }}</div>
                    </div>
                </div>
            </div>
        </el-dialog>
```

- [ ] **Step 7: methods — 加载勋章 + 打开弹窗**

在 `mounted()` 的 `this.loadUserData()` 对应位置，`loadUserData` 方法内 `this.loadTabContent()` 之前新增调用：

```js
            // 加载成就勋章（当前浏览用户）
            this.fetchAchievements()
```

在 `methods` 中 `loadTabContent` 方法之前新增两个方法：

```js
        async fetchAchievements() {
            try {
                const userId = this.profileUserId
                if (!userId) return
                const res = await getUserAchievements(userId)
                if (res && res.code === 200 && res.data) {
                    this.achievements = {
                        unlockedCount: res.data.unlockedCount || 0,
                        totalCount: res.data.totalCount || 0,
                        list: res.data.list || [],
                        levels: res.data.levels || []
                    }
                }
            } catch (e) {
                // 接口失败时保留默认值，不影响页面浏览
            }
        },
        openAchievementDialog() {
            this.achievementDialog = true
        },
```

- [ ] **Step 8: 样式 — 等级徽章、勋章入口、勋章墙**

在 `<style lang="less" scoped>` 末尾（`.user-level` 样式之后 / 文件末尾）追加：

```less
.user-level-row {
    display: flex;
    gap: 8px;
    margin-bottom: 12px;
}

.user-level {
    font-size: 12px;
    color: #1e80ff;
    background: #eaf2ff;
    padding: 2px 8px;
    border-radius: 4px;
    display: inline-block;

    &.power {
        color: #9a6700;
        background: #fdf4df;
    }
}

.stat-item.badge-entry {
    cursor: pointer;
    transition: opacity .2s;

    &:hover {
        opacity: .7;
    }
}

.achievement-wall {
    .ach-level-section {
        display: flex;
        gap: 12px;
        margin-bottom: 20px;

        .ach-level-card {
            flex: 1;
            display: flex;
            align-items: center;
            gap: 12px;
            background: linear-gradient(135deg, #f0f6ff 0%, #eaf2ff 100%);
            border-radius: 8px;
            padding: 16px;

            .ach-level-icon {
                font-size: 32px;
            }

            .ach-level-info {
                flex: 1;

                .ach-level-name {
                    font-size: 14px;
                    font-weight: 600;
                    color: #252933;
                }

                .ach-level-title {
                    font-size: 12px;
                    color: #515767;
                    margin-top: 4px;
                }

                .ach-level-value {
                    font-size: 12px;
                    font-weight: 600;
                    color: #1e80ff;
                    margin-top: 4px;
                }
            }
        }
    }

    .ach-grid {
        display: grid;
        grid-template-columns: repeat(4, 1fr);
        gap: 16px;

        .ach-item {
            text-align: center;
            padding: 16px 8px;
            border-radius: 8px;
            background: #fff;
            border: 1px solid #f2f3f5;
            transition: all .2s;

            &:hover {
                box-shadow: 0 4px 12px rgba(0, 0, 0, .06);
            }

            .ach-icon {
                font-size: 36px;
                margin-bottom: 8px;
            }

            .ach-name {
                font-size: 14px;
                font-weight: 600;
                color: #252933;
                margin-bottom: 4px;
            }

            .ach-desc {
                font-size: 12px;
                color: #8a919f;
                margin-bottom: 8px;
            }

            .ach-progress {
                display: inline-block;
                font-size: 12px;
                color: #1e80ff;
                background: #eaf2ff;
                padding: 2px 8px;
                border-radius: 4px;

                &.locked {
                    color: #8a919f;
                    background: #f2f3f5;
                }
            }

            &.unlocked {
                border-color: #1e80ff;
                background: #f7fbff;
            }
        }
    }
}
```

- [ ] **Step 9: 前端构建验证**

```powershell
npm run build
```

Expected: `✓ built in ...`，无编译错误。

---

## Task 11: 联调验证

- [ ] **Step 1: 重启后端服务**

content / reward / gateway 服务改动需重新编译并重启（项目由外部 IDEA 启动，本次仅编译，服务重启交由 IDE/外部处理；若热部署未生效，需手动同步 `target/classes` 或重启）。

- [ ] **Step 2: 前端启动**

```powershell
npm run dev
```

用浏览器（TRAE Chrome 插件）访问：

1. **登录用户**：进入 `http://localhost:3000/user/{自己的userId}`，头部应显示两枚等级徽章（逐友/逐力值 + 等级名），「勋章 N/11」入口可点击弹出勋章墙。
2. **勋章墙弹窗**：13 枚（2 等级置顶 + 11 网格），已解锁彩色、未解锁置灰显示 `progress/threshold`。
3. **未登录浏览他人主页**：直接访问他人 `http://localhost:3000/user/{他人userId}`，勋章墙正常展示（验证网关公开放行 `path.matches("/content/api/v1/user/\\d+/achievements")`）。
4. **数据一致性**：发布≥1 篇文章后，「初来乍到」解锁；`badgeCount` 同步变化。
5. **浏览器控制台**：无 4xx/5xx 网络错误。

---

## Self-Review

**1. Spec coverage：**
- ap_achievement 表 + 11 枚种子数据 → Task 1 ✓
- 不建用户解锁记录表，实时判定 → Task 5（AchievementServiceImpl 实时聚合）✓
- 2 枚等级徽章动态构造复用 getUserLevelInfo → Task 5（buildLevels）✓
- 接口 GET /content/api/v1/user/{userId}/achievements + 匿名放行 → Task 6 ✓
- badgeCount 真实化 → Task 7 ✓
- 前端头部等级徽章 + 勋章入口 + 勋章墙弹窗 → Task 10 ✓
- 连续签到天数复用既有统计（Feign 远程调用，符合微服务约束）→ Task 4 ✓

**2. Placeholder scan：** 所有步骤均含完整代码或精确命令，无 TBD/TODO/“类似 Task N”。

**3. Type consistency：** `getUserAchievements` 返回 `AchievementDataVO`；`getUserStatistics` 中 badgeCount 取 `getUserAchievements(userId).getUnlockedCount()`；VO 字段名（code/name/category/icon/description/unlocked/progress/threshold/type/level/levelTitle）在 Task 2 定义、Task 5 赋值、Task 10 前端消费三处一致；Feign 方法名 `getContinuousCheckinDays` 在 IRewardClient / Fallback / CheckinService / CheckinServiceImpl / RewardCheckinFeignController / AchievementServiceImpl 六处一致。
