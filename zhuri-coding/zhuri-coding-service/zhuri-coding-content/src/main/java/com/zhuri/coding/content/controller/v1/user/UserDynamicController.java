package com.zhuri.coding.content.controller.v1.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.behavior.pojos.UserBehaviorRecord;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.model.user.vo.UserDynamicVO;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 个人主页动态聚合接口
 * 返回用户的行为动作记录（点赞文章/沸点、关注用户、发布文章/沸点），按时间线降序排列
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user/dynamic")
public class UserDynamicController {

    /** 需要展示的动态行为类型集合 */
    private static final Set<String> DYNAMIC_TYPES = Set.of(
            BehaviorType.LIKE_ARTICLE.getCode(),
            BehaviorType.LIKE_PIN.getCode(),
            BehaviorType.FOLLOW_USER.getCode(),
            BehaviorType.PUBLISH_ARTICLE.getCode(),
            BehaviorType.PUBLISH_PIN.getCode());

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private IUserClient userClient;

    /**
     * 获取个人主页动态列表
     * GET /api/v1/user/dynamic?userId=123&size=50
     *
     * @param userId 目标用户ID（为空则取当前登录用户）
     * @param size   返回条数，默认 50，最大 200
     */
    @GetMapping
    public ResponseResult dynamic(@RequestParam(value = "userId", required = false) Long userId,
                                  @RequestParam(value = "size", defaultValue = "50") Integer size) {
        Integer uid;
        if (userId == null || userId <= 0) {
            ApUser current = AppThreadLocalUtil.getUser();
            if (current == null || current.getId() == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
            }
            uid = current.getId();
        } else {
            uid = userId.intValue();
        }

        if (size == null || size <= 0) {
            size = 50;
        }
        int limit = Math.min(size, 200);

        // 1. 查询用户的动态行为记录（时间线降序）
        LambdaQueryWrapper<UserBehaviorRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBehaviorRecord::getUserId, uid)
               .eq(UserBehaviorRecord::getStatus, 1)
               .in(UserBehaviorRecord::getBehaviorType, DYNAMIC_TYPES)
               .orderByDesc(UserBehaviorRecord::getCreatedTime)
               .last("LIMIT " + limit);
        List<UserBehaviorRecord> records = behaviorRecordMapper.selectList(wrapper);
        if (records == null || records.isEmpty()) {
            return ResponseResult.okResult(new ArrayList<>());
        }

        // 2. 批量加载关联目标数据，避免逐条查询
        List<Long> articleIds = new ArrayList<>();
        List<Long> pinsIds = new ArrayList<>();
        List<Integer> userTargetIds = new ArrayList<>();
        for (UserBehaviorRecord r : records) {
            if (isArticleType(r.getBehaviorType())) {
                articleIds.add(r.getTargetId());
            } else if (isPinsType(r.getBehaviorType())) {
                pinsIds.add(r.getTargetId());
            } else if (isFollowType(r.getBehaviorType())) {
                userTargetIds.add(r.getTargetUserId() != null ? r.getTargetUserId() : r.getTargetId().intValue());
            }
        }

        Map<Long, ApArticle> articleMap = loadArticles(articleIds);
        Map<Long, ApPins> pinsMap = loadPins(pinsIds);
        Map<Integer, Map<String, Object>> userMap = loadUsers(userTargetIds);

        // 3. 组装动态 VO
        List<UserDynamicVO> voList = new ArrayList<>();
        for (UserBehaviorRecord r : records) {
            UserDynamicVO vo = buildVO(r, articleMap, pinsMap, userMap);
            if (vo != null) {
                voList.add(vo);
            }
        }

