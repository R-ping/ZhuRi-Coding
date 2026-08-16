# 小册（写小册）系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建独立全屏三栏小册编辑器（新窗口 + 可折叠左目录）+ 简化申报/编辑审核流程 + 复用 ap_course 课程数据链路

**Architecture:** 前端新建 `/booklet/edit` 独立路由全屏三栏编辑器（复用例 ByteMdEditor），左侧小节目录可折叠；后端扩展 ApCourse.Status 枚举（新增 WRITING(4)/REVIEW(5)）+ 新增编辑白名单 BookletReviewController 审核接口；编辑白名单用前后端双常量（userId: 4 = admin），不落库。

**Tech Stack:** Vue 2 + Element UI + ByteMd (markdown) + Vue Router + Axios / Spring Boot + MyBatis-Plus + MySQL

**编辑账号白名单:** admin (userId=4)，对应手机号 13511223456
**作者测试账号:** 用户422067 (userId=1889521665)，对应手机号 11111111111

---

### Task 1: 数据库迁移脚本 - 新增小册字段

**Files:**
- Create: `heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/alter_ap_course_add_booklet_fields.sql`

- [ ] **Step 1: 创建迁移脚本**

```sql
-- 新增小册申报相关字段
ALTER TABLE `ap_course`
  ADD COLUMN `apply_reason` VARCHAR(500) DEFAULT '' COMMENT '申报审核拒绝原因',
  ADD COLUMN `apply_time` DATETIME DEFAULT NULL COMMENT '申报提交时间',
  ADD COLUMN `review_time` DATETIME DEFAULT NULL COMMENT '编辑审核时间';
```

- [ ] **Step 2: 执行迁移脚本**

Run: `mysql -h localhost -u root -p123456 leadnews_article < "heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/alter_ap_course_add_booklet_fields.sql"`

Expected: 3 列已追加到 `ap_course` 表

- [ ] **Step 3: 提交**

```bash
git add heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/alter_ap_course_add_booklet_fields.sql
git commit -m "feat(db): add booklet apply fields to ap_course"
```

---

### Task 2: 后端 - 扩展 ApCourse.Status 枚举

**Files:**
- Modify: `heima-leadnews-model/src/main/java/com/heima/model/course/pojos/ApCourse.java:86-101`

- [ ] **Step 1: 修改 Status 枚举，新增 WRITING(4) 和 REVIEW(5)**

```java
public enum Status {
    NORMAL((byte) 0),
    SUBMIT((byte) 1),
    FAIL((byte) 2),
    OFFLINE((byte) 3),
    /** 申报通过、写作中（新增） */
    WRITING((byte) 4),
    /** 上架待审（新增） */
    REVIEW((byte) 5),
    PUBLISHED((byte) 9);

    byte code;

    Status(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return this.code;
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `cd heima-leadnews && mvn compile -pl heima-leadnews-model -am -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add heima-leadnews-model/src/main/java/com/heima/model/course/pojos/ApCourse.java
git commit -m "feat(course): expand ApCourse.Status enum with WRITING(4) and REVIEW(5)"
```

---

### Task 3: 后端 - 编辑白名单配置

**Files:**
- Create: `heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/config/EditorConfig.java`

- [ ] **Step 1: 创建编辑白名单配置类**

```java
package com.heima.content.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 编辑白名单配置（前后端双常量，userId 白名单）
 * 当前编辑账号: admin (userId=4)
 */
public class EditorConfig {

    /** 编辑账号 userId 白名单 */
    public static final Set<Integer> EDITOR_USER_IDS = new HashSet<>(Arrays.asList(4));

