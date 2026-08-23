package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.heima.content.mapper.level.ApPermissionDefinitionMapper;
import com.heima.content.mapper.level.ApUserPermissionMapper;
import com.heima.model.level.pojos.ApPermissionDefinition;
import com.heima.model.level.pojos.ApUserPermission;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LevelPermissionServiceImpl 单元测试（用户权限查询/授予/回收/基础权限分配）
 *
 * @Service 依赖 userPermissionMapper、permissionDefinitionMapper。
 * 覆盖：
 * - hasPermission 判定；
 * - getUserPermissions 取未过期权限码；
 * - updateUserPermissions 升级授予(空记录新建/已过期重置)、降级回收(未过期回收)、不跨界不变更；
 * - assignBasicPermissions 首次分配基础权限 / 已有权限跳过。
 */
class LevelPermissionServiceImplTest {

    @Mock
    private ApUserPermissionMapper userPermissionMapper;
    @Mock
    private ApPermissionDefinitionMapper permissionDefinitionMapper;

    @InjectMocks
    private LevelPermissionServiceImpl permissionService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserPermission.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApPermissionDefinition.class);
    }

    private ApPermissionDefinition def(int requiredLevel, String code) {
        ApPermissionDefinition d = new ApPermissionDefinition();
        d.setRelatedLevelType(2);
        d.setIsActive(1);
        d.setRequiredLevel(requiredLevel);
        d.setPermissionCode(code);
        return d;
    }

    private void stubDefinitions(ApPermissionDefinition... defs) {
        when(permissionDefinitionMapper.selectList(any())).thenReturn(List.of(defs));
    }

    // ---------- hasPermission ----------
    @Test
    @DisplayName("hasPermission 存在未过期权限返回 true")
    void hasPermissionTrue() {
        when(userPermissionMapper.selectCount(any())).thenReturn(1L);
        assertTrue(permissionService.hasPermission(userId, "can_comment"));
    }

    @Test
    @DisplayName("hasPermission 无权限返回 false")
    void hasPermissionFalse() {
        when(userPermissionMapper.selectCount(any())).thenReturn(0L);
        assertFalse(permissionService.hasPermission(userId, "can_comment"));
    }

    // ---------- getUserPermissions ----------
    @Test
    @DisplayName("getUserPermissions 返回未过期权限码集合")
    void getUserPermissions() {
        ApUserPermission p = new ApUserPermission();
        p.setPermissionCode("can_like");
        when(userPermissionMapper.selectList(any())).thenReturn(List.of(p));
        assertEquals(List.of("can_like"), permissionService.getUserPermissions(userId));
    }

    // ---------- updateUserPermissions ----------
    @Test
    @DisplayName("升级时授予新解锁权限（新建记录）")
    void updateUserPermissionsGrantNew() {
        stubDefinitions(def(1, "p_a"), def(2, "p_b"), def(3, "p_c"));
        when(userPermissionMapper.selectOne(any())).thenReturn(null);

        permissionService.updateUserPermissions(userId, 2, 1, 2); // 仅 p_b 满足 new2>=2 & old1<2

        verify(userPermissionMapper).insert(any(ApUserPermission.class)); // 一次插入 p_b
    }

    @Test
    @DisplayName("升级时对已过期权限重置为有效")
    void updateUserPermissionsReactivate() {
        stubDefinitions(def(2, "p_b"));
        ApUserPermission expired = new ApUserPermission();
        expired.setExpiredAt(new Date());
        when(userPermissionMapper.selectOne(any())).thenReturn(expired);

        permissionService.updateUserPermissions(userId, 2, 1, 2); // new2>=2 & old1<2 -> grant p_b

        verify(userPermissionMapper).updateById(any(ApUserPermission.class)); // 清除过期时间
    }

    @Test
    @DisplayName("降级时回收已解锁权限")
    void updateUserPermissionsRevoke() {
        stubDefinitions(def(1, "p_x"), def(2, "p_b"), def(3, "p_c"));
        ApUserPermission active = new ApUserPermission();
        active.setExpiredAt(null);
        when(userPermissionMapper.selectOne(any())).thenReturn(active);

        permissionService.updateUserPermissions(userId, 2, 2, 1); // 仅 p_b 满足 new1<2 & old2>=2

        verify(userPermissionMapper).updateById(any(ApUserPermission.class)); // 置过期
    }

    @Test
    @DisplayName("等级不跨越门槛时不变更权限")
    void updateUserPermissionsNoChange() {
        stubDefinitions(def(1, "p_a"), def(2, "p_b"));
        when(userPermissionMapper.selectOne(any())).thenReturn(null);

        permissionService.updateUserPermissions(userId, 2, 1, 1); // old==new 均不通过

        verify(userPermissionMapper, never()).insert(any(ApUserPermission.class));
        verify(userPermissionMapper, never()).updateById(any(ApUserPermission.class));
    }

    // ---------- assignBasicPermissions ----------
    @Test
    @DisplayName("首次分配全部基础权限")
    void assignBasicPermissionsFirstTime() {
        when(userPermissionMapper.selectList(any())).thenReturn(List.of());
        when(userPermissionMapper.selectOne(any())).thenReturn(null);

        permissionService.assignBasicPermissions(userId);

        verify(userPermissionMapper, times(7)).insert(any(ApUserPermission.class));
    }

    @Test
    @DisplayName("已有权限时跳过基础权限分配")
    void assignBasicPermissionsSkipped() {
        ApUserPermission existing = new ApUserPermission();
        existing.setPermissionCode("can_comment");
        when(userPermissionMapper.selectList(any())).thenReturn(List.of(existing));

        permissionService.assignBasicPermissions(userId);

        verify(userPermissionMapper, never()).insert(any(ApUserPermission.class));
    }
}