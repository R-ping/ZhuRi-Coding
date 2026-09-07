package com.heima.content.controller.v1.level;

import com.heima.content.service.level.LevelService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.level.pojos.ApLevelConfig;
import com.heima.model.level.pojos.ApUserLevel;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/level")
public class LevelController {

    @Autowired
    private LevelService levelService;

    @GetMapping("/user/{userId}")
    public ResponseEntity<ApUserLevel> getUserLevel(@PathVariable Long userId) {
        ApUserLevel userLevel = levelService.getUserLevel(userId);
        return ResponseEntity.ok(userLevel);
    }

    @GetMapping("/user/{userId}/info")
    public ResponseEntity<Map<String, Object>> getUserLevelInfo(@PathVariable Long userId) {
        Map<String, Object> levelInfo = levelService.getUserLevelInfo(userId);
        return ResponseEntity.ok(levelInfo);
    }

    @GetMapping("/user/{userId}/data")
    public ResponseEntity<Map<String, Object>> getUserLevelData(@PathVariable Long userId) {
        Map<String, Object> levelData = levelService.getUserLevelData(userId);
        return ResponseEntity.ok(levelData);
    }

    @GetMapping("/user/{userId}/tasks")
    public ResponseResult getTodayTaskProgress(@PathVariable Long userId) {
        Map<String, Object> taskProgress = levelService.getTodayTaskProgress(userId);
        return ResponseResult.okResult(taskProgress);
    }

    @GetMapping("/user/{userId}/permissions")
    public ResponseEntity<List<String>> getUserPermissions(@PathVariable Long userId) {
        List<String> permissions = levelService.getUserPermissions(userId);
        return ResponseEntity.ok(permissions);
    }

    @GetMapping("/user/{userId}/permission/{permissionCode}")
    public ResponseEntity<Map<String, Boolean>> checkPermission(
            @PathVariable Long userId,
            @PathVariable String permissionCode) {
        boolean hasPermission = levelService.hasPermission(userId, permissionCode);
        return ResponseEntity.ok(Map.of("hasPermission", hasPermission));
    }

    @PostMapping("/action")
    public ResponseEntity<Void> recordAction(
            @RequestParam Long userId,
            @RequestParam String actionType,
            @RequestParam(required = false) String actionDetail) {
        levelService.recordAction(userId, actionType, actionDetail);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/action/with-limit")
    public ResponseEntity<Map<String, Object>> recordActionWithLimit(
            @RequestParam Long userId,
            @RequestParam String actionType,
            @RequestParam(required = false) String actionDetail) {
        Map<String, Object> result = levelService.recordActionWithLimit(userId, actionType, actionDetail);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/power")
    public ResponseEntity<Map<String, Object>> calculatePower(
            @RequestParam Long userId,
            @RequestParam Long articleId,
            @RequestParam String changeType,
            @RequestParam Integer powerChange) {
        Map<String, Object> result = levelService.calculatePowerWithLimit(userId, articleId, changeType, powerChange);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/configs")
    public ResponseEntity<List<ApLevelConfig>> getLevelConfigs(@RequestParam(defaultValue = "1") Integer levelType) {
        List<ApLevelConfig> configs = levelService.getLevelConfigs(levelType);
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/daily-privileges")
    public ResponseResult getDailyPrivileges() {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return ResponseResult.okResult(levelService.getLevelPrivileges(userId));
    }

    @GetMapping("/privileges")
    public ResponseResult getPrivileges() {
        return ResponseResult.okResult(levelService.getCreatorLevelPrivileges());
    }

    @GetMapping("/growth-tasks")
    public ResponseResult getGrowthTasks() {
        return ResponseResult.okResult(levelService.getGrowthTasks());
    }

    @GetMapping("/user/{userId}/power-detail")
    public ResponseResult getPowerDetail(@PathVariable Long userId) {
        return ResponseResult.okResult(levelService.getPowerDetail(userId));
    }

    @GetMapping("/user/{userId}/benefits")
    public ResponseResult getUserBenefits(@PathVariable Long userId) {
        return ResponseResult.okResult(levelService.getUserBenefits(userId));
    }

    @PostMapping("/user/{userId}/assign-basic-permissions")
    public ResponseResult assignBasicPermissions(@PathVariable Long userId) {
        levelService.assignBasicPermissions(userId);
        return ResponseResult.okResult();
    }
}