        return ResponseResult.okResult(voList);
    }

    private UserDynamicVO buildVO(UserBehaviorRecord r,
                                  Map<Long, ApArticle> articleMap,
                                  Map<Long, ApPins> pinsMap,
                                  Map<Integer, Map<String, Object>> userMap) {
        String code = r.getBehaviorType();
        UserDynamicVO vo = new UserDynamicVO();
        vo.setId(r.getId());
        vo.setBehaviorType(code);
        vo.setTargetType(r.getTargetType());
        vo.setTargetId(r.getTargetId());
        vo.setCreatedTime(r.getCreatedTime());

        if (isArticleType(code)) {
            ApArticle article = articleMap.get(r.getTargetId());
            if (article == null) {
                return null;
            }
            vo.setActionCategory(isPublishType(code) ? "publish" : "like");
            vo.setBehaviorDesc(isPublishType(code) ? "发布了文章" : "点赞了文章");
            vo.setTargetTitle(article.getTitle());
            vo.setTargetCover(article.getCoverImage());
            vo.setTargetUrl("/content/article/" + article.getId());
            vo.setTargetMeta(formatCount(article.getViews()) + " 阅读");
            return vo;
        }

        if (isPinsType(code)) {
            ApPins pins = pinsMap.get(r.getTargetId());
            if (pins == null) {
                return null;
            }
            vo.setActionCategory(isPublishType(code) ? "publish" : "like");
            vo.setBehaviorDesc(isPublishType(code) ? "发布了沸点" : "点赞了沸点");
            vo.setTargetTitle(pins.getContent());
            vo.setTargetCover(firstImage(pins.getImageUrls()));
            vo.setTargetUrl("/pins/detail/" + pins.getId());
            vo.setTargetMeta(formatCount(pins.getViews()) + " 浏览");
            return vo;
        }

        if (isFollowType(code)) {
            Integer targetUid = r.getTargetUserId() != null ? r.getTargetUserId() : r.getTargetId().intValue();
            Map<String, Object> user = userMap.get(targetUid);
            if (user == null) {
                return null;
            }
            vo.setActionCategory("follow");
            vo.setBehaviorDesc("关注了用户");
            vo.setTargetTitle(str(user.get("nickname")));
            vo.setTargetCover(str(user.get("avatar")));
            vo.setTargetUrl("/user/" + targetUid);
            vo.setTargetMeta("");
            return vo;
        }

        return null;
    }

    private Map<Long, ApArticle> loadArticles(List<Long> ids) {
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

    private Map<Long, ApPins> loadPins(List<Long> ids) {
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

    private Map<Integer, Map<String, Object>> loadUsers(List<Integer> ids) {
        Map<Integer, Map<String, Object>> map = new HashMap<>();
        List<Integer> distinct = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinct.isEmpty()) {
            return map;
        }
        for (Integer uid : distinct) {
            try {
                ResponseResult result = userClient.getPublicInfo(uid.longValue());
                if (result != null && result.getCode() == 200 && result.getData() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) result.getData();
                    Map<String, Object> info = new HashMap<>();
                    info.put("nickname", data.get("nickname"));
                    info.put("avatar", data.get("avatar"));
                    map.put(uid, info);
                }
            } catch (Exception e) {
                log.warn("获取用户公开信息失败, userId={}", uid, e);
            }
        }
        return map;
    }

    private boolean isArticleType(String code) {
        return BehaviorType.LIKE_ARTICLE.getCode().equals(code)
                || BehaviorType.PUBLISH_ARTICLE.getCode().equals(code);
    }

    private boolean isPinsType(String code) {
        return BehaviorType.LIKE_PIN.getCode().equals(code)
                || BehaviorType.PUBLISH_PIN.getCode().equals(code);
    }

    private boolean isFollowType(String code) {
        return BehaviorType.FOLLOW_USER.getCode().equals(code);
    }

    private boolean isPublishType(String code) {
        return BehaviorType.PUBLISH_ARTICLE.getCode().equals(code)
                || BehaviorType.PUBLISH_PIN.getCode().equals(code);
    }

    private String firstImage(String imageUrls) {
        if (imageUrls == null || imageUrls.isBlank()) {
            return "";
        }
        // 支持逗号分隔或 JSON 数组字符串，取第一张
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

    private String formatCount(Integer value) {
        long v = value != null ? value : 0L;
        if (v >= 10000) {
            return String.format("%.1fw", v / 10000.0);
        }
        if (v >= 1000) {
            return String.format("%.1fk", v / 1000.0);
        }
        return String.valueOf(v);
    }

    private String str(Object val) {
        return val != null ? val.toString() : "";
    }
}
