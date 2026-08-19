package com.heima.content.controller.v1.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.apis.user.IUserClient;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.column.ApColumnMapper;
import com.heima.content.mapper.course.ApCourseMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.mapper.tip.ApArticleTipRecordMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.content.service.article.ArticleStatisticsService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleTipRecord;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.ApCollection;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
import com.heima.model.column.pojos.ApColumn;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.course.pojos.ApCourse;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.pins.pojos.ApPins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 个人主页公开接口（未登录也可浏览他人主页分栏信息，利于社区浏览）
 *
 * <p>聚合用户基本信息 + 统计 + 等级，以及按作者查询的文章/专栏/沸点列表。
 * 与个人中心的 manage 接口（需登录、含草稿/审核态）区分：本接口仅返回已发布内容，供公开主页展示。
 *
 * <p>路径：/api/v1/user/home/{userId}[/articles|columns|pins]（经网关 /content 前缀转发，白名单放行）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user/home")
public class UserHomeController {

    @Autowired
    private IUserClient userClient;

    @Autowired
    private ArticleStatisticsService articleStatisticsService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApColumnMapper apColumnMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private ApCollectionMapper apCollectionMapper;

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Autowired
    private ApCourseMapper apCourseMapper;

    @Autowired
    private ApArticleTipRecordMapper tipRecordMapper;

