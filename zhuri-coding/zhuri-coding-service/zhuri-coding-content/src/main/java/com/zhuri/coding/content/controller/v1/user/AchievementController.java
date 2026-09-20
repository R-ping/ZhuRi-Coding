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
