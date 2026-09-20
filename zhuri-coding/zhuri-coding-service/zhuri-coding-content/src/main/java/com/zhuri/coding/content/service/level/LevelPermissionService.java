package com.heima.content.service.level;

import java.util.List;

/**
 * 用户权限服务 — 负责权限查询、授予、回收
 */
public interface LevelPermissionService {

    /**
     * 检查用户是否拥有指定权限
     */
    boolean hasPermission(Long userId, String permissionCode);

    /**
     * 获取用户所有权限列表
     */
    List<String> getUserPermissions(Long userId);

    /**
     * 根据等级变化更新用户权限
     */
    void updateUserPermissions(Long userId, int levelType, int oldLevel, int newLevel);

    /**
     * 为新用户分配基础权限
     * 仅在用户无任何权限时分配（幂等设计）
     */
    void assignBasicPermissions(Long userId);
}