package com.heima.content.service.pins.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.content.mapper.circle.ApCircleCategoryMapper;
import com.heima.content.mapper.circle.ApCircleMapper;
import com.heima.content.mapper.circle.ApUserCircleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.level.ApUserLevelMapper;
import com.heima.content.mapper.pins.ApPinsCommentMapper;
import com.heima.content.mapper.pins.ApPinsLikeMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.mapper.topic.TopicMapper;
import com.heima.content.service.topic.TopicService;
import com.heima.model.pins.dtos.PinsLinkPreviewDTO;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.pins.pojos.ApPinsComment;
import com.heima.model.pins.pojos.ApPinsLike;
import com.heima.model.pins.vos.PinsCommentVO;
import com.heima.model.pins.vos.PinsLinkPreviewVO;
import com.heima.model.pins.vos.PinsSidebarVO;
import com.heima.model.pins.vos.PinsVO;
import com.heima.model.circle.pojos.ApCircle;
import com.heima.model.circle.pojos.ApCircleCategory;
import com.heima.model.circle.pojos.ApUserCircle;
import com.heima.model.topic.pojos.ApTopic;
import com.heima.model.topic.vos.TopicRecommendVO;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.level.pojos.ApUserLevel;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class PinsQueryService {

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private ApPinsLikeMapper apPinsLikeMapper;

    @Autowired
    private ApPinsCommentMapper apPinsCommentMapper;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private ApUserCircleMapper apUserCircleMapper;

    @Autowired
    private ApCircleMapper apCircleMapper;

    @Autowired
    private ApCircleCategoryMapper apCircleCategoryMapper;

    @Autowired
    private TopicMapper topicMapper;

    @Autowired
    private ApUserLevelMapper apUserLevelMapper;

    @Autowired
    private TopicService topicService;

    // ========== 沸点列表 ==========

    public ResponseResult list(String tab, Integer page, Integer size) {
        ApUser user = getUserOrNull();
        // 兼容前端 "follow" 与约定的 "following" 两种取值，二者等同"我关注的用户发的沸点"
        if ("following".equals(tab) || "follow".equals(tab)) {
            if (user == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
            }
            return listFollowing(user, page, size);
        } else if ("hot".equals(tab)) {
            return listHot(user, page, size);
        } else {
            return listLatest(user, page, size);
        }
    }

    public ResponseResult listLatest(ApUser user, Integer page, Integer size) {
        Page<ApPins> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApPins> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPins::getStatus, ApPins.Status.PUBLISHED.getCode());
        wrapper.eq(ApPins::getIsDeleted, false);
        wrapper.orderByDesc(ApPins::getReviewTime);
        IPage<ApPins> result = apPinsMapper.selectPage(pageParam, wrapper);
        List<PinsVO> voList = convertToVOList(result.getRecords(), user);
        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", result.getTotal());
        data.put("page", page);
        data.put("size", size);
        return ResponseResult.okResult(data);
    }

    public ResponseResult listHot(ApUser user, Integer page, Integer size) {
        // 查询所有已发布的沸点
        LambdaQueryWrapper<ApPins> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPins::getStatus, ApPins.Status.PUBLISHED.getCode());
        wrapper.eq(ApPins::getIsDeleted, false);
        List<ApPins> allPins = apPinsMapper.selectList(wrapper);

        if (allPins.isEmpty()) {
            Map<String, Object> data = new HashMap<>();
            data.put("list", new ArrayList<>());
            data.put("total", 0);
            data.put("page", page);
            data.put("size", size);
            return ResponseResult.okResult(data);
        }

        // 批量查询用户等级
        Set<Long> authorIds = allPins.stream()
                .map(ApPins::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        final Map<Long, ApUserLevel> levelMap;
        if (!authorIds.isEmpty()) {
            LambdaQueryWrapper<ApUserLevel> levelWrapper = new LambdaQueryWrapper<>();
            levelWrapper.in(ApUserLevel::getUserId, authorIds);
            List<ApUserLevel> levels = apUserLevelMapper.selectList(levelWrapper);
            levelMap = levels.stream().collect(Collectors.toMap(ApUserLevel::getUserId, l -> l, (a, b) -> a));
        } else {
            levelMap = new HashMap<>();
        }

        // 计算热度分并排序
        List<ApPins> sortedPins = allPins.stream()
                .sorted((a, b) -> {
                    int scoreA = calcHotScore(a, levelMap);
                    int scoreB = calcHotScore(b, levelMap);
                    return Integer.compare(scoreB, scoreA);
                })
                .collect(Collectors.toList());

        // 手动分页
        int total = sortedPins.size();
        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, total);
        List<ApPins> pagePins = fromIndex < total ? sortedPins.subList(fromIndex, toIndex) : new ArrayList<>();

        List<PinsVO> voList = convertToVOList(pagePins, user);
        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", (long) total);
        data.put("page", page);
        data.put("size", size);
        return ResponseResult.okResult(data);
    }

    public int calcHotScore(ApPins pin, Map<Long, ApUserLevel> levelMap) {
        int likes = pin.getLikes() != null ? pin.getLikes() : 0;
        int comment = pin.getComment() != null ? pin.getComment() : 0;
        int share = pin.getShareCount() != null ? pin.getShareCount() : 0;
        int dailyLevel = 0;
        int powerLevel = 0;
        ApUserLevel level = levelMap.get(pin.getAuthorId());
        if (level != null) {
            dailyLevel = level.getDailyLevel() != null ? level.getDailyLevel() : 0;
            powerLevel = level.getPowerLevel() != null ? level.getPowerLevel() : 0;
        }
        return likes * 1 + comment * 2 + share * 3 + dailyLevel * 5 + powerLevel * 5;
    }

    public ResponseResult listFollowing(ApUser user, Integer page, Integer size) {
        // 获取当前用户关注的用户ID列表
        LambdaQueryWrapper<ApFollow> followWrapper = new LambdaQueryWrapper<>();
        followWrapper.eq(ApFollow::getUserId, user.getId());
        List<ApFollow> follows = apFollowMapper.selectList(followWrapper);
        List<Long> followedIds = follows.stream()
                .map(f -> f.getFollowUserId().longValue())
                .collect(Collectors.toList());

        if (followedIds.isEmpty()) {
            Map<String, Object> data = new HashMap<>();
            data.put("list", new ArrayList<>());
            data.put("total", 0);
            data.put("page", page);
            data.put("size", size);
            return ResponseResult.okResult(data);
        }

        Page<ApPins> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApPins> pinsWrapper = new LambdaQueryWrapper<>();
        pinsWrapper.in(ApPins::getAuthorId, followedIds);
        pinsWrapper.eq(ApPins::getStatus, ApPins.Status.PUBLISHED.getCode());
        pinsWrapper.eq(ApPins::getIsDeleted, false);
        pinsWrapper.orderByDesc(ApPins::getReviewTime);
        IPage<ApPins> result = apPinsMapper.selectPage(pageParam, pinsWrapper);

        List<PinsVO> voList = convertToVOList(result.getRecords(), user);
        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", result.getTotal());
        data.put("page", page);
        data.put("size", size);
        return ResponseResult.okResult(data);
    }

    // ========== 沸点详情 ==========

    /**
     * 获取沸点详情（单条）
     */
    public ResponseResult detail(Long pinsId) {
        if (pinsId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "pinsId不能为空");
        }
        ApUser user = getUserOrNull();
        ApPins pin = apPinsMapper.selectById(pinsId);
        if (pin == null || Boolean.TRUE.equals(pin.getIsDeleted())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        List<ApPins> single = Collections.singletonList(pin);
        List<PinsVO> voList = convertToVOList(single, user);
        return ResponseResult.okResult(voList.isEmpty() ? null : voList.get(0));
    }

    /**
     * 递增沸点浏览量（供沸点详情页调用，话题详情浏览数为关联沸点浏览量的总和）
     */
    public void incrView(Long pinsId) {
        if (pinsId == null) {
            return;
        }
        try {
            apPinsMapper.incrementViews(pinsId);
        } catch (Exception e) {
            log.error("递增沸点浏览量失败, pinsId={}", pinsId, e);
        }
    }

    // ========== 侧边栏 ==========

    public ResponseResult sidebar() {
        ApUser user = getUserOrNull();
        PinsSidebarVO vo = new PinsSidebarVO();

        if (user != null) {
            // 沸点数量
            LambdaQueryWrapper<ApPins> pinsWrapper = new LambdaQueryWrapper<>();
            pinsWrapper.eq(ApPins::getAuthorId, user.getId().longValue());
            pinsWrapper.eq(ApPins::getIsDeleted, false);
            vo.setPinsCount((int) apPinsMapper.selectCount(pinsWrapper).longValue());

            // 圈子数量
            LambdaQueryWrapper<ApUserCircle> circleWrapper = new LambdaQueryWrapper<>();
            circleWrapper.eq(ApUserCircle::getUserId, user.getId());
            vo.setCircleCount((int) apUserCircleMapper.selectCount(circleWrapper).longValue());

            // 关注数量
            LambdaQueryWrapper<ApFollow> followWrapper = new LambdaQueryWrapper<>();
            followWrapper.eq(ApFollow::getUserId, user.getId());
            vo.setFollowingCount((int) apFollowMapper.selectCount(followWrapper).longValue());

            // 粉丝数量
            LambdaQueryWrapper<ApFollow> fansWrapper = new LambdaQueryWrapper<>();
            fansWrapper.eq(ApFollow::getFollowUserId, user.getId());
            vo.setFollowersCount((int) apFollowMapper.selectCount(fansWrapper).longValue());
        }

        // 精选沸点：likes + comment 最高的3条
        LambdaQueryWrapper<ApPins> featuredWrapper = new LambdaQueryWrapper<>();
        featuredWrapper.eq(ApPins::getStatus, ApPins.Status.PUBLISHED.getCode());
        featuredWrapper.eq(ApPins::getIsDeleted, false);
        List<ApPins> allPublished = apPinsMapper.selectList(featuredWrapper);
        List<ApPins> featuredPins = allPublished.stream()
                .sorted((a, b) -> {
                    int scoreA = (a.getLikes() != null ? a.getLikes() : 0) + (a.getComment() != null ? a.getComment() : 0);
                    int scoreB = (b.getLikes() != null ? b.getLikes() : 0) + (b.getComment() != null ? b.getComment() : 0);
                    return Integer.compare(scoreB, scoreA);
                })
                .limit(3)
                .collect(Collectors.toList());
        vo.setFeaturedPins(convertToVOList(featuredPins, user));

        // 推荐话题
        try {
            Map<String, Object> topicResult = topicService.recommend(0, 5);
            @SuppressWarnings("unchecked")
            List<TopicRecommendVO> topics = (List<TopicRecommendVO>) topicResult.get("list");
            vo.setRecommendedTopics(topics != null ? topics : new ArrayList<>());
        } catch (Exception e) {
            log.error("获取推荐话题失败", e);
            vo.setRecommendedTopics(new ArrayList<>());
        }

        return ResponseResult.okResult(vo);
    }

    // ========== 评论列表 ==========

    public ResponseResult commentList(Long pinsId, Integer page, Integer size, String sort) {
        if (pinsId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "pinsId不能为空");
        }

        boolean hot = "hot".equalsIgnoreCase(sort);

        // 分页查询顶级评论
        Page<ApPinsComment> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApPinsComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApPinsComment::getPinsId, pinsId);
        wrapper.and(w -> w.isNull(ApPinsComment::getParentId).or().eq(ApPinsComment::getParentId, 0));
        if (hot) {
            wrapper.orderByDesc(ApPinsComment::getLikeCount).orderByDesc(ApPinsComment::getCreatedTime);
        } else {
            wrapper.orderByDesc(ApPinsComment::getCreatedTime);
        }
        IPage<ApPinsComment> pageResult = apPinsCommentMapper.selectPage(pageParam, wrapper);
        List<ApPinsComment> pageComments = pageResult.getRecords();
        long total = pageResult.getTotal();

        // 批量查询子回复
        List<ApPinsComment> allSubReplies = new ArrayList<>();
        if (!pageComments.isEmpty()) {
            List<Long> parentIds = pageComments.stream().map(ApPinsComment::getId).collect(Collectors.toList());
            LambdaQueryWrapper<ApPinsComment> replyWrapper = new LambdaQueryWrapper<>();
            replyWrapper.in(ApPinsComment::getParentId, parentIds);
            replyWrapper.orderByAsc(ApPinsComment::getCreatedTime);
            allSubReplies = apPinsCommentMapper.selectList(replyWrapper);
        }
        Map<Long, List<ApPinsComment>> replyMap = allSubReplies.stream()
                .collect(Collectors.groupingBy(ApPinsComment::getParentId));

        // 转换为VO，每个顶级评论附带子回复
        List<PinsCommentVO> voList = pageComments.stream().map(comment -> {
            PinsCommentVO vo = convertCommentToVO(comment);
            List<ApPinsComment> replies = replyMap.getOrDefault(comment.getId(), new ArrayList<>());
            List<PinsCommentVO> replyVOs = replies.stream()
                    .map(this::convertCommentToVO)
                    .collect(Collectors.toList());
            vo.setReplies(replyVOs);
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        return ResponseResult.okResult(data);
    }

    // ========== 话题列表 ==========

    public ResponseResult topics(String keyword, Integer page, Integer size) {
        Page<ApTopic> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApTopic> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(ApTopic::getName, keyword);
        }
        wrapper.orderByDesc(ApTopic::getPostCount);
        IPage<ApTopic> result = topicMapper.selectPage(pageParam, wrapper);
        List<Map<String, Object>> voList = result.getRecords().stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName() != null ? t.getName() : "");
            m.put("count", t.getPostCount() != null ? t.getPostCount() : 0);
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", result.getTotal());
        data.put("page", page);
        data.put("size", size);
        return ResponseResult.okResult(data);
    }

    // ========== 圈子列表 ==========

    /**
     * 获取所有圈子（按类别分组）
     * 数据模型：ap_circle_category 存储分类，ap_circle 通过 category_id 关联分类
     */
    public ResponseResult circles() {
        // 1. 查询所有分类（按 sort_order 排序）
        LambdaQueryWrapper<ApCircleCategory> categoryWrapper = new LambdaQueryWrapper<>();
        categoryWrapper.orderByAsc(ApCircleCategory::getSortOrder);
        List<ApCircleCategory> categories = apCircleCategoryMapper.selectList(categoryWrapper);

        // 2. 查询所有圈子（关联分类）
        LambdaQueryWrapper<ApCircle> circleWrapper = new LambdaQueryWrapper<>();
        circleWrapper.isNotNull(ApCircle::getCategoryId);
        circleWrapper.orderByAsc(ApCircle::getSortOrder);
        List<ApCircle> allCircles = apCircleMapper.selectList(circleWrapper);

        // 3. 按 category_id 分组
        Map<Long, List<ApCircle>> circlesByCategory = allCircles.stream()
                .collect(Collectors.groupingBy(ApCircle::getCategoryId));

        // 4. 构建返回数据：分类 + 该分类下的圈子列表
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApCircleCategory category : categories) {
            Map<String, Object> categoryMap = new HashMap<>();
            categoryMap.put("id", category.getId());
            categoryMap.put("name", category.getName() != null ? category.getName() : "");

            // 获取该分类下的圈子
            List<ApCircle> circles = circlesByCategory.getOrDefault(category.getId(), new ArrayList<>());
            List<Map<String, Object>> circleList = new ArrayList<>();
            for (ApCircle circle : circles) {
                Map<String, Object> circleMap = new HashMap<>();
                circleMap.put("id", circle.getId());
                circleMap.put("name", circle.getName() != null ? circle.getName() : "");
                circleMap.put("icon", circle.getIcon() != null ? circle.getIcon() : "");
                circleMap.put("memberCount", circle.getMemberCount() != null ? circle.getMemberCount() : 0);
                circleMap.put("pinsCount", circle.getPinsCount() != null ? circle.getPinsCount() : 0);
                circleList.add(circleMap);
            }
            categoryMap.put("circles", circleList);
            result.add(categoryMap);
        }
        return ResponseResult.okResult(result);
    }

    // ========== 链接预览 ==========

    public ResponseResult linkPreview(PinsLinkPreviewDTO dto) {
        if (dto.getUrl() == null || dto.getUrl().trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "URL不能为空");
        }
        PinsLinkPreviewVO vo = new PinsLinkPreviewVO();
        vo.setUrl(dto.getUrl());
        try {
            URI uri = new URI(dto.getUrl());
            String domain = uri.getHost();
            vo.setDomain(domain != null ? domain : "");
        } catch (Exception e) {
            vo.setDomain("");
        }
        vo.setTitle("");
        return ResponseResult.okResult(vo);
    }

    // ========== 工具方法 ==========

    public ApUser getUserOrNull() {
        try {
            return AppThreadLocalUtil.getUser();
        } catch (Exception e) {
            return null;
        }
    }

    public List<PinsVO> convertToVOList(List<ApPins> pinsList, ApUser currentUser) {
        if (pinsList == null || pinsList.isEmpty()) {
            return new ArrayList<>();
        }
        // 批量查询当前用户是否已点赞
        Set<Long> likedPinsIds = new HashSet<>();
        if (currentUser != null) {
            List<Long> pinsIds = pinsList.stream().map(ApPins::getId).collect(Collectors.toList());
            LambdaQueryWrapper<ApPinsLike> likeWrapper = new LambdaQueryWrapper<>();
            likeWrapper.in(ApPinsLike::getPinsId, pinsIds);
            likeWrapper.eq(ApPinsLike::getUserId, currentUser.getId());
            List<ApPinsLike> likedList = apPinsLikeMapper.selectList(likeWrapper);
            likedPinsIds = likedList.stream().map(ApPinsLike::getPinsId).collect(Collectors.toSet());
        }

        // 批量查询圈子名称，避免 N+1 查询
        Map<Long, String> circleNameMap = loadCircleNameMap(pinsList);

        Set<Long> finalLikedPinsIds = likedPinsIds;
        return pinsList.stream().map(pin -> convertToVO(pin, finalLikedPinsIds, circleNameMap)).collect(Collectors.toList());
    }

    /**
     * 批量加载圈子ID -> 圈子名称映射
     */
    private Map<Long, String> loadCircleNameMap(List<ApPins> pinsList) {
        Map<Long, String> circleNameMap = new HashMap<>();
        Set<Long> circleIds = pinsList.stream()
                .map(ApPins::getCircleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (circleIds.isEmpty()) {
            return circleNameMap;
        }
        List<ApCircle> circles = apCircleMapper.selectBatchIds(circleIds);
        if (circles != null) {
            for (ApCircle circle : circles) {
                if (circle.getId() != null && circle.getName() != null) {
                    circleNameMap.put(circle.getId(), circle.getName());
                }
            }
        }
        return circleNameMap;
    }

    public PinsVO convertToVO(ApPins pin, Set<Long> likedPinsIds) {
        return convertToVO(pin, likedPinsIds, loadCircleNameMap(Collections.singletonList(pin)));
    }

    public PinsVO convertToVO(ApPins pin, Set<Long> likedPinsIds, Map<Long, String> circleNameMap) {
        PinsVO vo = new PinsVO();
        vo.setId(pin.getId());
        vo.setUserId(pin.getUserId());
        vo.setUserName(pin.getUserName() != null ? pin.getUserName() : "");
        vo.setUserAvatar(pin.getUserAvatar() != null ? pin.getUserAvatar() : "");
        vo.setAuthorId(pin.getAuthorId());
        vo.setAuthorName(pin.getAuthorName() != null ? pin.getAuthorName() : "");
        vo.setAuthorImage(pin.getAuthorImage() != null ? pin.getAuthorImage() : "");
        vo.setContent(pin.getContent() != null ? pin.getContent() : "");
        vo.setImageUrls(parseStringList(pin.getImageUrls()));
        vo.setTopicTags(parseStringList(pin.getTopicTags()));
        vo.setTopicId(pin.getTopicId());
        vo.setCircleId(pin.getCircleId());
        String circleName = circleNameMap != null ? circleNameMap.get(pin.getCircleId()) : null;
        vo.setCircleName(circleName != null ? circleName : "");
        vo.setLinkUrl(pin.getLinkUrl() != null ? pin.getLinkUrl() : "");
        vo.setLinkTitle(pin.getLinkTitle() != null ? pin.getLinkTitle() : "");
        vo.setLikeCount(pin.getLikes() != null ? pin.getLikes() : 0);
        vo.setCommentCount(pin.getComment() != null ? pin.getComment() : 0);
        vo.setShareCount(pin.getShareCount() != null ? pin.getShareCount() : 0);
        vo.setLiked(likedPinsIds != null && likedPinsIds.contains(pin.getId()));
        vo.setCreatedTime(pin.getCreatedTime());
        vo.setPublishTime(pin.getPublishTime());
        vo.setReviewTime(pin.getReviewTime());
        return vo;
    }

    public List<String> parseStringList(String str) {
        if (str == null || str.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public PinsCommentVO convertCommentToVO(ApPinsComment comment) {
        PinsCommentVO vo = new PinsCommentVO();
        vo.setId(comment.getId());
        vo.setPinsId(comment.getPinsId());
        vo.setUserId(comment.getUserId());
        vo.setUserName(comment.getUserName() != null ? comment.getUserName() : "");
        vo.setUserAvatar(comment.getUserAvatar() != null ? comment.getUserAvatar() : "");
        vo.setParentId(comment.getParentId() != null ? comment.getParentId() : 0L);
        vo.setContent(comment.getContent() != null ? comment.getContent() : "");
        vo.setImageUrls(parseStringList(comment.getImageUrls()));
        vo.setReplyToUserId(comment.getReplyToUserId());
        vo.setReplyToUserName(comment.getReplyToUserName() != null ? comment.getReplyToUserName() : "");
        vo.setLikeCount(comment.getLikeCount() != null ? comment.getLikeCount() : 0);
        vo.setReplyCount(comment.getReplyCount() != null ? comment.getReplyCount() : 0);
        vo.setReplies(new ArrayList<>());
        vo.setCreatedTime(comment.getCreatedTime());
        return vo;
    }
}