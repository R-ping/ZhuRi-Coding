package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.level.ApBehaviorConfigMapper;
import com.heima.content.mapper.level.ApLevelPrivilegeMapper;
import com.heima.content.mapper.level.ApUserLevelMapper;
import com.heima.content.mapper.level.ApUserPowerLogMapper;
import com.heima.content.service.level.LevelPermissionService;
import com.heima.content.service.level.LevelService;
import com.heima.model.level.pojos.ApBehaviorConfig;
import com.heima.model.level.pojos.ApLevelConfig;
import com.heima.model.level.pojos.ApLevelPrivilege;
import com.heima.model.level.pojos.ApUserLevel;
import com.heima.model.level.pojos.ApUserPowerLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 等级服务 Facade — 委派各子服务处理具体职责
 *
 * 职责拆分：
 * - LevelQueryService: 用户等级查询、等级配置查询、等级计算
 * - LevelActionService: 行为记录、逐日分计算、签到
 * - LevelPowerService: 逐力值计算
 * - LevelPermissionService: 权限管理
 * - LevelDiamondService: 钻石奖励
 * - LevelTaskProgressBuilder: 任务进度构建（已独立）
 */
@Slf4j
@Service
public class LevelServiceImpl implements LevelService {

    @Autowired
    private LevelQueryService levelQueryService;

    @Autowired
    private LevelActionService levelActionService;

    @Autowired
    private LevelPowerService levelPowerService;

    @Autowired
    private LevelPermissionService permissionService;

    @Autowired
    private LevelPrivilegeService levelPrivilegeService;

    @Autowired
    private ApLevelPrivilegeMapper privilegeMapper;

    @Autowired
    private ApBehaviorConfigMapper behaviorConfigMapper;

    @Autowired
    private ApUserLevelMapper userLevelMapper;

    @Autowired
    private ApUserPowerLogMapper powerLogMapper;

    // ==================== 等级查询 ====================

    @Override
    public ApUserLevel getUserLevel(Long userId) {
        return levelQueryService.getUserLevel(userId);
    }

    @Override
    public Map<String, Object> getUserLevelInfo(Long userId) {
        return levelQueryService.getUserLevelInfo(userId);
    }

    @Override
    public Map<String, Object> getUserLevelData(Long userId) {
        return levelQueryService.getUserLevelData(userId);
    }

    @Override
    public List<ApLevelConfig> getLevelConfigs(Integer levelType) {
        return levelQueryService.getLevelConfigs(levelType);
    }

    @Override
    public Map<String, Object> getLevelPrivileges(Long userId) {
        return levelPrivilegeService.getLevelPrivileges(userId);
    }

    @Override
    public Map<String, Object> getUserInfoPack(Long userId) {
        return levelPrivilegeService.getUserInfoPack(userId);
    }

    // ==================== 行为记录 ====================

    @Override
    public void recordAction(Long userId, String actionType, String actionDetail) {
        levelActionService.recordAction(userId, actionType, actionDetail);
    }

    @Override
    public Map<String, Object> recordActionWithLimit(Long userId, String actionType, String actionDetail) {
        return levelActionService.recordActionWithLimit(userId, actionType, actionDetail);
    }

    @Override
    public Map<String, Object> rollbackActionWithLimit(Long userId, String actionType, String actionDetail) {
        return levelActionService.rollbackActionWithLimit(userId, actionType, actionDetail);
    }

    @Override
    public Map<String, Object> recordPaymentAction(Long userId, String actionType, BigDecimal amount,
        String actionDetail) {
        return levelActionService.recordPaymentAction(userId, actionType, amount, actionDetail);
    }


    @Override
    public Map<String, Object> getTodayTaskProgress(Long userId) {
        return levelActionService.getTodayTaskProgress(userId);
    }

    // ==================== 逐力值 ====================

    @Override
    public void calculatePower(Long userId, Long articleId, String changeType, Integer powerChange) {
        levelPowerService.calculatePower(userId, articleId, changeType, powerChange);
    }

    @Override
    public Map<String, Object> calculatePowerWithLimit(Long userId, Long articleId, String changeType,
        Integer powerChange) {
        return levelPowerService.calculatePowerWithLimit(userId, articleId, changeType, powerChange);
    }