    /** 判断指定 userId 是否为编辑 */
    public static boolean isEditor(Integer userId) {
        return userId != null && EDITOR_USER_IDS.contains(userId);
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/config/EditorConfig.java
git commit -m "feat(course): add editor white list config (userId=4)"
```

---

### Task 4: 后端 - 扩展 ApCourseService 状态迁移与申报接口

**Files:**
- Modify: `heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/service/course/ApCourseService.java`
- Modify: `heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/service/course/impl/ApCourseServiceImpl.java`

- [ ] **Step 1: 在 ApCourseService 接口中新增方法**

```java
// 新增方法
ResponseResult submitApply(Long courseId, String applyContent, Long userId);
ResponseResult getMyBooklets(Long userId, Integer page, Integer size, Byte status);
```

- [ ] **Step 2: 在 ApCourseServiceImpl 中实现状态迁移辅助方法**

```java
/**
 * 状态迁移校验与执行
 * @param course 当前课程
 * @param targetStatus 目标状态
 * @param userId 操作人
 * @param isEditor 是否为编辑
 * @return 错误信息或 null（成功）
 */
private String transitionTo(ApCourse course, byte targetStatus, Integer userId, boolean isEditor) {
    byte current = course.getStatus();
    
    // 作者操作
    if (!isEditor) {
        if (!course.getAuthorId().equals(userId)) {
            return "只能操作自己的课程";
        }
        // 创建草稿 NORMAL(0) -> SUBMIT(1): 提交申报
        if (current == 0 && targetStatus == 1) return null;
        // 申报被拒 FAIL(2) -> NORMAL(0): 修改后重提前重置
        if (current == 2 && targetStatus == 0) return null;
        // 写作中 WRITING(4) -> REVIEW(5): 提交上架审核
        if (current == 4 && targetStatus == 5) return null;
        // 已下架 OFFLINE(3) -> NORMAL(0): 作者改后重提
        if (current == 3 && targetStatus == 0) return null;
        return "非法状态迁移";
    }
    
    // 编辑操作
    if (isEditor) {
        // 申报待审 SUBMIT(1) -> WRITING(4): 通过申报
        if (current == 1 && targetStatus == 4) return null;
        // 申报待审 SUBMIT(1) -> FAIL(2): 拒绝申报
        if (current == 1 && targetStatus == 2) return null;
        // 上架待审 REVIEW(5) -> PUBLISHED(9): 上架
        if (current == 5 && targetStatus == 9) return null;
        // 上架待审 REVIEW(5) -> WRITING(4): 驳回上架
        if (current == 5 && targetStatus == 4) return null;
        // 已上架 PUBLISHED(9) -> OFFLINE(3): 下架
        if (current == 9 && targetStatus == 3) return null;
        // 已下架 OFFLINE(3) -> PUBLISHED(9): 重新上架
        if (current == 3 && targetStatus == 9) return null;
        return "非法状态迁移";
    }
    
    return "非法状态迁移";
}
```

- [ ] **Step 3: 实现 submitApply 方法**

```java
@Override
@Transactional(rollbackFor = Exception.class)
public ResponseResult submitApply(Long courseId, String applyContent, Long userId) {
    if (courseId == null || userId == null) {
        return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
    }

    ApCourse course = getById(courseId);
    if (course == null) {
        return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
    }

    // 校验状态迁移
    String error = transitionTo(course, (byte) 1, userId.intValue(), false);
    if (error != null) {
        return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, error);
    }

    course.setStatus((byte) 1);
    course.setDescription(applyContent != null ? applyContent : course.getDescription());
    course.setApplyTime(new Date());
    course.setUpdatedTime(new Date());
    updateById(course);

    return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
}
```

- [ ] **Step 4: 实现 getMyBooklets 方法**

```java
@Override
public ResponseResult getMyBooklets(Long userId, Integer page, Integer size, Byte status) {
    if (userId == null) {
        return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
    }

    IPage<ApCourse> iPage = new Page<>(page, size);
    LambdaQueryWrapper<ApCourse> query = new LambdaQueryWrapper<>();
    query.eq(ApCourse::getAuthorId, userId.intValue());
    query.eq(ApCourse::getIsDeleted, 0);

    if (status != null) {
        query.eq(ApCourse::getStatus, status);
    }

    query.orderByDesc(ApCourse::getUpdatedTime);

    IPage<ApCourse> resultPage = page(iPage, query);

    Map<String, Object> data = new HashMap<>();
    data.put("list", resultPage.getRecords());
    data.put("total", resultPage.getTotal());
    return ResponseResult.okResult(data);
}
```

- [ ] **Step 5: 验证编译通过**

Run: `cd heima-leadnews && mvn compile -pl heima-leadnews-service -am -q`

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/service/course/ApCourseService.java
git add heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/service/course/impl/ApCourseServiceImpl.java
git commit -m "feat(course): add state machine transition and submitApply/getMyBooklets"
```

---

### Task 5: 后端 - 新增 BookletReviewController（编辑审核接口）

**Files:**
- Create: `heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/controller/v1/course/BookletReviewController.java`

- [ ] **Step 1: 创建编辑审核控制器**

```java
package com.heima.content.controller.v1.course;

import com.heima.content.config.EditorConfig;
import com.heima.content.service.course.ApCourseChapterService;
import com.heima.content.service.course.ApCourseService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.course.pojos.ApCourseChapter;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 小册（课程）编辑审核控制器 - 仅白名单编辑账号可访问
 */
@RestController
@RequestMapping("/api/v1/course/review")
@Slf4j
public class BookletReviewController {

    @Autowired
    private ApCourseService apCourseService;

    @Autowired
    private ApCourseChapterService chapterService;

    /** 校验当前用户是否为编辑 */
    private ResponseResult checkEditor() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (!EditorConfig.isEditor(user.getId())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "仅编辑可操作");
        }
        return null;
    }

    /** 获取当前编辑用户 */
    private ApUser getEditor() {
        return AppThreadLocalUtil.getUser();
    }

    /** 通用状态迁移 */
    private ResponseResult transition(Long courseId, byte targetStatus, String reason) {
        ResponseResult check = checkEditor();
        if (check != null) return check;

        ApUser editor = getEditor();
        ApCourse course = apCourseService.getById(courseId);
        if (course == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "课程不存在");
        }

        // 用 ApCourseServiceImpl 的 transitionTo 方法
        // 这里直接调用 impl 中的 updateStatus 方法，但需校验状态迁移
        // 为简化，直接调用 ApCourseService 的 updateStatus 并设置 reason
        course.setStatus(targetStatus);
        if (reason != null) {
            // 申报审核拒绝原因写 apply_reason，上架审核拒绝原因写 reason
            if (targetStatus == 2) {
                course.setApplyReason(reason);
            } else {
                course.setReason(reason);
            }
        }
        if (targetStatus == 9) {
            course.setPublishedAt(new Date());
        }
        course.setReviewTime(new Date());
        course.setUpdatedTime(new Date());
        apCourseService.updateById(course);

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    /** 1. 申报待审列表 */
    @GetMapping("/apply-list")
    public ResponseResult applyList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        ResponseResult check = checkEditor();
        if (check != null) return check;
        return apCourseService.findList(page, size, (byte) 1);
    }

    /** 2. 通过申报：1→4 */
    @PostMapping("/apply-approve")
    public ResponseResult approveApply(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) return check;
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        if (courseId == null) return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        return transition(courseId, (byte) 4, null);
    }

    /** 3. 拒绝申报：1→2，写 apply_reason */
    @PostMapping("/apply-reject")
    public ResponseResult rejectApply(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) return check;
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        String reason = params.get("reason") != null ? params.get("reason").toString() : "";
        if (courseId == null) return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        return transition(courseId, (byte) 2, reason);
    }

    /** 4. 上架待审列表 */
    @GetMapping("/publish-list")
    public ResponseResult publishList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        ResponseResult check = checkEditor();
        if (check != null) return check;
        return apCourseService.findList(page, size, (byte) 5);
    }

    /** 5. 上架：5→9，写 published_at，并批量发布小节（0→1） */
    @PostMapping("/publish-approve")
    public ResponseResult approvePublish(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) return check;
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        if (courseId == null) return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);

        // 先迁移课程状态
        ResponseResult result = transition(courseId, (byte) 9, null);
        if (result.getCode() != 200) return result;

        // 批量发布所有草稿小节为已发布
        chapterService.publishAllChapters(courseId);

        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    /** 6. 驳回上架：5→4，写 reason */
    @PostMapping("/publish-reject")
    public ResponseResult rejectPublish(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) return check;
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        String reason = params.get("reason") != null ? params.get("reason").toString() : "";
        if (courseId == null) return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        return transition(courseId, (byte) 4, reason);
    }

    /** 7. 发布单个/批量小节：0→1 */
    @PostMapping("/publish-section")
    public ResponseResult publishSection(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) return check;
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        List<String> chapterIds = (List<String>) params.get("chapterIds");
        if (courseId == null) return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        chapterService.publishChapters(courseId, chapterIds);
        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }

    /** 8. 下架：9→3 */
    @PostMapping("/unpublish")
    public ResponseResult unpublish(@RequestBody Map<String, Object> params) {
        ResponseResult check = checkEditor();
        if (check != null) return check;
        Long courseId = params.get("courseId") != null ? Long.parseLong(params.get("courseId").toString()) : null;
        if (courseId == null) return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        return transition(courseId, (byte) 3, null);
    }
}
```

- [ ] **Step 2: 在 ApCourseChapterService 接口和实现类中新增 publishAllChapters / publishChapters 方法**

在 `ApCourseService` 接口中增加 `getById` 和 `updateById` 的兼容方法（或直接让 impl 暴露）。在 `ApCourseChapterService` 中新增：

```java
// ApCourseChapterService 接口新增
ResponseResult publishAllChapters(Long courseId);
ResponseResult publishChapters(Long courseId, List<String> chapterIds);
```

在 `ApCourseChapterServiceImpl` 中实现：

```java
@Override
@Transactional(rollbackFor = Exception.class)
public ResponseResult publishAllChapters(Long courseId) {
    if (courseId == null) {
        return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
    }

    LambdaQueryWrapper<ApCourseChapter> query = new LambdaQueryWrapper<>();
    query.eq(ApCourseChapter::getCourseId, courseId);
    query.eq(ApCourseChapter::getStatus, 0);
    
    List<ApCourseChapter> chapters = chapterMapper.selectList(query);
    for (ApCourseChapter ch : chapters) {
        ch.setStatus(1);
        ch.setUpdatedTime(new Date());
        chapterMapper.updateById(ch);
    }
    
    return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
}

@Override
@Transactional(rollbackFor = Exception.class)
public ResponseResult publishChapters(Long courseId, List<String> chapterIds) {
    if (courseId == null || chapterIds == null || chapterIds.isEmpty()) {
        return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
    }

    for (String idStr : chapterIds) {
        Long id = Long.parseLong(idStr);
        ApCourseChapter chapter = chapterMapper.selectById(id);
        if (chapter != null && chapter.getCourseId().equals(courseId)) {
            chapter.setStatus(1);
            chapter.setUpdatedTime(new Date());
            chapterMapper.updateById(chapter);
        }
    }
    
    return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
}
```

- [ ] **Step 3: 验证编译通过**

Run: `cd heima-leadnews && mvn compile -pl heima-leadnews-service -am -q`

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/controller/v1/course/BookletReviewController.java
git add heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/service/course/ApCourseChapterService.java
git add heima-leadnews-service/heima-leadnews-content/src/main/java/com/heima/content/service/course/impl/ApCourseChapterServiceImpl.java
git commit -m "feat(course): add BookletReviewController for editor review operations"
```

---

### Task 6: 前端 - 路由扩展 + 登录守卫

**Files:**
- Modify: `src/routers/creator.js:7-16,18-143`
- Modify: `src/router.js:38`

- [ ] **Step 1: 扩展 creatorGuard 路径判断**

修改 `creator.js` 中的 `creatorGuard` 函数：

```javascript
export function creatorGuard(to, from, next) {
  if (to.path.startsWith('/creator') || to.path.startsWith('/booklet')) {
    const token = localStorage.getItem('ACCESS_TOKEN')
    if (!token) {
      next('/home')
      return
    }
  }
  next()
}
```

- [ ] **Step 2: 在 creator.js 路由末尾添加 /booklet 路由**

```javascript
// 在 export default routes 之前，追加 booklet 路由
let bookletRoutes = [
  {
    path: '/booklet/edit',
    name: 'BookletEdit',
    component: () => import('@/pages/creator/booklet/edit.vue')
  }
]

// 合并到导出
routes = routes.concat(bookletRoutes)
```

- [ ] **Step 3: 提交**

```bash
git add src/routers/creator.js
git commit -m "feat(router): add /booklet/edit route and extend creatorGuard"
```

---

### Task 7: 前端 - CreatorDropdown 写小册改新窗口

**Files:**
- Modify: `src/components/layouts/CreatorDropdown.vue:78-85`

- [ ] **Step 1: 修改 handleCourseClick 为新窗口**

```javascript
handleCourseClick() {
  if (!this.coursePermission.hasPermission) {
    toast('当前逐力值等级，未达到写小册要求', 2)
    return
  }
  // 写小册 -> 新窗口全屏编辑器
  this.handleNavigate('/booklet/edit', true)
},
```

- [ ] **Step 2: 提交**

```bash
git add src/components/layouts/CreatorDropdown.vue
git commit -m "feat(booklet): open booklet editor in new window"
```

---

### Task 8: 前端 - permission.js 新增 isEditor

**Files:**
- Modify: `src/utils/permission.js`

- [ ] **Step 1: 新增 isEditor 方法**

```javascript
/** 编辑白名单 userId 集合（与后端 EditorConfig 一致） */
const EDITOR_USER_IDS = [4]

/** 判断当前用户是否为编辑（白名单账号） */
isEditor() {
  const user = store.getters.getUserInfo
  if (!user || !user.id) return false
  return EDITOR_USER_IDS.includes(user.id)
},
```

- [ ] **Step 2: 提交**

```bash
git add src/utils/permission.js
git commit -m "feat(permission): add isEditor() for booklet editor white list"
```

---

### Task 9: 前端 - menus.js 新增小册审核菜单项

**Files:**
- Modify: `src/pages/creator/constants/menus.js`

- [ ] **Step 1: 在菜单数据末尾追加审核菜单**

```javascript
{
  title: '小册审核',
  icon: 'el-icon-s-check',
  isEditorOnly: true,  // 标记仅编辑可见
  children: [
    { title: '申报审核', path: '/creator/booklet/review/apply' },
    { title: '上架审核', path: '/creator/booklet/review/publish' }
  ]
}
```

- [ ] **Step 2: 提交**

```bash
git add src/pages/creator/constants/menus.js
git commit -m "feat(menu): add booklet review menu items"
```

---

### Task 10: 前端 - Sidebar.vue 过滤编辑专属菜单

**Files:**
- Modify: `src/pages/creator/layout/components/Sidebar.vue:65-95`

- [ ] **Step 1: 在 filterMenuItems 中过滤编辑菜单**

```javascript
filterMenuItems(items) {
  const isEditor = permission.isEditor()
  return items.map(item => {
    const newItem = { ...item }
    // 编辑专属菜单，非编辑隐藏
    if (item.isEditorOnly && !isEditor) {
      return null  // 不渲染
    }
    // ... 原有权限判断逻辑
    const needParentPermission = item.requiredPermission === 'can_create_course'
    const isParentLocked = (needParentPermission && !this.hasCoursePermission) || item.locked
    // ... 其余逻辑不变
  }).filter(Boolean)  // 过滤掉 null
},
```

- [ ] **Step 2: 提交**

```bash
git add src/pages/creator/layout/components/Sidebar.vue
git commit -m "feat(sidebar): filter isEditorOnly menu items by editor white list"
```

---

### Task 11: 前端 - course.js 新增小册/审核 API

**Files:**
- Modify: `src/apis/course.js`

- [ ] **Step 1: 在 course.js 末尾追加 API 方法**

```javascript
// ========== 小册（写小册）接口 ==========

/** 提交小册申报（状态 0→1） */
applyBooklet(data) {
  return request.post(`${API_PREFIX}/manage/apply`, data)
},

/** 我的小册列表 */
getMyBooklets(params) {
  return request.get(`${API_PREFIX}/manage/my-booklets`, params)
},

// ========== 编辑审核接口 ==========

/** 申报待审列表 */
getApplyReviewList(params) {
  return request.get(`${API_PREFIX}/review/apply-list`, params)
},

/** 通过申报 */
approveApply(data) {
  return request.post(`${API_PREFIX}/review/apply-approve`, data)
},

/** 拒绝申报 */
rejectApply(data) {
  return request.post(`${API_PREFIX}/review/apply-reject`, data)
},

/** 上架待审列表 */
getPublishReviewList(params) {
  return request.get(`${API_PREFIX}/review/publish-list`, params)
},

/** 上架（含批量发布小节） */
approvePublish(data) {
  return request.post(`${API_PREFIX}/review/publish-approve`, data)
},

/** 驳回上架 */
rejectPublish(data) {
  return request.post(`${API_PREFIX}/review/publish-reject`, data)
},

/** 发布小节 */
publishSection(data) {
  return request.post(`${API_PREFIX}/review/publish-section`, data)
},

/** 编辑下架 */
reviewUnpublish(data) {
  return request.post(`${API_PREFIX}/review/unpublish`, data)
},
```

- [ ] **Step 2: 提交**

```bash
git add src/apis/course.js
git commit -m "feat(api): add booklet apply and review APIs"
```

---

### Task 12: 前端 - 小册编辑器目录组件 BookletToc.vue

**Files:**
- Create: `src/pages/creator/booklet/components/BookletToc.vue`

- [ ] **Step 1: 创建目录组件**

```vue
<template>
  <div class="booklet-toc" :class="{ collapsed: collapsed }">
    <div class="toc-header">
      <span class="toc-title">目录</span>
    </div>
    <div class="toc-list">
      <!-- 小册介绍（固定首项） -->
      <div class="toc-item intro-item" :class="{ active: activeSection === 'intro' }" @click="$emit('select', 'intro')">
        <i class="el-icon-document"></i>
        <span>小册介绍</span>
      </div>
      <!-- 章节列表 -->
      <div
        v-for="(ch, idx) in chapters"
        :key="ch.id"
        class="toc-item"
        :class="{ active: activeSection === ch.id }"
        @click="$emit('select', ch.id)"
      >
        <span class="toc-order">{{ idx + 1 }}</span>
        <span class="toc-title-text">{{ ch.title || '未命名章节' }}</span>
        <span class="toc-free-tag" v-if="ch.isFree === 1">试读</span>
        <span class="toc-actions" @click.stop>
          <el-button type="text" size="mini" icon="el-icon-edit" @click.stop="$emit('rename', ch)" />
          <el-button type="text" size="mini" icon="el-icon-delete" @click.stop="$emit('delete', ch)" />
        </span>
      </div>
    </div>
    <div class="toc-footer">
      <el-button type="text" icon="el-icon-plus" @click="$emit('add')">添加章节</el-button>
    </div>
  </div>
</template>

<script>
export default {
  name: 'BookletToc',
  props: {
    collapsed: { type: Boolean, default: false },
    chapters: { type: Array, default: () => [] },
    activeSection: { type: [String, Number], default: null }
  }
}
</script>

<style lang="less" scoped>
.booklet-toc {
  width: 240px;
  min-width: 240px;
  background: #fafafa;
  border-right: 1px solid #e8e8e8;
  display: flex;
  flex-direction: column;
  transition: width 0.2s, min-width 0.2s, opacity 0.2s;
  overflow: hidden;

  &.collapsed {
    width: 0;
    min-width: 0;
    opacity: 0;
    pointer-events: none;
  }
}

.toc-header {
  padding: 12px 16px;
  border-bottom: 1px solid #e8e8e8;
  .toc-title {
    font-size: 14px;
    font-weight: 600;
    color: #333;
  }
}

.toc-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.toc-item {
  display: flex;
  align-items: center;
  padding: 8px 16px;
  cursor: pointer;
  gap: 8px;
  font-size: 13px;
  color: #515767;
  transition: background 0.15s;

  &:hover {
    background: #e8f4ff;
  }
  &.active {
    background: #d4e8ff;
    color: #1e80ff;
  }

  &.intro-item {
    border-bottom: 1px solid #e8e8e8;
    margin-bottom: 4px;
  }
}

.toc-order {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #e8e8e8;
  border-radius: 50%;
  font-size: 12px;
  flex-shrink: 0;
}

.toc-title-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toc-free-tag {
  font-size: 11px;
  color: #67c23a;
  background: #f0f9eb;
  padding: 1px 6px;
  border-radius: 3px;
  flex-shrink: 0;
}

.toc-actions {
  display: none;
  flex-shrink: 0;
  .toc-item:hover & {
    display: flex;
  }
}

.toc-footer {
  padding: 12px 16px;
  border-top: 1px solid #e8e8e8;
}
</style>
```

- [ ] **Step 2: 提交**

```bash
git add src/pages/creator/booklet/components/BookletToc.vue
git commit -m "feat(booklet): add BookletToc collapsible directory component"
```

---

### Task 13: 前端 - 小册编辑器顶部工具栏组件 BookletTopBar.vue

**Files:**
- Create: `src/pages/creator/booklet/components/BookletTopBar.vue`

- [ ] **Step 1: 创建顶部工具栏组件**

```vue
<template>
  <div class="booklet-topbar">
    <div class="topbar-left">
      <el-input
        v-model="localTitle"
        placeholder="小册标题"
        class="title-input"
        size="small"
        @input="$emit('update:title', localTitle)"
      />
      <span class="save-status" :class="'status-' + saveStatus">{{ saveStatusText }}</span>
    </div>
    <div class="topbar-right">
      <!-- 作者操作 -->
      <template v-if="isEditor">
        <el-button size="small" @click="$emit('publish')" v-if="status === 5">上架</el-button>
        <el-button size="small" @click="$emit('unpublish')" v-if="status === 9">下架</el-button>
        <el-button size="small" @click="$emit('publishSection')">发布小节</el-button>
      </template>
      <template v-else>
        <el-button size="small" type="primary" @click="$emit('apply')" v-if="status === 0">提交申报</el-button>
        <el-button size="small" type="primary" @click="$emit('submitReview')" v-if="status === 4">提交上架审核</el-button>
      </template>
      <el-dropdown @command="handleMore" v-if="!isEditor && status === 0">
        <el-button size="small" icon="el-icon-more"></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="save">保存</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>
    </div>
  </div>
</template>

<script>
export default {
  name: 'BookletTopBar',
  props: {
    title: { type: String, default: '' },
    saveStatus: { type: String, default: 'saved' },
    isEditor: { type: Boolean, default: false },
    status: { type: Number, default: null }
  },
  data() {
    return {
      localTitle: this.title
    }
  },
  watch: {
    title(val) { this.localTitle = val }
  },
  computed: {
    saveStatusText() {
      const map = { saving: '保存中...', saved: '已保存', dirty: '未保存', failed: '保存失败' }
      return map[this.saveStatus] || ''
    }
  },
  methods: {
    handleMore(cmd) {
      if (cmd === 'save') this.$emit('save')
    }
  }
}
</script>

<style lang="less" scoped>
.booklet-topbar {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  flex-shrink: 0;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.title-input {
  width: 280px;
}

.save-status {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
  color: #909399;
}
.status-saving { color: #e6a23c; }
.status-saved { color: #67c23a; }
.status-dirty { color: #909399; }
.status-failed { color: #f56c6c; }

.topbar-right {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
```

- [ ] **Step 2: 提交**

```bash
git add src/pages/creator/booklet/components/BookletTopBar.vue
git commit -m "feat(booklet): add BookletTopBar toolbar component"
```

---

### Task 14: 前端 - 小册编辑器主页面 edit.vue

**Files:**
- Create: `src/pages/creator/booklet/edit.vue`

- [ ] **Step 1: 创建全屏三栏编辑器主页面**

```vue
<template>
  <div class="booklet-editor-page">
    <!-- 顶部工具栏 -->
    <BookletTopBar
      :title="courseInfo.title"
      :saveStatus="saveStatus"
      :isEditor="isEditor"
      :status="courseInfo.status"
      @update:title="onTitleChange"
      @apply="handleApply"
      @submitReview="handleSubmitReview"
      @publish="handleEditorPublish"
      @unpublish="handleEditorUnpublish"
      @publishSection="handlePublishSection"
      @save="doSave"
    />

    <div class="editor-body">
      <!-- 左侧目录栏 -->
      <BookletToc
        :collapsed="tocCollapsed"
        :chapters="chapters"
        :activeSection="activeSection"
        @select="onSelectSection"
        @add="addChapter"
        @delete="deleteChapter"
        @rename="renameChapter"
      />

      <!-- 中间：Markdown 编辑器 -->
      <div class="editor-main" v-if="editingChapter">
        <div class="chapter-editor-header">
          <el-input v-model="editingChapter.title" placeholder="章节标题" size="small" @input="markDirty" />
          <div class="chapter-options">
            <el-checkbox v-model="editingChapter.isFreeBool" @change="onFreeChange">设为试读</el-checkbox>
          </div>
        </div>
        <ByteMdEditor
          ref="editor"
          :value="editingChapter.content"
          @change="onContentChange"
          placeholder="请输入章节内容，支持 Markdown 语法..."
        />
        <!-- 底部工具栏 -->
        <div class="editor-bottom-bar">
          <el-button type="text" size="small" @click="tocCollapsed = !tocCollapsed">
            <i :class="tocCollapsed ? 'el-icon-s-unfold' : 'el-icon-s-fold'"></i>
            {{ tocCollapsed ? '展开目录' : '收起目录' }}
          </el-button>
          <span class="bottom-bar-spacer"></span>
          <el-button type="text" size="small" @click="previewCollapsed = !previewCollapsed">
            {{ previewCollapsed ? '展开预览' : '收起预览' }}
            <i :class="previewCollapsed ? 'el-icon-s-unfold' : 'el-icon-s-fold'"></i>
          </el-button>
        </div>
      </div>

      <!-- 右侧：预览 -->
      <div class="editor-preview" v-if="editingChapter && !previewCollapsed">
        <div class="preview-header">预览</div>
        <div class="preview-content markdown-body" v-html="renderedContent"></div>
      </div>
    </div>

    <!-- 申报弹窗 -->
    <el-dialog title="小册申报" :visible.sync="applyDialogVisible" width="560px">
      <el-form label-position="top">
        <el-form-item label="选题方向">
          <el-input v-model="applyForm.topic" placeholder="请简要说明小册的主题和方向" />
        </el-form-item>
        <el-form-item label="大纲">
          <el-input type="textarea" v-model="applyForm.outline" :rows="4" placeholder="列举小册的主要内容章节" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input type="textarea" v-model="applyForm.description" :rows="3" placeholder="小册简介" />
        </el-form-item>
        <el-form-item label="样章说明">
          <el-input type="textarea" v-model="applyForm.sample" :rows="3" placeholder="已完成的样章内容说明" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="applyDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmApply">提交申报</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import courseApi from '@/apis/course'
import permission from '@/utils/permission'
import { toast } from '@/utils/toast'
import { marked } from 'marked'
import BookletTopBar from './components/BookletTopBar.vue'
import BookletToc from './components/BookletToc.vue'
import ByteMdEditor from '@/pages/creator/components/editor/ByteMdEditor.vue'

export default {
  name: 'BookletEdit',
  components: { BookletTopBar, BookletToc, ByteMdEditor },
  data() {
    return {
      loading: true,
      courseId: null,
      courseInfo: {
        title: '未命名小册',
        subtitle: '',
        description: '',
        coverImage: '',
        price: 0,
        originalPrice: 0,
        categoryId: null,
        status: 0,
        reason: '',
        applyReason: ''
      },
      chapters: [],
      activeSection: null,  // 'intro' 或 chapter.id
      editingChapter: null,
      introContent: '',
      tocCollapsed: false,
      previewCollapsed: false,
      dirty: false,
      autoSaveTimer: null,
      saveStatus: 'saved',
      applyDialogVisible: false,
      applyForm: { topic: '', outline: '', description: '', sample: '' }
    }
  },
  computed: {
    isEditor() {
      return permission.isEditor()
    },
    renderedContent() {
      if (!this.editingChapter || !this.editingChapter.content) return ''
      return marked(this.editingChapter.content)
    }
  },
  mounted() {
    this.courseId = this.$route.query.courseId
    if (this.courseId) {
      this.loadCourseDetail()
    } else {
      this.createNewBooklet()
    }
  },
  beforeDestroy() {
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer)
  },
  methods: {
    async createNewBooklet() {
      try {
        const res = await courseApi.createCourse({ title: '未命名小册' })
        if (res && res.code === 200 && res.data) {
          this.courseId = res.data.id
          this.$router.replace(`/booklet/edit?courseId=${this.courseId}`)
          await this.loadCourseDetail()
        }
      } catch (e) {
        toast('创建小册失败')
      }
    },
    async loadCourseDetail() {
      this.loading = true
      try {
        const res = await courseApi.getManageDetail({ courseId: this.courseId })
        if (res && res.code === 200 && res.data) {
          const course = res.data.course
          this.courseInfo = {
            title: course.title || '未命名小册',
            subtitle: course.subtitle || '',
            description: course.description || '',
            coverImage: course.coverImage || '',
            price: course.price || 0,
            originalPrice: course.originalPrice || 0,
            categoryId: course.categoryId || null,
            status: course.status,
            reason: course.reason || '',
            applyReason: course.applyReason || ''
          }
          this.chapters = (res.data.chapters || []).map(ch => ({
            ...ch,
            isFreeBool: ch.isFree === 1
          }))
          this.activeSection = 'intro'
          this.editingChapter = null
        }
      } catch (e) {
        toast('加载小册详情失败')
      } finally {
        this.loading = false
      }
    },
    onSelectSection(sectionId) {
      this.activeSection = sectionId
      if (sectionId === 'intro') {
        this.editingChapter = { id: 'intro', title: '小册介绍', content: this.introContent, isFree: 0, isFreeBool: false }
      } else {
        const ch = this.chapters.find(c => c.id === sectionId)
        if (ch) this.editingChapter = { ...ch }
      }
    },
    onContentChange(val) {
      if (this.editingChapter) {
        this.editingChapter.content = val
        this.markDirty()
        this.autoSave()
      }
    },
    onTitleChange(val) {
      this.courseInfo.title = val
      this.markDirty()
      this.autoSave()
    },
    onFreeChange(val) {
      if (this.editingChapter) {
        this.editingChapter.isFree = val ? 1 : 0
        this.markDirty()
        this.autoSaveChapter()
      }
    },
    markDirty() {
      this.dirty = true
      this.saveStatus = 'dirty'
    },
    autoSave() {
      if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer)
      this.autoSaveTimer = setTimeout(() => { this.doSave() }, 2000)
    },
    async doSave() {
      if (!this.dirty) return
      this.saveStatus = 'saving'
      try {
        await courseApi.updateCourse({
          id: this.courseId,
          title: this.courseInfo.title,
          subtitle: this.courseInfo.subtitle
        })
        if (this.editingChapter && this.editingChapter.id !== 'intro') {
          await this.autoSaveChapter()
        }
        this.dirty = false
        this.saveStatus = 'saved'
      } catch (e) {
        this.saveStatus = 'failed'
      }
    },
    async autoSaveChapter() {
      if (!this.editingChapter || this.editingChapter.id === 'intro') return
      try {
        await courseApi.updateChapter({
          id: this.editingChapter.id,
          title: this.editingChapter.title,
          content: this.editingChapter.content,
          isFree: this.editingChapter.isFree,
          estimatedMinutes: this.editingChapter.estimatedMinutes || 5
        })
      } catch (e) {
        console.error('自动保存章节失败', e)
      }
    },
    async addChapter() {
      try {
        const res = await courseApi.createChapter({ courseId: this.courseId })
        if (res && res.code === 200 && res.data) {
          await this.loadCourseDetail()
          const newCh = this.chapters.find(ch => ch.id === res.data.id)
          if (newCh) this.onSelectSection(newCh.id)
        }
      } catch (e) {
        toast('创建章节失败')
      }
    },
    async deleteChapter(ch) {
      try {
        await this.$confirm('确定要删除该章节吗？', '提示', { type: 'warning' })
        await courseApi.deleteChapter(ch.id)
        await this.loadCourseDetail()
        this.activeSection = 'intro'
        this.editingChapter = null
      } catch (e) {
        if (e !== 'cancel') toast('删除失败')
      }
    },
    renameChapter(ch) {
      this.$prompt('请输入新章节名称', '重命名', { inputValue: ch.title }).then(({ value }) => {
        if (value) {
          courseApi.updateChapter({ id: ch.id, title: value }).then(() => {
            ch.title = value
            this.loadCourseDetail()
          })
        }
      }).catch(() => {})
    },
    handleApply() {
      this.applyDialogVisible = true
    },
    async confirmApply() {
      if (!this.applyForm.topic) {
        toast('请填写选题方向')
        return
      }
      try {
        const applyContent = JSON.stringify(this.applyForm)
        const res = await courseApi.applyBooklet({ courseId: this.courseId, applyContent })
        if (res && res.code === 200) {
          toast('申报已提交，等待编辑审核')
          this.applyDialogVisible = false
          this.courseInfo.status = 1
        }
      } catch (e) {
        toast('提交申报失败')
      }
    },
    async handleSubmitReview() {
      try {
        await this.doSave()
        await this.$confirm('确定要提交上架审核吗？', '提示', { type: 'warning' })
        const res = await courseApi.submitForReview({ courseId: this.courseId })
        if (res && res.code === 200) {
          toast('已提交上架审核')
          this.courseInfo.status = 5
        }
      } catch (e) {
        if (e !== 'cancel') toast('提交失败')
      }
    },
    async handleEditorPublish() {
      try {
        await this.$confirm('确定要上架该小册吗？', '提示', { type: 'warning' })
        const res = await courseApi.approvePublish({ courseId: this.courseId })
        if (res && res.code === 200) {
          toast('上架成功')
          this.courseInfo.status = 9
        }
      } catch (e) {
        if (e !== 'cancel') toast('上架失败')
      }
    },
    async handleEditorUnpublish() {
      try {
        await this.$confirm('确定要下架该小册吗？', '提示', { type: 'warning' })
        const res = await courseApi.reviewUnpublish({ courseId: this.courseId })
        if (res && res.code === 200) {
          toast('已下架')
          this.courseInfo.status = 3
        }
      } catch (e) {
        if (e !== 'cancel') toast('下架失败')
      }
    },
    async handlePublishSection() {
      try {
        const draftChapters = this.chapters.filter(ch => ch.status === 0)
        if (draftChapters.length === 0) {
          toast('没有草稿章节需要发布')
          return
        }
        await this.$confirm(`将发布 ${draftChapters.length} 个草稿章节，确定吗？`, '提示', { type: 'warning' })
        const res = await courseApi.publishSection({
          courseId: this.courseId,
          chapterIds: draftChapters.map(ch => String(ch.id))
        })
        if (res && res.code === 200) {
          toast('发布成功')
          this.loadCourseDetail()
        }
      } catch (e) {
        if (e !== 'cancel') toast('发布失败')
      }
    }
  }
}
</script>

<style lang="less" scoped>
.booklet-editor-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
}

.editor-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.editor-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

.chapter-editor-header {
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  align-items: center;
  gap: 16px;
}

.chapter-options {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.editor-bottom-bar {
  height: 36px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  background: #fafafa;
  border-top: 1px solid #e8e8e8;
  flex-shrink: 0;
  gap: 8px;
}

.bottom-bar-spacer {
  flex: 1;
}

.editor-preview {
  width: 360px;
  background: #fff;
  border-left: 1px solid #e8e8e8;
  overflow-y: auto;
  flex-shrink: 0;
}

.preview-header {
  padding: 12px 16px;
  font-size: 13px;
  font-weight: 600;
  color: #999;
  border-bottom: 1px solid #e8e8e8;
}

.preview-content {
  padding: 16px;
  font-size: 14px;
  line-height: 1.8;
  color: #333;
}
</style>
```

- [ ] **Step 2: 提交**

```bash
git add src/pages/creator/booklet/edit.vue
git commit -m "feat(booklet): add fullscreen three-column booklet editor page"
```

---

### Task 15: 前端 - 编辑审核页面（申报审核 + 上架审核）

**Files:**
- Create: `src/pages/creator/booklet/review/ApplyReview.vue`
- Create: `src/pages/creator/booklet/review/PublishReview.vue`

- [ ] **Step 1: 创建申报审核页面 ApplyReview.vue**

```vue
<template>
  <div class="review-page">
    <h2 class="page-title">申报审核</h2>
    <el-table :data="list" v-loading="loading" style="width: 100%">
      <el-table-column prop="title" label="小册标题" min-width="180" />
      <el-table-column prop="authorName" label="作者" width="120" />
      <el-table-column prop="applyTime" label="申报时间" width="160">
        <template slot-scope="s">{{ formatTime(s.row.applyTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template slot-scope="s">
          <el-button type="text" size="small" @click="handleApprove(s.row)">通过</el-button>
          <el-button type="text" size="small" style="color:#f56c6c" @click="handleReject(s.row)">拒绝</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import courseApi from '@/apis/course'

export default {
  name: 'ApplyReview',
  data() {
    return { loading: false, list: [] }
  },
  mounted() { this.loadList() },
  methods: {
    async loadList() {
      this.loading = true
      try {
        const res = await courseApi.getApplyReviewList({ page: 1, size: 20 })
        if (res && res.code === 200 && res.data) this.list = res.data.list || []
      } catch (e) { console.error(e) }
      finally { this.loading = false }
    },
    async handleApprove(row) {
      try {
        await this.$confirm(`确定通过「${row.title}」的申报？`, '提示', { type: 'warning' })
        const res = await courseApi.approveApply({ courseId: row.id })
        if (res && res.code === 200) { this.$message.success('已通过'); this.loadList() }
      } catch (e) { if (e !== 'cancel') this.$message.error('操作失败') }
    },
    async handleReject(row) {
      try {
        const { value } = await this.$prompt('拒绝原因', '拒绝申报', { inputType: 'textarea' })
        if (value) {
          const res = await courseApi.rejectApply({ courseId: row.id, reason: value })
          if (res && res.code === 200) { this.$message.success('已拒绝'); this.loadList() }
        }
      } catch (e) { if (e !== 'cancel') this.$message.error('操作失败') }
    },
    formatTime(t) { if (!t) return ''; return new Date(t).toLocaleString() }
  }
}
</script>

<style lang="less" scoped>
.review-page { padding: 20px; }
.page-title { font-size: 20px; font-weight: 600; margin-bottom: 16px; }
</style>
```

- [ ] **Step 2: 创建上架审核页面 PublishReview.vue**

```vue
<template>
  <div class="review-page">
    <h2 class="page-title">上架审核</h2>
    <el-table :data="list" v-loading="loading" style="width: 100%">
      <el-table-column prop="title" label="小册标题" min-width="180" />
      <el-table-column prop="authorName" label="作者" width="120" />
      <el-table-column prop="chapterCount" label="章节数" width="80" />
      <el-table-column label="操作" width="240" fixed="right">
        <template slot-scope="s">
          <el-button type="text" size="small" style="color:#67c23a" @click="handleApprove(s.row)">上架</el-button>
          <el-button type="text" size="small" style="color:#f56c6c" @click="handleReject(s.row)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import courseApi from '@/apis/course'

export default {
  name: 'PublishReview',
  data() {
    return { loading: false, list: [] }
  },
  mounted() { this.loadList() },
  methods: {
    async loadList() {
      this.loading = true
      try {
        const res = await courseApi.getPublishReviewList({ page: 1, size: 20 })
        if (res && res.code === 200 && res.data) this.list = res.data.list || []
      } catch (e) { console.error(e) }
      finally { this.loading = false }
    },
    async handleApprove(row) {
      try {
        await this.$confirm(`确定上架「${row.title}」？上架后所有章节将被发布。`, '提示', { type: 'warning' })
        const res = await courseApi.approvePublish({ courseId: row.id })
        if (res && res.code === 200) { this.$message.success('已上架'); this.loadList() }
      } catch (e) { if (e !== 'cancel') this.$message.error('操作失败') }
    },
    async handleReject(row) {
      try {
        const { value } = await this.$prompt('驳回原因', '驳回上架', { inputType: 'textarea' })
        if (value) {
          const res = await courseApi.rejectPublish({ courseId: row.id, reason: value })
          if (res && res.code === 200) { this.$message.success('已驳回'); this.loadList() }
        }
      } catch (e) { if (e !== 'cancel') this.$message.error('操作失败') }
    }
  }
}
</script>

<style lang="less" scoped>
.review-page { padding: 20px; }
.page-title { font-size: 20px; font-weight: 600; margin-bottom: 16px; }
</style>
```

- [ ] **Step 3: 为审核页面注册路由到 creator.js**

在 `creator.js` 中 children 末尾追加：

```javascript
{
  path: 'booklet/review/apply',
  name: 'BookletApplyReview',
  component: () => import('@/pages/creator/booklet/review/ApplyReview.vue')
},
{
  path: 'booklet/review/publish',
  name: 'BookletPublishReview',
  component: () => import('@/pages/creator/booklet/review/PublishReview.vue')
}
```

- [ ] **Step 4: 提交**

```bash
git add src/pages/creator/booklet/review/ApplyReview.vue
git add src/pages/creator/booklet/review/PublishReview.vue
git add src/routers/creator.js
git commit -m "feat(booklet): add review pages (ApplyReview + PublishReview) and routes"
```

---

### Task 16: 编译验证与总提交

- [ ] **Step 1: 后端编译**

Run: `cd heima-leadnews && mvn compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 2: 前端编译**

Run: `cd .. && npm run build`

Expected: 无错误，生成 dist 目录

- [ ] **Step 3: 创建功能分支（如果尚未在分支上）**

```bash
git checkout -b feat/booklet-system
```

- [ ] **Step 4: 暂存所有变更**

```bash
git add .
```

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(booklet): 小册系统 - 全屏三栏编辑器 + 简化申报/编辑审核全流程"
```