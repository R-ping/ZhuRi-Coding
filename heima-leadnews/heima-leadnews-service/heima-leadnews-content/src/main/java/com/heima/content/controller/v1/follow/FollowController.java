
package com.heima.content.controller.v1.follow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.service.fans.FansDataService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/follow")
public class FollowController {

    @Autowired
    private FansDataService fansDataService;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @PostMapping("/do")
    public ResponseResult doFollow(@RequestParam("userId") Long userId, @RequestParam("followUserId") Long followUserId) {
        ApUser apUser = new ApUser();
        apUser.setId(userId.intValue());
        AppThreadLocalUtil.setUser(apUser);
        try {
            return fansDataService.followFans(followUserId.intValue());
        } finally {
            AppThreadLocalUtil.clear();
        }
    }

    /**
     * 查询用户 userId 是否已关注 followUserId（供 IM 互关检测等跨服务调用）
     */
    @GetMapping("/isFollowing")
    public ResponseResult isFollowing(@RequestParam("userId") Long userId, @RequestParam("followUserId") Long followUserId) {
        if (userId == null || followUserId == null) {
            return ResponseResult.errorResult(400, "userId/followUserId不能为空");
        }
        LambdaQueryWrapper<ApFollow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApFollow::getUserId, userId.intValue());
        wrapper.eq(ApFollow::getFollowUserId, followUserId.intValue());
        boolean isFollowing = apFollowMapper.selectCount(wrapper) > 0;
        Map<String, Object> result = new HashMap<>();
        result.put("isFollowing", isFollowing);
        return ResponseResult.okResult(result);
    }
}