    /**
     * 个人主页头部聚合数据：基本信息（昵称/头像/简介/职位/公司）+ 统计 + 等级
     * GET /api/v1/user/home/{userId}
     */
    @GetMapping("/{userId}")
    public ResponseResult home(@PathVariable Long userId) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }

        Map<String, Object> result = new HashMap<>();

        // 1. 用户公开信息
        Map<String, Object> user = new HashMap<>();
        user.put("userId", userId);
        try {
            ResponseResult ur = userClient.getPublicInfo(userId);
            if (ur != null && ur.getCode() == 200 && ur.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ud = (Map<String, Object>) ur.getData();
                user.put("nickname", str(ud.get("nickname")));
                user.put("avatar", str(ud.get("avatar")));
                user.put("intro", str(ud.get("bio")));
                user.put("position", str(ud.get("position")));
                user.put("company", str(ud.get("company")));
            }
        } catch (Exception e) {
            log.warn("获取用户公开信息失败, userId={}", userId, e);
        }
        result.put("user", user);

        // 2. 统计 + 等级（followCount/followerCount/likeCount/readCount/collectionCount/tagCount/badgeCount/levelInfo）
        try {
            ResponseResult sr = articleStatisticsService.getUserStatistics(userId);
            if (sr != null && sr.getCode() == 200 && sr.getData() instanceof Map) {
                result.putAll((Map<String, Object>) sr.getData());
            }
        } catch (Exception e) {
            log.warn("获取用户统计失败, userId={}", userId, e);
        }

        return ResponseResult.okResult(result);
    }

    /**
     * 用户已发布文章列表（公开）
     * GET /api/v1/user/home/{userId}/articles?page=1&size=10
     */
    @GetMapping("/{userId}/articles")
    public ResponseResult articles(@PathVariable Long userId,
                                   @RequestParam(defaultValue = "1") Integer page,
                                   @RequestParam(defaultValue = "10") Integer size) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;

        LambdaQueryWrapper<ApArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticle::getAuthorId, userId)
                .eq(ApArticle::getIsDeleted, false)
                .eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode())
                .orderByDesc(ApArticle::getPublishTime)
                .orderByDesc(ApArticle::getId);
        IPage<ApArticle> result = apArticleMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> list = result.getRecords().stream().map(a -> {
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", a.getId() != null ? String.valueOf(a.getId()) : "");
            vo.put("title", str(a.getTitle()));
            vo.put("coverImage", str(a.getCoverImage()));
            vo.put("createTime", a.getPublishTime() != null ? a.getPublishTime() : a.getCreatedTime());
            vo.put("readCount", a.getViews() != null ? a.getViews() : 0);
            vo.put("commentCount", a.getComment() != null ? a.getComment() : 0);
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    /**
     * 用户已发布专栏列表（公开）
     * GET /api/v1/user/home/{userId}/columns?page=1&size=10
     */
    @GetMapping("/{userId}/columns")
    public ResponseResult columns(@PathVariable Long userId,
                                  @RequestParam(defaultValue = "1") Integer page,
                                  @RequestParam(defaultValue = "10") Integer size) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;

        LambdaQueryWrapper<ApColumn> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApColumn::getAuthorId, userId)
                .eq(ApColumn::getIsDeleted, false)
                .eq(ApColumn::getStatus, ApColumn.Status.PUBLISHED.getCode())
                .orderByDesc(ApColumn::getCreatedTime)
                .orderByDesc(ApColumn::getId);
        IPage<ApColumn> result = apColumnMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> list = result.getRecords().stream().map(c -> {
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", c.getId());
            vo.put("name", str(c.getTitle()));
            vo.put("description", str(c.getDescription()));
            vo.put("cover", str(c.getCoverImage()));
            vo.put("articleCount", c.getArticleCount() != null ? c.getArticleCount() : 0);
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    /**
     * 用户已发布沸点列表（公开）
     * GET /api/v1/user/home/{userId}/pins?page=1&size=10
     */
    @GetMapping("/{userId}/pins")
    public ResponseResult pins(@PathVariable Long userId,
                               @RequestParam(defaultValue = "1") Integer page,
                               @RequestParam(defaultValue = "10") Integer size) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;

        LambdaQueryWrapper<ApPins> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPins::getAuthorId, userId)
                .eq(ApPins::getIsDeleted, false)
                .eq(ApPins::getStatus, ApPins.Status.PUBLISHED.getCode())
                .orderByDesc(ApPins::getPublishTime)
                .orderByDesc(ApPins::getId);
        IPage<ApPins> result = apPinsMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> list = result.getRecords().stream().map(p -> {
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", p.getId());
            vo.put("content", str(p.getContent()));
            vo.put("createTime", p.getPublishTime() != null ? p.getPublishTime() : p.getCreatedTime());
            vo.put("likeCount", p.getLikes() != null ? p.getLikes() : 0);
            vo.put("commentCount", p.getComment() != null ? p.getComment() : 0);
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    /**
     * 用户关注的用户列表（公开）
     * GET /api/v1/user/home/{userId}/following?page=1&size=20
     */
    @GetMapping("/{userId}/following")
    public ResponseResult following(@PathVariable Long userId,
                                    @RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "20") Integer size) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 20;

        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getUserId, userId.intValue())
                .orderByDesc(ApFollow::getCreatedTime);
        IPage<ApFollow> result = apFollowMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> list = result.getRecords().stream()
                .map(f -> userBrief(f.getFollowUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    /**
     * 用户的关注者列表（公开）
     * GET /api/v1/user/home/{userId}/followers?page=1&size=20
     */
    @GetMapping("/{userId}/followers")
    public ResponseResult followers(@PathVariable Long userId,
                                    @RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "20") Integer size) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 20;

        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getFollowUserId, userId.intValue())
                .orderByDesc(ApFollow::getCreatedTime);
        IPage<ApFollow> result = apFollowMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> list = result.getRecords().stream()
                .map(f -> userBrief(f.getUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    /**
     * 用户收藏集（收藏的文章列表，公开）
     * GET /api/v1/user/home/{userId}/collections?page=1&size=10
     */
    @GetMapping("/{userId}/collections")
    public ResponseResult collections(@PathVariable Long userId,
                                      @RequestParam(defaultValue = "1") Integer page,
                                      @RequestParam(defaultValue = "10") Integer size) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;

        // 1. 查询用户收藏记录（时间线降序）
        LambdaQueryWrapper<ApCollection> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApCollection::getUserId, userId.intValue())
                .orderByDesc(ApCollection::getCreatedTime);
        IPage<ApCollection> result = apCollectionMapper.selectPage(new Page<>(page, size), wrapper);
        List<ApCollection> records = result.getRecords();
        if (records == null || records.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("list", new ArrayList<>());
            empty.put("total", 0);
            return ResponseResult.okResult(empty);
        }

        // 2. 批量加载文章
        List<Long> articleIds = records.stream()
                .map(ApCollection::getArticleId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, ApArticle> articleMap = loadArticleMap(articleIds);

        // 3. 组装收藏文章列表
        List<Map<String, Object>> list = new ArrayList<>();
        for (ApCollection c : records) {
            ApArticle article = articleMap.get(c.getArticleId());
            if (article == null) {
                continue;
            }
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", article.getId());
            vo.put("title", str(article.getTitle()));
            vo.put("coverImage", str(article.getCoverImage()));
            vo.put("authorId", article.getAuthorId());
            vo.put("authorName", str(article.getAuthorName()));
            vo.put("readCount", article.getViews() != null ? article.getViews() : 0);
            vo.put("commentCount", article.getComment() != null ? article.getComment() : 0);
            vo.put("collectTime", c.getCreatedTime() != null ? c.getCreatedTime() : new Date());
            list.add(vo);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    /**
     * 用户点赞的文章/沸点列表（公开）
     * GET /api/v1/user/home/{userId}/likes?page=1&size=10&type=article|pins
     *
     * @param type 过滤类型：article-文章, pins-沸点；不传返回全部
     */
    @GetMapping("/{userId}/likes")
    public ResponseResult likes(@PathVariable Long userId,
                                @RequestParam(defaultValue = "1") Integer page,
                                @RequestParam(defaultValue = "10") Integer size,
                                @RequestParam(required = false) String type) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;

        // 1. 查询用户点赞行为记录（时间线降序）
        List<String> likeTypes = new ArrayList<>();
        if ("article".equalsIgnoreCase(type)) {
            likeTypes.add(BehaviorType.LIKE_ARTICLE.getCode());
        } else if ("pins".equalsIgnoreCase(type)) {
            likeTypes.add(BehaviorType.LIKE_PIN.getCode());
        } else {
            likeTypes.add(BehaviorType.LIKE_ARTICLE.getCode());
            likeTypes.add(BehaviorType.LIKE_PIN.getCode());
        }

        LambdaQueryWrapper<UserBehaviorRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBehaviorRecord::getUserId, userId.intValue())
                .eq(UserBehaviorRecord::getStatus, 1)
                .in(UserBehaviorRecord::getBehaviorType, likeTypes)
                .orderByDesc(UserBehaviorRecord::getCreatedTime);
        IPage<UserBehaviorRecord> result = behaviorRecordMapper.selectPage(new Page<>(page, size), wrapper);
        List<UserBehaviorRecord> records = result.getRecords();
        if (records == null || records.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("list", new ArrayList<>());
            empty.put("total", 0);
            return ResponseResult.okResult(empty);
        }

        // 2. 批量加载目标数据
        List<Long> articleIds = new ArrayList<>();
        List<Long> pinsIds = new ArrayList<>();
        for (UserBehaviorRecord r : records) {
            if (BehaviorType.LIKE_ARTICLE.getCode().equals(r.getBehaviorType())) {
                articleIds.add(r.getTargetId());
            } else if (BehaviorType.LIKE_PIN.getCode().equals(r.getBehaviorType())) {
                pinsIds.add(r.getTargetId());
            }
        }
        Map<Long, ApArticle> articleMap = loadArticleMap(articleIds);
        Map<Long, ApPins> pinsMap = loadPinsMap(pinsIds);

        // 3. 组装点赞列表
        List<Map<String, Object>> list = new ArrayList<>();
        for (UserBehaviorRecord r : records) {
            if (BehaviorType.LIKE_ARTICLE.getCode().equals(r.getBehaviorType())) {
                ApArticle article = articleMap.get(r.getTargetId());
                if (article == null) {
                    continue;
                }
                Map<String, Object> vo = new HashMap<>();
                vo.put("id", article.getId());
                vo.put("targetType", 1);
                vo.put("title", str(article.getTitle()));
                vo.put("coverImage", str(article.getCoverImage()));
                vo.put("authorId", article.getAuthorId());
                vo.put("authorName", str(article.getAuthorName()));
                vo.put("readCount", article.getViews() != null ? article.getViews() : 0);
                vo.put("commentCount", article.getComment() != null ? article.getComment() : 0);
                vo.put("likeTime", r.getCreatedTime() != null ? r.getCreatedTime() : new Date());
                list.add(vo);
            } else if (BehaviorType.LIKE_PIN.getCode().equals(r.getBehaviorType())) {
                ApPins pins = pinsMap.get(r.getTargetId());
                if (pins == null) {
                    continue;
                }
                Map<String, Object> vo = new HashMap<>();
                vo.put("id", pins.getId());
                vo.put("targetType", 2);
                vo.put("title", str(pins.getContent()));
                vo.put("coverImage", firstImage(pins.getImageUrls()));
                vo.put("authorId", pins.getAuthorId());
                vo.put("authorName", str(pins.getAuthorName()));
                vo.put("readCount", pins.getViews() != null ? pins.getViews() : 0);
                vo.put("likeCount", pins.getLikes() != null ? pins.getLikes() : 0);
                vo.put("likeTime", r.getCreatedTime() != null ? r.getCreatedTime() : new Date());
                list.add(vo);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    /**
     * 用户创作的已发布课程列表（公开）
     * GET /api/v1/user/home/{userId}/courses?page=1&size=10
     */
    @GetMapping("/{userId}/courses")
    public ResponseResult courses(@PathVariable Long userId,
                                  @RequestParam(defaultValue = "1") Integer page,
                                  @RequestParam(defaultValue = "10") Integer size) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;

        LambdaQueryWrapper<ApCourse> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApCourse::getAuthorId, userId.intValue())
                .eq(ApCourse::getIsDeleted, 0)
                .eq(ApCourse::getStatus, ApCourse.Status.PUBLISHED.getCode())
                .orderByDesc(ApCourse::getPublishedAt)
                .orderByDesc(ApCourse::getId);
        IPage<ApCourse> result = apCourseMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> list = result.getRecords().stream().map(c -> {
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", c.getId());
            vo.put("title", str(c.getTitle()));
            vo.put("subtitle", str(c.getSubtitle()));
            vo.put("coverImage", str(c.getCoverImage()));
            vo.put("price", c.getPrice() != null ? c.getPrice() : 0);
            vo.put("originalPrice", c.getOriginalPrice() != null ? c.getOriginalPrice() : 0);
            vo.put("chapterCount", c.getChapterCount() != null ? c.getChapterCount() : 0);
            vo.put("studyCount", c.getStudyCount() != null ? c.getStudyCount() : 0);
            vo.put("salesCount", c.getSalesCount() != null ? c.getSalesCount() : 0);
            vo.put("publishedAt", c.getPublishedAt() != null ? c.getPublishedAt() : c.getCreatedTime());
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    /**
     * 作者收到的打赏记录（公开）
     * GET /api/v1/user/home/{userId}/tips?page=1&size=10
     *
     * <p>按打赏时间倒序分页，展示打赏人（昵称/头像）、打赏金额、打赏留言及被打赏的文章标题。
     * 数据来源：ap_article_tip_record（公开感谢名单流水）。
     */
    @GetMapping("/{userId}/tips")
    public ResponseResult tips(@PathVariable Long userId,
                               @RequestParam(defaultValue = "1") Integer page,
                               @RequestParam(defaultValue = "10") Integer size) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 10;

        // 1. 查询该作者收到的打赏流水（按打赏时间倒序）
        LambdaQueryWrapper<ApArticleTipRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticleTipRecord::getAuthorId, userId.intValue())
                .orderByDesc(ApArticleTipRecord::getCreatedTime)
                .orderByDesc(ApArticleTipRecord::getId);
        IPage<ApArticleTipRecord> result = tipRecordMapper.selectPage(new Page<>(page, size), wrapper);
        List<ApArticleTipRecord> records = result.getRecords();
        if (records == null || records.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("list", new ArrayList<>());
            empty.put("total", 0);
            return ResponseResult.okResult(empty);
        }

        // 2. 批量加载被打赏文章标题
        List<Long> articleIds = records.stream()
                .map(ApArticleTipRecord::getArticleId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, ApArticle> articleMap = loadArticleMap(articleIds);

        // 3. 组装打赏记录列表
        List<Map<String, Object>> list = new ArrayList<>();
        for (ApArticleTipRecord r : records) {
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", r.getId());
            // 文章ID为雪花ID，序列化为字符串防止精度丢失
            vo.put("articleId", r.getArticleId() != null ? String.valueOf(r.getArticleId()) : "");
            ApArticle article = articleMap.get(r.getArticleId());
            vo.put("articleTitle", article != null ? str(article.getTitle()) : "");
            vo.put("nickName", str(r.getNickName()));
            vo.put("avatar", str(r.getAvatar()));
            vo.put("amount", r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO);
            vo.put("message", str(r.getMessage()));
            vo.put("createdTime", r.getCreatedTime() != null ? r.getCreatedTime() : new Date());
            list.add(vo);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    // ==================== Private Helpers ====================

    /** 按文章ID批量加载文章 */
    private Map<Long, ApArticle> loadArticleMap(List<Long> ids) {
        Map<Long, ApArticle> map = new HashMap<>();
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinct.isEmpty()) {
            return map;
        }
        List<ApArticle> list = apArticleMapper.selectBatchIds(distinct);
        if (list != null) {
            for (ApArticle a : list) {
                map.put(a.getId(), a);
            }
        }
        return map;
    }

    /** 按沸点ID批量加载沸点 */
    private Map<Long, ApPins> loadPinsMap(List<Long> ids) {
        Map<Long, ApPins> map = new HashMap<>();
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinct.isEmpty()) {
            return map;
        }
        List<ApPins> list = apPinsMapper.selectBatchIds(distinct);
        if (list != null) {
            for (ApPins p : list) {
                map.put(p.getId(), p);
            }
        }
        return map;
    }

    /** 组装用户简要信息（昵称/头像/简介），获取失败返回 null */
    private Map<String, Object> userBrief(Integer userId) {
        if (userId == null) {
            return null;
        }
        try {
            ResponseResult result = userClient.getPublicInfo(userId.longValue());
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.getData();
                Map<String, Object> info = new HashMap<>();
                info.put("id", userId);
                info.put("nickname", str(data.get("nickname")));
                info.put("avatar", str(data.get("avatar")));
                info.put("intro", str(data.get("bio")));
                return info;
            }
        } catch (Exception e) {
            log.warn("获取用户公开信息失败, userId={}", userId, e);
        }
        return null;
    }

    /** 从沸点图片URL串中取第一张图 */
    private String firstImage(String imageUrls) {
        if (imageUrls == null || imageUrls.isBlank()) {
            return "";
        }
        String trimmed = imageUrls.trim();
        if (trimmed.startsWith("[")) {
            int idx = trimmed.indexOf('"');
            int end = idx >= 0 ? trimmed.indexOf('"', idx + 1) : -1;
            if (idx >= 0 && end > idx) {
                return trimmed.substring(idx + 1, end);
            }
            return "";
        }
        int comma = trimmed.indexOf(',');
        return comma > 0 ? trimmed.substring(0, comma) : trimmed;
    }

    private String str(Object val) {
        return val != null ? val.toString() : "";
    }
}
