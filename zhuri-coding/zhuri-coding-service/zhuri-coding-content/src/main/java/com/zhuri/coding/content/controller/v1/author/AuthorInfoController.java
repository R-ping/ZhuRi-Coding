package com.heima.content.controller.v1.author;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.user.IUserClient;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.service.level.LevelService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 作者信息聚合接口：一次返回作者基本信息、职位、逐日等级、逐力值等级、关注数、粉丝数、是否已关注
 * 供沸点/文章列表作者昵称/头像悬浮卡片使用
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/author")
public class AuthorInfoController {

    @Autowired
    private IUserClient userClient;

    @Autowired
    private LevelService levelService;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @GetMapping("/info")
    public ResponseResult getAuthorInfo(@RequestParam("userId") Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "userId不能为空");
        }

        Map<String, Object> result = new HashMap<>();

        // 1. 用户公开信息（昵称、头像、职位/公司/简介）
        String nickname = "";
        String avatar = "";
        String position = "";
        String company = "";
        String bio = "";
        try {
            ResponseResult userResult = userClient.getPublicInfo(userId);
            if (userResult != null && userResult.getCode() == 200 && userResult.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> userData = (Map<String, Object>) userResult.getData();
                nickname = str(userData.get("nickname"));
                avatar = str(userData.get("avatar"));
                position = str(userData.get("position"));
                company = str(userData.get("company"));
                bio = str(userData.get("bio"));
            }
        } catch (Exception e) {
            log.warn("获取用户公开信息失败, userId={}", userId, e);
        }
        result.put("userId", userId);
        result.put("nickname", nickname);
        result.put("avatar", avatar);
        result.put("position", position);
        result.put("company", company);
        result.put("bio", bio);

        // 2. 逐日等级 + 逐力值等级
        Map<String, Object> levelInfo = levelService.getUserLevelInfo(userId);
        result.put("dailyLevel", levelInfo.getOrDefault("dailyLevel", 1));
        result.put("dailyTitle", levelInfo.getOrDefault("dailyTitle", ""));
        result.put("powerLevel", levelInfo.getOrDefault("powerLevel", 1));
        result.put("powerTitle", levelInfo.getOrDefault("powerTitle", ""));

        // 3. 关注数（该用户关注了多少人）与粉丝数
        long followCount = apFollowMapper.selectCount(
                new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getUserId, userId.intValue()));
        long followerCount = apFollowMapper.selectCount(
                new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getFollowUserId, userId.intValue()));
        result.put("followCount", followCount);
        result.put("followerCount", followerCount);

        // 4. 当前登录用户是否已关注该作者
        boolean isFollowed = false;
        ApUser currentUser = AppThreadLocalUtil.getUser();
        if (currentUser != null && currentUser.getId() != null
                && !currentUser.getId().equals(userId.intValue())) {
            Long currentUserId = currentUser.getId().longValue();
            Long count = apFollowMapper.selectCount(
                    new LambdaQueryWrapper<ApFollow>()
                            .eq(ApFollow::getUserId, currentUserId.intValue())
                            .eq(ApFollow::getFollowUserId, userId.intValue()));
            isFollowed = count != null && count > 0;
        }
        result.put("isFollowed", isFollowed);

        return ResponseResult.okResult(result);
    }

    private String str(Object val) {
        return val != null ? val.toString() : "";
    }
}