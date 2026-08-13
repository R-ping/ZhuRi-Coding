package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.apis.user.IUserClient;
import com.heima.content.mapper.level.ApLevelPrivilegeMapper;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.level.pojos.ApLevelConfig;
import com.heima.model.level.pojos.ApLevelPrivilege;
import com.heima.model.level.pojos.ApUserLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 等级权益服务 — 负责等级经验权益规则组装、用户信息聚合
 */
@Slf4j
@Service
public class LevelPrivilegeService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private ApLevelPrivilegeMapper privilegeMapper;

    @Autowired
    private LevelQueryService levelQueryService;

    @Autowired
    private IUserClient userClient;

    /**
     * 等级经验权益规则（/api/v1/level/privileges）
     *
     * @param userId 当前用户ID，未登录传0
     */
    public Map<String, Object> getLevelPrivileges(Long userId) {
        Map<String, Object> result = new HashMap<>();

        // 1. 等级规则（level_type=1 逐日等级，按 levelValue 升序）
        List<Map<String, Object>> levelSpec = new ArrayList<>();
        for (ApLevelConfig config : levelQueryService.getLevelConfigs(1)) {
            Map<String, Object> item = new HashMap<>();
            item.put("level", config.getLevelValue() != null ? config.getLevelValue() : 0);
            item.put("min_score", config.getMinScore() != null ? config.getMinScore() : 0);
            item.put("max_score", config.getMaxScore() != null ? config.getMaxScore() : 0);
            item.put("level_title", config.getTitle() != null ? config.getTitle() : "");
            levelSpec.add(item);
        }

        // 2. 当前等级经验（未登录 userId=0 视为0）
        int currentLevel = 0;
        BigDecimal currentScore = BigDecimal.ZERO;
        if (userId != null && userId > 0) {
            ApUserLevel userLevel = levelQueryService.getUserLevel(userId);
            currentLevel = userLevel.getDailyLevel() != null ? userLevel.getDailyLevel() : 1;
            currentScore = userLevel.getDailyScore() != null ? userLevel.getDailyScore() : BigDecimal.ZERO;
        }

        // 3. 权益列表（level_type=1 且启用，按 needJscoreLevel、sortOrder 升序）
        LambdaQueryWrapper<ApLevelPrivilege> query = new LambdaQueryWrapper<>();
        query.eq(ApLevelPrivilege::getLevelType, 1);
        query.eq(ApLevelPrivilege::getIsActive, 1);
        query.orderByAsc(ApLevelPrivilege::getNeedJscoreLevel);
        query.orderByAsc(ApLevelPrivilege::getSortOrder);
        List<ApLevelPrivilege> privileges = privilegeMapper.selectList(query);

        // 按 needJscoreLevel 分组为二维数组（LinkedHashMap 保持升序）
        Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (ApLevelPrivilege privilege : privileges) {
            Integer needLevel = privilege.getNeedJscoreLevel() != null ? privilege.getNeedJscoreLevel() : 0;
            grouped.computeIfAbsent(needLevel, k -> new ArrayList<>())
                .add(buildPrivilegeItem(privilege, currentLevel));
        }
        List<List<Map<String, Object>>> levelPrivilege = new ArrayList<>(grouped.values());

        result.put("level_spec", levelSpec);
        result.put("level_privilege", levelPrivilege);
        result.put("current_level", currentLevel);
        result.put("current_score", currentScore);
        return result;
    }

    /**
     * 用户信息聚合（/api/v1/user/info-pack）
     *
     * @param userId 当前用户ID，未登录传0
     */
    public Map<String, Object> getUserInfoPack(Long userId) {
        boolean validUser = userId != null && userId > 0;
        Map<String, Object> result = new HashMap<>();

        // 1. 用户基本信息
        Map<String, Object> userBasic = new HashMap<>();
        userBasic.put("user_id", validUser ? userId : 0L);
        userBasic.put("user_name", "");
        userBasic.put("avatar_large", "");
        userBasic.put("description", "");
        userBasic.put("company", "");
        userBasic.put("job_title", "");
        if (validUser) {
            try {
                ResponseResult userResult = userClient.getBasicInfo(userId);
                if (userResult != null && userResult.getCode() == 200 && userResult.getData() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> userData = (Map<String, Object>) userResult.getData();
                    userBasic.put("user_name", strOrEmpty(userData.get("nickname")));
                    userBasic.put("avatar_large", strOrEmpty(userData.get("avatar")));
                }
            } catch (Exception e) {
                log.warn("获取用户基本信息失败 userId={}, error={}", userId, e.getMessage());
            }
        }

        // 2. 用户成长信息
        Map<String, Object> growth = new HashMap<>();
        growth.put("user_id", validUser ? userId : 0L);
        growth.put("jpower", 0);
        growth.put("jscore", 0);
        growth.put("jpower_level", 0);
        growth.put("jscore_level", 0);
        growth.put("jscore_title", "");
        growth.put("jscore_next_level_score", 0);
        growth.put("jscore_this_level_mini_score", 0);
        if (validUser) {
            ApUserLevel userLevel = levelQueryService.getUserLevel(userId);
            BigDecimal dailyScore = userLevel.getDailyScore() != null ? userLevel.getDailyScore() : BigDecimal.ZERO;
            Integer dailyLevel = userLevel.getDailyLevel() != null ? userLevel.getDailyLevel() : 1;
            growth.put("jpower", userLevel.getPowerValue() != null ? userLevel.getPowerValue() : 0);
            growth.put("jscore", dailyScore);
            growth.put("jpower_level", userLevel.getPowerLevel() != null ? userLevel.getPowerLevel() : 1);
            growth.put("jscore_level", dailyLevel);

            // 等级配置（level_type=1），取当前等级标题与上下级门槛分
            for (ApLevelConfig config : levelQueryService.getLevelConfigs(1)) {
                int levelValue = config.getLevelValue() != null ? config.getLevelValue() : 0;
                if (levelValue == dailyLevel) {
                    growth.put("jscore_title", config.getTitle() != null ? config.getTitle() : "");
                    growth.put("jscore_this_level_mini_score",
                        config.getMinScore() != null ? config.getMinScore() : 0);
                } else if (levelValue == dailyLevel + 1) {
                    growth.put("jscore_next_level_score",
                        config.getMinScore() != null ? config.getMinScore() : 0);
                }
            }
        }

        // 3. 用户计数（暂无数据，空Map）
        result.put("user_basic", userBasic);
        result.put("user_counter", new HashMap<>());
        result.put("user_growth_info", growth);
        return result;
    }

    /**
     * 构建单个权益项
     */
    private Map<String, Object> buildPrivilegeItem(ApLevelPrivilege privilege, int currentLevel) {
        Map<String, Object> item = new HashMap<>();
        Integer needLevel = privilege.getNeedJscoreLevel() != null ? privilege.getNeedJscoreLevel() : 0;
        item.put("priv_id", privilege.getId());
        item.put("title", privilege.getPrivilegeName() != null ? privilege.getPrivilegeName() : "");
        item.put("icon", privilege.getIconName() != null ? privilege.getIconName() : "");
        item.put("priv_status", needLevel <= currentLevel ? 1 : 0);
        item.put("desc", parseDescJson(privilege.getDescJson()));
        item.put("poster", privilege.getPosterName() != null ? privilege.getPosterName() : "");
        item.put("need_jscore_level", needLevel);
        item.put("web_jump_url", privilege.getWebJumpUrl() != null ? privilege.getWebJumpUrl() : "");
        item.put("app_jump_url", privilege.getAppJumpUrl() != null ? privilege.getAppJumpUrl() : "");
        return item;
    }

    /**
     * 解析权益说明 descJson（[{desc_title, desc_content}]），失败返回空列表
     */
    private List<Map<String, String>> parseDescJson(String descJson) {
        if (descJson == null || descJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(descJson, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (Exception e) {
            log.warn("解析权益descJson失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }
}
