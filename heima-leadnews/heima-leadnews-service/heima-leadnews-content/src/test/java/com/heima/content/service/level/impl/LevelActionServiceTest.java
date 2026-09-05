package com.heima.content.service.level.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.heima.content.mapper.level.ApBehaviorConfigMapper;
import com.heima.content.mapper.level.ApUserDailyProgressMapper;
import com.heima.content.mapper.level.ApUserLevelMapper;
import com.heima.content.mapper.pins.ApUserActionLogMapper;
import com.heima.content.service.level.LevelPermissionService;
import com.heima.model.level.pojos.ApBehaviorConfig;
import com.heima.model.level.pojos.ApUserDailyProgress;
import com.heima.model.level.pojos.ApUserLevel;
import com.heima.model.user.pojos.ApUserActionLog;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LevelActionService 单元测试（逐日等级 / 积分体系核心）
 *
 * 覆盖焦点：
 * - recordAction：按行为配置加分、无效行为(0分)不做任何写库；
 * - recordActionWithLimit：今日次数/积分双上限拦截、合理加分；
 * - recordPaymentAction：支付金额校验（<=0 拒绝、超每日上限截断）；
 * - checkIn：重复签到拦截、签到加分、今日积分打满拦截；
 * - grantScore 侧翼：升级时发权限与钻石、行为日志落库；
 * - recordPassiveAction：被动行为每日进度 upsert。
 */
class LevelActionServiceTest {

    @Mock
    private ApUserActionLogMapper actionLogMapper;
    @Mock
    private ApUserLevelMapper userLevelMapper;
    @Mock
    private LevelQueryService levelQueryService;
    @Mock
    private LevelPermissionService permissionService;
    @Mock
    private LevelDiamondService diamondService;
    @Mock
    private LevelTaskProgressBuilder taskProgressBuilder;
    @Mock
    private ApBehaviorConfigMapper behaviorConfigMapper;
    @Mock
    private ApUserDailyProgressMapper userDailyProgressMapper;