    // ==================== 权限 ====================

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        return permissionService.hasPermission(userId, permissionCode);
    }

    @Override
    public List<String> getUserPermissions(Long userId) {
        return permissionService.getUserPermissions(userId);
    }

    @Override
    public void assignBasicPermissions(Long userId) {
        permissionService.assignBasicPermissions(userId);
    }

    // ==================== 创作者等级权益 ====================

    @Override
    public Map<String, Object> getCreatorLevelPrivileges() {
        LambdaQueryWrapper<ApLevelPrivilege> query = new LambdaQueryWrapper<>();
        query.eq(ApLevelPrivilege::getLevelType, 2);
        query.eq(ApLevelPrivilege::getIsActive, 1);
        query.orderByAsc(ApLevelPrivilege::getLevelValue, ApLevelPrivilege::getSortOrder);
        List<ApLevelPrivilege> privileges = privilegeMapper.selectList(query);

        Map<Integer, List<ApLevelPrivilege>> grouped = privileges.stream()
                .collect(Collectors.groupingBy(ApLevelPrivilege::getLevelValue));

        Map<String, Object> result = new HashMap<>();
        result.put("privilegesByLevel", grouped);
        return result;
    }

    @Override
    public Map<String, Object> getGrowthTasks() {
        LambdaQueryWrapper<ApBehaviorConfig> query = new LambdaQueryWrapper<>();
        query.eq(ApBehaviorConfig::getIsActive, 1);
        query.orderByAsc(ApBehaviorConfig::getGroupSort, ApBehaviorConfig::getSortOrder);
        List<ApBehaviorConfig> tasks = behaviorConfigMapper.selectList(query);

        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        return result;
    }

    @Override
    public Map<String, Object> getPowerDetail(Long userId) {
        Map<String, Object> result = new HashMap<>();

        ApUserLevel userLevel = userLevelMapper.selectOne(
                new LambdaQueryWrapper<ApUserLevel>()
                        .eq(ApUserLevel::getUserId, userId)
        );

        if (userLevel != null) {
            result.put("currentPower", userLevel.getPowerValue());
            result.put("currentLevel", userLevel.getPowerLevel());
            result.put("actionScore", userLevel.getActionScore());
            result.put("influenceScore", userLevel.getInfluenceScore());
            result.put("qualityScore", userLevel.getQualityScore());
            result.put("violationScore", userLevel.getViolationScore());
        } else {
            result.put("currentPower", 0);
            result.put("currentLevel", 1);
            result.put("actionScore", 0);
            result.put("influenceScore", 0);
            result.put("qualityScore", 0);
            result.put("violationScore", 0);
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);

        LambdaQueryWrapper<ApUserPowerLog> logQuery = new LambdaQueryWrapper<>();
        logQuery.eq(ApUserPowerLog::getUserId, userId);
        logQuery.ge(ApUserPowerLog::getRecordDate, startDate);
        logQuery.le(ApUserPowerLog::getRecordDate, endDate);
        logQuery.orderByDesc(ApUserPowerLog::getRecordDate);
        List<ApUserPowerLog> logs = powerLogMapper.selectList(logQuery);

        result.put("history", logs);
        return result;
    }

    @Override
    public Map<String, Object> getUserBenefits(Long userId) {
        ApUserLevel userLevel = userLevelMapper.selectOne(
                new LambdaQueryWrapper<ApUserLevel>()
                        .eq(ApUserLevel::getUserId, userId)
        );

        int currentLevel = userLevel != null && userLevel.getPowerLevel() != null
                ? userLevel.getPowerLevel() : 1;

        LambdaQueryWrapper<ApLevelPrivilege> query = new LambdaQueryWrapper<>();
        query.eq(ApLevelPrivilege::getLevelType, 2);
        query.eq(ApLevelPrivilege::getIsActive, 1);
        query.le(ApLevelPrivilege::getLevelValue, currentLevel);
        query.orderByAsc(ApLevelPrivilege::getLevelValue, ApLevelPrivilege::getSortOrder);

        List<ApLevelPrivilege> benefits = privilegeMapper.selectList(query);

        Map<String, Object> result = new HashMap<>();
        result.put("currentLevel", currentLevel);
        result.put("benefits", benefits);
        return result;
    }
}