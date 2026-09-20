package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.heima.apis.user.IUserClient;
import com.heima.content.mapper.level.ApLevelPrivilegeMapper;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.level.pojos.ApLevelConfig;
import com.heima.model.level.pojos.ApLevelPrivilege;
import com.heima.model.level.pojos.ApUserLevel;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LevelPrivilegeService 单元测试（等级经验权益规则 / 用户信息聚合）
 *
 * @Service 依赖 privilegeMapper、LevelQueryService、IUserClient，均可 @Mock 注入。
 * 覆盖：
 * - getLevelPrivileges：未登录(userId<=0)与登录两种分支、level_spec 组装、权益按 needJscoreLevel 分组、priv_status 解锁判断、descJson 正常/空/非法解析；
 * - getUserInfoPack：未登录空态、登录回填用户基本信息与成长信息、userClient 异常降级、当前级与下一级门槛分匹配。
 */
class LevelPrivilegeServiceTest {

    @Mock
    private ApLevelPrivilegeMapper privilegeMapper;
    @Mock
    private LevelQueryService levelQueryService;
    @Mock
    private IUserClient userClient;

    @InjectMocks
    private LevelPrivilegeService levelPrivilegeService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 预热 MybatisPlus 实体表元数据
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApLevelPrivilege.class);
    }

    private ApLevelConfig config(Integer levelValue, Integer minScore, String title) {
        ApLevelConfig c = new ApLevelConfig();
        c.setLevelValue(levelValue);
        c.setMinScore(minScore);
        c.setTitle(title);
        return c;
    }

    private ApLevelPrivilege privilege(Integer needLevel, Integer sortOrder, String descJson) {
        ApLevelPrivilege p = new ApLevelPrivilege();
        p.setId(1L);
        p.setNeedJscoreLevel(needLevel);
        p.setSortOrder(sortOrder);
        p.setPrivilegeName("特权" + needLevel);
        p.setIconName("icon.png");
        p.setPosterName("poster.png");
        p.setDescJson(descJson);
        p.setWebJumpUrl("/web");
        p.setAppJumpUrl("/app");
        return p;
    }

    // ---------- getLevelPrivileges ----------
    private Map<String, Object> privilegesWithUser(Long loginUserId) {
        when(levelQueryService.getLevelConfigs(1))
                .thenReturn(List.of(config(1, 0, "L1"), config(2, 100, "L2")));
        if (loginUserId != null && loginUserId > 0) {
            ApUserLevel u = new ApUserLevel();
            u.setDailyLevel(2);
            u.setDailyScore(new BigDecimal("150"));
            when(levelQueryService.getUserLevel(loginUserId)).thenReturn(u);
        }
        when(privilegeMapper.selectList(any())).thenReturn(List.of(
                privilege(1, 1, "[{\"desc_title\":\"t1\",\"desc_content\":\"c1\"}]"),
                privilege(2, 2, null)));
        return levelPrivilegeService.getLevelPrivileges(loginUserId);
    }

    @Test
    @DisplayName("getLevelPrivileges 登录用户组装等级规格与已解锁权益")
    void getLevelPrivilegesLoggedIn() {
        Map<String, Object> r = privilegesWithUser(userId);
        assertEquals(2, ((List<?>) r.get("level_spec")).size());
        assertEquals(2, r.get("current_level"));
        assertEquals(new BigDecimal("150"), r.get("current_score"));
        assertEquals(2, ((List<?>) r.get("level_privilege")).size());

        List<?> group0 = (List<?>) ((List<?>) r.get("level_privilege")).get(0);
        Map<?, ?> p1 = (Map<?, ?>) group0.get(0);
        assertEquals(1, p1.get("priv_status")); // need1<=current2 -> 已解锁
        assertEquals(1, ((List<?>) p1.get("desc")).size());
    }

    @Test
    @DisplayName("getLevelPrivileges 未登录 current_level 归零且权益未解锁")
    void getLevelPrivilegesAnonymous() {
        Map<String, Object> r = privilegesWithUser(null);
        assertEquals(0, r.get("current_level"));
        assertEquals(BigDecimal.ZERO, r.get("current_score"));
        Map<?, ?> p1 = (Map<?, ?>) ((List<?>) ((List<?>) r.get("level_privilege")).get(0)).get(0);
        assertEquals(0, p1.get("priv_status")); // need1>current0 -> 未解锁
        verify(levelQueryService, never()).getUserLevel(anyLong()); // 未登录不应查用户等级
    }

    @Test
    @DisplayName("parseDescJson 非法 JSON 时权益 desc 为空但不丢弃该条")
    void getLevelPrivilegesBadDesc() {
        when(levelQueryService.getLevelConfigs(1)).thenReturn(List.of());
        when(privilegeMapper.selectList(any())).thenReturn(List.of(privilege(1, 1, "not-json")));
        Map<String, Object> r = levelPrivilegeService.getLevelPrivileges(null);
        Map<?, ?> p = (Map<?, ?>) ((List<?>) ((List<?>) r.get("level_privilege")).get(0)).get(0);
        assertTrue(((List<?>) p.get("desc")).isEmpty());
    }

    // ---------- getUserInfoPack ----------
    @Test
    @DisplayName("getUserInfoPack 登录用户回填基本信息与成长信息")
    void getUserInfoPackLoggedIn() {
        when(userClient.getBasicInfo(userId)).thenReturn(
                ResponseResult.okResult(Map.of("nickname", "张三", "avatar", "a.png")));
        ApUserLevel u = new ApUserLevel();
        u.setDailyLevel(3);
        u.setDailyScore(new BigDecimal("200"));
        u.setPowerLevel(2);
        u.setPowerValue(99);
        when(levelQueryService.getUserLevel(userId)).thenReturn(u);
        when(levelQueryService.getLevelConfigs(1))
                .thenReturn(List.of(config(3, 200, "三级"), config(4, 400, "四级")));

        Map<String, Object> r = levelPrivilegeService.getUserInfoPack(userId);

        @SuppressWarnings("unchecked")
        Map<String, Object> basic = (Map<String, Object>) r.get("user_basic");
        assertEquals("张三", basic.get("user_name"));
        assertEquals("a.png", basic.get("avatar_large"));

        @SuppressWarnings("unchecked")
        Map<String, Object> growth = (Map<String, Object>) r.get("user_growth_info");
        assertEquals(99, growth.get("jpower"));
        assertEquals(new BigDecimal("200"), growth.get("jscore"));
        assertEquals(2, growth.get("jpower_level"));
        assertEquals(3, growth.get("jscore_level"));
        assertEquals("三级", growth.get("jscore_title"));
        assertEquals(200, growth.get("jscore_this_level_mini_score"));
        assertEquals(400, growth.get("jscore_next_level_score"));
        verify(userClient).getBasicInfo(userId);
    }

    @Test
    @DisplayName("getUserInfoPack 未登录时跳过远程调用并返回空态")
    void getUserInfoPackAnonymous() {
        Map<String, Object> r = levelPrivilegeService.getUserInfoPack(0L);
        @SuppressWarnings("unchecked")
        Map<String, Object> growth = (Map<String, Object>) r.get("user_growth_info");
        assertEquals(0, growth.get("jpower"));
        assertEquals(0, growth.get("jscore_level"));
    }

    @Test
    @DisplayName("getUserInfoPack 用户Feign异常时降级为空基本信息")
    void getUserInfoPackFeignDown() {
        when(userClient.getBasicInfo(userId)).thenThrow(new RuntimeException("down"));
        ApUserLevel u = new ApUserLevel();
        u.setDailyLevel(3);
        u.setDailyScore(new BigDecimal("200"));
        u.setPowerLevel(2);
        u.setPowerValue(99);
        when(levelQueryService.getUserLevel(userId)).thenReturn(u);
        when(levelQueryService.getLevelConfigs(1)).thenReturn(List.of());

        Map<String, Object> r = levelPrivilegeService.getUserInfoPack(userId);
        @SuppressWarnings("unchecked")
        Map<String, Object> basic = (Map<String, Object>) r.get("user_basic");
        assertEquals("", basic.get("user_name"));
    }
}