    @InjectMocks
    private LevelActionService levelActionService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 预热 MybatisPlus 实体表元数据与 lambda 列缓存，使单测自足、不依赖 Spring 上下文
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserActionLog.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApBehaviorConfig.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserDailyProgress.class);
    }

    private ApUserLevel level(int dailyLevel, BigDecimal dailyScore) {
        ApUserLevel ul = new ApUserLevel();
        ul.setUserId(userId);
        ul.setDailyLevel(dailyLevel);
        ul.setDailyScore(dailyScore);
        return ul;
    }

    // ==================== recordAction ====================

    @Test
    @DisplayName("recordAction - 有效行为加分并落库")
    void testRecordActionValid() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(1);

        levelActionService.recordAction(userId, "comment_article", "评论文章");

        // 行为日志 + 用户等级各落库一次，未升级不发权限/钻石
        verify(actionLogMapper).insert((ApUserActionLog) any());
        verify(userLevelMapper).updateById((ApUserLevel) any());
        verify(permissionService, never()).updateUserPermissions(any(), anyInt(), anyInt(), anyInt());
        verify(diamondService, never()).grantDiamondOnLevelUp(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("recordAction - 0分行为(如browse_article)直接忽略不写库")
    void testRecordActionZeroScoreIgnored() {
        levelActionService.recordAction(userId, "browse_article", "浏览");
        verify(actionLogMapper, never()).insert((ApUserActionLog) any());
        verify(userLevelMapper, never()).updateById((ApUserLevel) any());
    }

    @Test
    @DisplayName("recordAction - 未知行为类型忽略不写库")
    void testRecordActionUnknownIgnored() {
        levelActionService.recordAction(userId, "unknown_action", "未知");
        verify(actionLogMapper, never()).insert((ApUserActionLog) any());
    }

    // ==================== recordActionWithLimit ====================

    @Test
    @DisplayName("recordActionWithLimit - 今日次数达上限拒绝")
    void testActionWithLimitReachDailyCount() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(actionLogMapper.selectCount(any())).thenReturn(2L); // publish_article 今日已达上限

        Map<String, Object> result = levelActionService.recordActionWithLimit(userId, "publish_article", "发文章");

        assertFalse((Boolean) result.get("success"));
        assertEquals("今日该行为已达上限", result.get("message"));
        verify(actionLogMapper, never()).insert((ApUserActionLog) any());
    }

    @Test
    @DisplayName("recordActionWithLimit - 无每日总分上限，达到行为次数上限才拒绝")
    void testActionWithLimitReachDailyScore() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(1);
        // 今日已通过其他行为累计 200+ 分（旧规则有"每日总分上限 200"，现规则无总分上限）
        ApUserActionLog log = new ApUserActionLog();
        log.setScoreChange(BigDecimal.valueOf(250));
        when(actionLogMapper.selectList(any())).thenReturn(Collections.singletonList(log));
        when(actionLogMapper.selectCount(any())).thenReturn(0L); // 未达本行为次数上限

        Map<String, Object> result = levelActionService.recordActionWithLimit(userId, "publish_article", "发文章");

        // 新规则：只受单行为每日次数上限约束，无总分上限 → 正常加分
        assertTrue((Boolean) result.get("success"));
        verify(actionLogMapper).insert((ApUserActionLog) any());
    }

    @Test
    @DisplayName("recordActionWithLimit - 正常加分成功")
    void testActionWithLimitSuccess() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(1);
        when(actionLogMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> result = levelActionService.recordActionWithLimit(userId, "publish_article", "发文章");

        assertTrue((Boolean) result.get("success"));
        assertEquals(8, ((BigDecimal) result.get("score")).intValue());
        verify(actionLogMapper).insert((ApUserActionLog) any());
        verify(userLevelMapper).updateById((ApUserLevel) any());
    }

    @Test
    @DisplayName("recordActionWithLimit - 无效行为类型返回失败")
    void testActionWithLimitInvalidType() {
        Map<String, Object> result = levelActionService.recordActionWithLimit(userId, "browse_article", "浏览");
        assertFalse((Boolean) result.get("success"));
        assertEquals("无效的行为类型", result.get("message"));
    }

    @Test
    @DisplayName("recordActionWithLimit - S5修复:先加悲观行锁再校验上限,并以锁后实例落库")
    void testActionWithLimitAcquiresRowLock() {
        ApUserLevel fresh = level(1, BigDecimal.ZERO);
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(1);
        when(userLevelMapper.selectByUserIdForUpdate(userId)).thenReturn(fresh);
        when(actionLogMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> result = levelActionService.recordActionWithLimit(userId, "publish_article", "发文章");

        assertTrue((Boolean) result.get("success"));
        // 锁必须在上限校验之前获取（校验是 selectCount/selectList，均在锁的同一事务内）
        Mockito.inOrder(userLevelMapper, actionLogMapper)
                .verify(userLevelMapper).selectByUserIdForUpdate(userId);
        // 加分落库使用的是锁读取后的行实例，而非校验前缓存的旧实例
        verify(userLevelMapper).updateById(fresh);
    }

    // ==================== recordPaymentAction ====================

    @Test
    @DisplayName("recordPaymentAction - 无效金额(<=0)拒绝")
    void testPaymentInvalidAmount() {
        Map<String, Object> result = levelActionService.recordPaymentAction(
                userId, "purchase_course", BigDecimal.ZERO, "购课");
        assertFalse((Boolean) result.get("success"));
        assertEquals("无效的支付金额", result.get("message"));
    }

    @Test
    @DisplayName("recordPaymentAction - 大额支付不截断（无每日总分上限，金额即经验值）")
    void testPaymentExceedsDailyLimit() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(1);
        // 今日已通过其他行为累计 150 分（旧规则会按 200 上限截断，现规则不截断）
        ApUserActionLog log = new ApUserActionLog();
        log.setScoreChange(BigDecimal.valueOf(150));
        when(actionLogMapper.selectList(any())).thenReturn(Collections.singletonList(log));
        when(actionLogMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> result = levelActionService.recordPaymentAction(
                userId, "purchase_course", BigDecimal.valueOf(1000), "购课");

        // 新规则：支付金额全额入账，不做总分截断
        assertTrue((Boolean) result.get("success"));
        assertEquals(1000, ((BigDecimal) result.get("score")).intValue());
    }

    @Test
    @DisplayName("recordPaymentAction - 正常支付金额即经验值")
    void testPaymentNormal() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(1);
        when(actionLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        Map<String, Object> result = levelActionService.recordPaymentAction(
                userId, "purchase_course", BigDecimal.valueOf(88.5), "购课");

        assertTrue((Boolean) result.get("success"));
        // 金额即经验，支持小数
        assertEquals(0, BigDecimal.valueOf(88.5).compareTo((BigDecimal) result.get("score")));
    }

    // ==================== checkIn ====================

    @Test
    @DisplayName("checkIn - 今日已签到拒绝")
    void testCheckInAlready() {
        when(actionLogMapper.selectCount(any())).thenReturn(1L);
        Map<String, Object> result = levelActionService.checkIn(userId);
        assertFalse((Boolean) result.get("success"));
        assertEquals(Boolean.TRUE, result.get("hasCheckedIn"));
        verify(actionLogMapper, never()).insert((ApUserActionLog) any());
    }

    @Test
    @DisplayName("checkIn - 今日总分很高也可签到（无每日总分上限）")
    void testCheckInScoreFull() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(1);
        when(actionLogMapper.selectCount(any())).thenReturn(0L);
        // 今日已有 200+ 分（旧规则会拦截签到，新规则无总分上限正常签到）
        ApUserActionLog log = new ApUserActionLog();
        log.setScoreChange(BigDecimal.valueOf(200));
        when(actionLogMapper.selectList(any())).thenReturn(Collections.singletonList(log));

        Map<String, Object> result = levelActionService.checkIn(userId);
        assertTrue((Boolean) result.get("success"));
        assertEquals(Boolean.TRUE, result.get("hasCheckedIn"));
        verify(actionLogMapper).insert((ApUserActionLog) any());
    }

    @Test
    @DisplayName("checkIn - 签到成功加2分")
    void testCheckInSuccess() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(1);
        when(actionLogMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> result = levelActionService.checkIn(userId);
        assertTrue((Boolean) result.get("success"));
        assertEquals(2, ((BigDecimal) result.get("score")).intValue());
        verify(actionLogMapper).insert((ApUserActionLog) any());
    }

    @Test
    @DisplayName("checkIn - S5修复:先加行锁再查当日签到次数,杜绝并发重复签到")
    void testCheckInLocksBeforeCount() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(1);
        when(userLevelMapper.selectByUserIdForUpdate(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(actionLogMapper.selectCount(any())).thenReturn(0L);
        when(actionLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        Map<String, Object> result = levelActionService.checkIn(userId);
        assertTrue((Boolean) result.get("success"));

        // 关键时序：行锁获取必须先于当日签到次数查询与落库
        var inOrder = Mockito.inOrder(userLevelMapper, actionLogMapper);
        inOrder.verify(userLevelMapper).selectByUserIdForUpdate(userId);
        inOrder.verify(actionLogMapper).insert(org.mockito.ArgumentMatchers.any(ApUserActionLog.class));
    }

    // ==================== grantScore - 等级提升分支 ====================

    @Test
    @DisplayName("recordAction - 升级时发放权限与钻石")
    void testRecordActionLevelUp() {
        when(levelQueryService.getUserLevel(userId)).thenReturn(level(1, BigDecimal.ZERO));
        when(levelQueryService.calculateLevel(eq(1), any(BigDecimal.class))).thenReturn(2); // 由1级升到2级

        levelActionService.recordAction(userId, "publish_article", "发文章");

        verify(permissionService).updateUserPermissions(eq(userId), eq(1), eq(1), eq(2));
        verify(diamondService).grantDiamondOnLevelUp(eq(userId), eq(1), eq(2));
    }

    // ==================== 委托方法 ====================

    @Test
    @DisplayName("getTodayTaskProgress - 委托构建器")
    void testGetTodayTaskProgress() {
        when(taskProgressBuilder.buildTaskProgress(userId))
                .thenReturn(Collections.singletonMap("done", 1));
        Map<String, Object> result = levelActionService.getTodayTaskProgress(userId);
        assertEquals(1, result.get("done"));
        verify(taskProgressBuilder).buildTaskProgress(userId);
    }

    @Test
    @DisplayName("recordPassiveAction - 无行为日志，仅更新每日进度")
    void testRecordPassiveAction() {
        when(behaviorConfigMapper.selectCount(any())).thenReturn(1L); // 行为在配置表中
        when(userDailyProgressMapper.selectOne(any())).thenReturn(null);

        levelActionService.recordPassiveAction(userId, "be_followed");

        verify(actionLogMapper, never()).insert((ApUserActionLog) any());
        verify(userDailyProgressMapper).insert((ApUserDailyProgress) any());
    }

    @Test
    @DisplayName("recordPassiveAction - 行为不在配置表跳过进度更新")
    void testRecordPassiveActionConfigMissing() {
        when(behaviorConfigMapper.selectCount(any())).thenReturn(0L);

        levelActionService.recordPassiveAction(userId, "be_followed");

        verify(userDailyProgressMapper, never()).insert((ApUserDailyProgress) any());
        verify(userDailyProgressMapper, never()).updateById((ApUserDailyProgress) any());
    }
}