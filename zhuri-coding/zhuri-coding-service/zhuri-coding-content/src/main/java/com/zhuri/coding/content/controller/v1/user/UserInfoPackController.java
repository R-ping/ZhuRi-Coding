package com.zhuri.coding.content.controller.v1.user;

import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户信息聚合接口
 */
@RestController
@RequestMapping("/api/v1/user")
public class UserInfoPackController {

    @Autowired
    private LevelService levelService;

    /**
     * 用户信息聚合（基本信息 + 成长信息 + 计数信息）
     */
    @GetMapping("/info-pack")
    public ResponseResult getUserInfoPack() {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return ResponseResult.okResult(levelService.getUserInfoPack(userId));
    }
}
