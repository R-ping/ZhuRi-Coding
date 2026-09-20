package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.level.ApPermissionDefinitionMapper;
import com.heima.content.mapper.level.ApUserPermissionMapper;
import com.heima.content.service.level.LevelPermissionService;
import com.heima.model.level.pojos.ApPermissionDefinition;
import com.heima.model.level.pojos.ApUserPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 用户权限服务实现 — 负责权限查询、授予、回收
 */
@Slf4j
@Service
public class LevelPermissionServiceImpl implements LevelPermissionService {

    @Autowired
    private ApUserPermissionMapper userPermissionMapper;

    @Autowired
    private ApPermissionDefinitionMapper permissionDefinitionMapper;

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        LambdaQueryWrapper<ApUserPermission> query = new LambdaQueryWrapper<>();
        query.eq(ApUserPermission::getUserId, userId);
        query.eq(ApUserPermission::getPermissionCode, permissionCode);
        query.isNull(ApUserPermission::getExpiredAt);
        return userPermissionMapper.selectCount(query) > 0;
    }

    @Override
    public List<String> getUserPermissions(Long userId) {
        LambdaQueryWrapper<ApUserPermission> query = new LambdaQueryWrapper<>();
        query.eq(ApUserPermission::getUserId, userId);
        query.isNull(ApUserPermission::getExpiredAt);
        List<ApUserPermission> permissions = userPermissionMapper.selectList(query);
        List<String> permissionCodes = new ArrayList<>();
        for (ApUserPermission p : permissions) {
            permissionCodes.add(p.getPermissionCode());
        }
        return permissionCodes;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserPermissions(Long userId, int levelType, int oldLevel, int newLevel) {
        LambdaQueryWrapper<ApPermissionDefinition> query = new LambdaQueryWrapper<>();
        query.eq(ApPermissionDefinition::getRelatedLevelType, levelType);
        query.eq(ApPermissionDefinition::getIsActive, 1);
        List<ApPermissionDefinition> permissions = permissionDefinitionMapper.selectList(query);

        for (ApPermissionDefinition permission : permissions) {
            int requiredLevel = permission.getRequiredLevel();
            String permissionCode = permission.getPermissionCode();

            if (newLevel >= requiredLevel && oldLevel < requiredLevel) {
                grantPermission(userId, permissionCode);
            } else if (newLevel < requiredLevel && oldLevel >= requiredLevel) {
                revokePermission(userId, permissionCode);
            }
        }
    }

    private void grantPermission(Long userId, String permissionCode) {
        LambdaQueryWrapper<ApUserPermission> query = new LambdaQueryWrapper<>();
        query.eq(ApUserPermission::getUserId, userId);
        query.eq(ApUserPermission::getPermissionCode, permissionCode);
        ApUserPermission existing = userPermissionMapper.selectOne(query);

        if (existing == null) {
            ApUserPermission userPermission = new ApUserPermission();
            userPermission.setUserId(userId);
            userPermission.setPermissionCode(permissionCode);
            userPermission.setGrantedAt(new Date());
            userPermissionMapper.insert(userPermission);
            log.info("用户{}获得权限{}", userId, permissionCode);
        } else if (existing.getExpiredAt() != null) {
            existing.setExpiredAt(null);
            userPermissionMapper.updateById(existing);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignBasicPermissions(Long userId) {
        List<String> existingPermissions = getUserPermissions(userId);
        if (!existingPermissions.isEmpty()) {
            return;
        }

        String[] basicPermissions = {
            "can_publish_article",
            "can_publish_pins",
            "can_comment",
            "can_follow",
            "can_like",
            "can_collect",
            "can_create_coloum"
        };

        for (String permissionCode : basicPermissions) {
            grantPermission(userId, permissionCode);
        }

        log.info("为用户{}分配基础权限完成", userId);
    }

    private void revokePermission(Long userId, String permissionCode) {
        LambdaQueryWrapper<ApUserPermission> query = new LambdaQueryWrapper<>();
        query.eq(ApUserPermission::getUserId, userId);
        query.eq(ApUserPermission::getPermissionCode, permissionCode);
        ApUserPermission existing = userPermissionMapper.selectOne(query);

        if (existing != null && existing.getExpiredAt() == null) {
            existing.setExpiredAt(new Date());
            userPermissionMapper.updateById(existing);
            log.info("用户{}失去权限{}", userId, permissionCode);
        }
    }
}