package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.pojos.UserProfile;
import com.heima.user.mapper.ApUserMapper;
import com.heima.user.mapper.UserProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/user/feign")
@Slf4j
public class UserFeignController {

    @Autowired
    private ApUserMapper apUserMapper;

    @Autowired
    private UserProfileMapper userProfileMapper;

    /**
     * 获取用户基本信息（供其他服务Feign调用）
     */
    @GetMapping("/basic-info")
    public ResponseResult getBasicInfo(@RequestParam("userId") Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(400, "userId不能为空");
        }
        ApUser user = apUserMapper.selectById(userId);
        if (user == null) {
            return ResponseResult.errorResult(404, "用户不存在");
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", user.getId());
        userInfo.put("nickname", user.getNickname() != null ? user.getNickname() : "");
        userInfo.put("avatar", user.getImage() != null ? user.getImage() : "");
        return ResponseResult.okResult(userInfo);
    }

    /**
     * 获取用户公开信息（昵称、头像、职位/公司/简介），供作者悬浮卡片等场景 Feign 调用
     */
    @GetMapping("/public-info")
    public ResponseResult getPublicInfo(@RequestParam("userId") Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(400, "userId不能为空");
        }
        ApUser user = apUserMapper.selectById(userId);
        if (user == null) {
            return ResponseResult.errorResult(404, "用户不存在");
        }

        Map<String, Object> info = new HashMap<>();
        info.put("userId", user.getId());
        info.put("nickname", user.getNickname() != null ? user.getNickname() : "");
        info.put("avatar", user.getImage() != null ? user.getImage() : "");

        // 用户资料（职位/公司/简介）可能未初始化
        UserProfile profile = userProfileMapper.selectById(userId);
        info.put("position", profile != null && profile.getPosition() != null ? profile.getPosition() : "");
        info.put("company", profile != null && profile.getCompany() != null ? profile.getCompany() : "");
        info.put("bio", profile != null && profile.getBio() != null ? profile.getBio() : "");
        return ResponseResult.okResult(info);
    }
}