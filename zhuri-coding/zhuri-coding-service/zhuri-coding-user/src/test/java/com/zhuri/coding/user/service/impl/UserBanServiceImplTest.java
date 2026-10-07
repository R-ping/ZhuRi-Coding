package com.zhuri.coding.user.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminUserBanVO;
import com.zhuri.coding.model.admin.vos.AdminUserDispositionRecordVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.user.admin.LocalAdminRoleResolver;
import com.zhuri.coding.user.admin.UserAdminAuditRecorder;
import com.zhuri.coding.user.admin.UserDispositionNotifier;
import com.zhuri.coding.user.mapper.ApAdminAuditLogMapper;
import com.zhuri.coding.user.mapper.ApUserMapper;
import com.zhuri.coding.user.service.UserBanChecker;
import com.zhuri.coding.user.service.UserBanService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 运营侧「用户处置」单测。
 *
 * <p>盯住四件事（都是改错了不会立刻报错的那类）：
 * <ol>
 *   <li><b>解封时四列必须真的被写成 NULL</b>：本项目配了 {@code update-strategy: not_null}，
 *       用 {@code updateById} 会跳过 null 字段 —— 解封会返回"操作成功"而 {@code ban_until} 原封不动，
 *       用户还是登不进来。这条只能靠断言 wrapper 的参数表来钉。</li>
 *   <li><b>解封必须带条件更新</b>：两个运营同时点，只有一个能改到行；另一个该收到错误而不是假成功。</li>
 *   <li><b>警告的通知失败必须整体失败</b>：警告的全部效果就是那条通知，通知没发出去却记了一条
 *       "已警告"，是最坏的一种台账（看起来发生过、其实没人知道）。</li>
 *   <li><b>封禁的通知失败不能回滚封禁</b>：与上一条相反，账号状态才是封禁本身。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营侧用户处置（UserBanServiceImpl）")
class UserBanServiceImplTest {

    private static final Integer OPERATOR_ID = 7;
    private static final Integer TARGET_ID = 1001;
    private static final String REASON = "连续发布违规内容";

    @Mock
    private ApUserMapper userMapper;
    @Mock
    private ApAdminAuditLogMapper auditLogMapper;
    @Mock
    private UserAdminAuditRecorder auditSink;
    @Mock
    private UserDispositionNotifier notifier;
    @Mock
    private LocalAdminRoleResolver adminRoleResolver;

    @InjectMocks
    private UserBanServiceImpl service;

    @BeforeEach
    void setUp() {
        // Lambda 包装器要靠实体元信息解析列名，单测没有 MyBatis 会话，必须先手动初始化
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUser.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApAdminAuditLog.class);
        asOperator(OPERATOR_ID);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    // ==================== 封禁名单 ====================

    @Nested
    @DisplayName("封禁名单")
    class PageList {

        @Test
        @DisplayName("只列仍在封禁中的账号，并批量回填操作人昵称")
        void listsBannedUsers() {
            ApUser banned = target(bannedUntilDaysLater(7));
            banned.setBanTime(new Date());
            banned.setBanOperatorId(9);
            when(userMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(banned)));
            ApUser operator = new ApUser();
            operator.setId(9);
            operator.setNickname("超管甲");
            when(userMapper.selectList(any())).thenReturn(List.of(operator));

            ResponseResult result = service.page(1, 20);

            assertEquals(200, result.getCode().intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(1L, data.get("total"));
            assertNotNull(data.get("serverTime"), "封禁是否到期以服务端时间为准，出参要带上它");

            @SuppressWarnings("unchecked")
            List<AdminUserBanVO> list = (List<AdminUserBanVO>) data.get("list");
            assertEquals(1, list.size());
            assertEquals(TARGET_ID, list.get(0).getUserId());
            assertFalse(list.get(0).isPermanent());
            assertEquals("超管甲", list.get(0).getBanOperatorNickname());

            // "仍在封禁中"必须落在 SQL 上：漏了它名单会把已到期的记录也列出来
            String where = capturedQuerySql();
            assertTrue(where.contains("ban_until"), "必须按 ban_until 过滤仍在封禁中的账号：" + where);
        }

        @Test
        @DisplayName("永久封禁标 permanent=true，不把远期时间这个实现细节透给前端")
        void marksPermanent() {
            ApUser banned = target(permanentUntil());
            when(userMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(banned)));

            @SuppressWarnings("unchecked")
            List<AdminUserBanVO> list = (List<AdminUserBanVO>)
                ((Map<String, Object>) service.page(1, 20).getData()).get("list");

            assertTrue(list.get(0).isPermanent());
            assertNotNull(list.get(0).getBanUntil());
            // 没有操作人时不查昵称（避免一次无意义的批量查询）
            verify(userMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("页大小越界（0 / 过大）回落到默认 20")
        void clampsSize() {
            when(userMapper.selectPage(any(), any())).thenReturn(pageOf(List.of()));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) service.page(0, 999).getData();

            assertEquals(1, data.get("page"));
            assertEquals(20, data.get("size"));
        }

        @Test
        @DisplayName("空名单也要给出 total=0，不能返回 null 让前端崩在 .map 上")
        void handlesEmpty() {
            when(userMapper.selectPage(any(), any())).thenReturn(pageOf(List.of()));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) service.page(1, 20).getData();

            assertTrue(((List<?>) data.get("list")).isEmpty());
            assertEquals(0L, data.get("total"));
        }
    }

    // ==================== 处置记录 ====================

    @Nested
    @DisplayName("处置记录")
    class Records {

        @Test
        @DisplayName("返回台账并附带累计警告次数（只算成功的警告）")
        void returnsRecordsWithWarnCount() {
            when(auditLogMapper.selectPage(any(), any())).thenReturn(auditPageOf(List.of(
                auditRow(1L, UserBanService.ACTION_WARN, ApAdminAuditLog.RESULT_SUCCESS),
                auditRow(2L, UserBanService.ACTION_BAN, ApAdminAuditLog.RESULT_SUCCESS))));
            when(auditLogMapper.selectCount(any())).thenReturn(3L);

            ResponseResult result = service.records(TARGET_ID, 1, 20);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(3, data.get("warnCount"), "运营在这个页面上的核心判断是「警告够了没有、该不该封」");
            @SuppressWarnings("unchecked")
            List<AdminUserDispositionRecordVO> list = (List<AdminUserDispositionRecordVO>) data.get("list");
            assertEquals(2, list.size());
            assertEquals(UserBanService.ACTION_WARN, list.get(0).getAction());
            assertEquals(OPERATOR_ID, list.get(0).getOperatorId());
        }

        @Test
        @DisplayName("缺账号ID → 参数错误，不查库")
        void rejectsNullUserId() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                service.records(null, 1, 20).getCode().intValue());
            verify(auditLogMapper, never()).selectPage(any(), any());
        }
    }

    // ==================== 警告 ====================

    @Nested
    @DisplayName("警告")
    class Warn {

        @Test
        @DisplayName("成功：先投递通知、后落台账，次数为历史成功数 +1")
        void warnSucceeds() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(null)));
            when(auditLogMapper.selectCount(any())).thenReturn(1L);

            ResponseResult result = service.warn(TARGET_ID, REASON);

            assertEquals(200, result.getCode().intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(2, data.get("warnCount"));

            ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
            verify(notifier).notify(eq(String.valueOf(TARGET_ID)),
                eq(UserDispositionNotifier.HANDLE_WARN), msg.capture());
            assertTrue(msg.getValue().contains("第 2 次警告"), msg.getValue());
            assertTrue(msg.getValue().contains(REASON), "理由必须原样告知当事人");

            ApAdminAuditLog audit = capturedSuccessAudit();
            assertEquals(ApAdminAuditLog.MODULE_USER, audit.getModule());
            assertEquals(UserBanService.ACTION_WARN, audit.getAction());
            assertEquals("USER", audit.getTargetType());
            assertEquals(String.valueOf(TARGET_ID), audit.getTargetId());
            assertEquals(REASON, audit.getReason());
            assertEquals("第 2 次警告", audit.getDetail());

            // 顺序钉死：先通知、成功后落台账。反过来的话，通知失败就会留下一条假的"已警告"记录
            InOrder inOrder = inOrder(notifier, auditSink);
            inOrder.verify(notifier).notify(anyString(), anyString(), anyString());
            inOrder.verify(auditSink).recordSuccess(any(ApAdminAuditLog.class));
        }

        @Test
        @DisplayName("通知投递失败 → 整体失败：记失败台账 + 返回错误，绝不记成功台账")
        void warnFailsWhenNotifyFails() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(null)));
            when(auditLogMapper.selectCount(any())).thenReturn(0L);
            doThrow(new RuntimeException("通知服务不可用"))
                .when(notifier).notify(anyString(), anyString(), anyString());

            ResponseResult result = service.warn(TARGET_ID, REASON);

            assertEquals(AppHttpCodeEnum.SERVER_ERROR.getCode(), result.getCode().intValue());
            verify(auditSink, never()).recordSuccess(any());
            ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
            verify(auditSink).recordFailure(any(ApAdminAuditLog.class), err.capture());
            assertTrue(err.getValue().contains("通知服务不可用"), err.getValue());
        }

        @Test
        @DisplayName("正在封禁中的账号 → 拒绝重复警告（否则「警告→封禁」的升级线索会变模糊）")
        void rejectsBannedTarget() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(bannedUntilDaysLater(3))));

            ResponseResult result = service.warn(TARGET_ID, REASON);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("正在封禁中"));
            verify(notifier, never()).notify(anyString(), anyString(), anyString());
            verify(auditSink, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("账号不存在 → 报错，零通知零台账")
        void rejectsMissingTarget() {
            when(userMapper.selectList(any())).thenReturn(List.of());

            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                service.warn(TARGET_ID, REASON).getCode().intValue());
            verify(notifier, never()).notify(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("取不到操作人 → 需要登录，不产生 operator 为空的台账")
        void rejectsWhenOperatorUnknown() {
            AppThreadLocalUtil.clear();

            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(),
                service.warn(TARGET_ID, REASON).getCode().intValue());
            verify(userMapper, never()).selectList(any());
        }
    }

    // ==================== 封禁 ====================

    @Nested
    @DisplayName("封禁")
    class Ban {

        @Test
        @DisplayName("临时封禁：四个字段一次写全，截止时间落在天数之后")
        void bansTemporarily() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(null)));
            when(adminRoleResolver.roleCodesOf(TARGET_ID)).thenReturn(List.of());
            when(userMapper.update(isNull(), any())).thenReturn(1);
            long before = System.currentTimeMillis();

            ResponseResult result = service.ban(TARGET_ID, REASON, 7);

            assertEquals(200, result.getCode().intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertFalse((Boolean) data.get("permanent"));
            Date until = (Date) data.get("banUntil");
            long expectedLow = before + 7L * 86_400_000L - 5_000;
            long expectedHigh = System.currentTimeMillis() + 7L * 86_400_000L + 5_000;
            assertTrue(until.getTime() > expectedLow && until.getTime() < expectedHigh,
                "截止时间应约为 now + 7 天，实际 " + until);

            LambdaUpdateWrapper<ApUser> update = capturedUpdate();
            String set = update.getSqlSet();
            assertNotNull(set);
            for (String column : List.of("ban_until", "ban_reason", "ban_time", "ban_operator_id")) {
                assertTrue(set.contains(column), "封禁四要素要一次写全，缺少 " + column + "：" + set);
            }
            String where = update.getSqlSegment();
            assertTrue(where.contains("id"), "WHERE 必须限定到目标账号：" + where);
            // 封禁刻意不做条件更新（目标是"封到某时刻"，重设本身幂等），
            // 这样"把 7 天延长到 30 天"才不会被闸口挡住

            ApAdminAuditLog audit = capturedSuccessAudit();
            assertEquals(UserBanService.ACTION_BAN, audit.getAction());
            assertTrue(audit.getDetail().contains("nickname=测试用户"), audit.getDetail());
            assertTrue(audit.getDetail().contains("未封禁 ->"),
                "变更摘要要能看出这是首封还是续改：" + audit.getDetail());
        }

        @Test
        @DisplayName("永久封禁：写远期时间 + permanent=true，不需要定时任务去解")
        void bansPermanently() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(null)));
            when(adminRoleResolver.roleCodesOf(TARGET_ID)).thenReturn(List.of());
            when(userMapper.update(isNull(), any())).thenReturn(1);

            ResponseResult result = service.ban(TARGET_ID, REASON, null);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertTrue((Boolean) data.get("permanent"));
            Date until = (Date) data.get("banUntil");
            assertTrue(UserBanChecker.isPermanent(until), "永久封禁要靠远期时间被识别出来");
            assertEquals(9999, LocalDateTime.ofInstant(until.toInstant(), ZoneId.systemDefault()).getYear());
            assertTrue(capturedSuccessAudit().getDetail().contains("(永久)"));
        }

        @Test
        @DisplayName("封禁已有封禁记录的账号 → 允许改期（不是拒绝），台账里留下新旧截止时间")
        void reBanIsAllowed() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(bannedUntilDaysLater(3))));
            when(adminRoleResolver.roleCodesOf(TARGET_ID)).thenReturn(List.of());
            when(userMapper.update(isNull(), any())).thenReturn(1);

            assertEquals(200, service.ban(TARGET_ID, REASON, 30).getCode().intValue());

            String detail = capturedSuccessAudit().getDetail();
            assertTrue(detail.contains(" -> "), "要能看出从哪个时间改到哪个时间：" + detail);
            assertFalse(detail.contains("未封禁 ->"), "这不是首封：" + detail);
        }

        @Test
        @DisplayName("不能封禁自己：自己的账号是唯一能把自己解开的，封了就是死结")
        void refusesSelfBan() {
            ResponseResult result = service.ban(OPERATOR_ID, REASON, 7);

            assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), result.getCode().intValue());
            verify(userMapper, never()).update(any(), any());
            verify(auditSink, never()).recordSuccess(any());
            // 这次拒绝本身就是需要被看到的信号（误点 / 脚本传错 id 都会命中）
            verify(auditSink).recordFailure(any(ApAdminAuditLog.class), anyString());
        }

        @Test
        @DisplayName("账号不存在 → 报错，不写库")
        void refusesMissingTarget() {
            when(userMapper.selectList(any())).thenReturn(List.of());

            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                service.ban(TARGET_ID, REASON, 7).getCode().intValue());
            verify(userMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("通知投递失败仍返回成功（notified=false）：账号状态才是封禁本身，通知只是补充")
        void notifyFailureDoesNotRollbackBan() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(null)));
            when(adminRoleResolver.roleCodesOf(TARGET_ID)).thenReturn(List.of());
            when(userMapper.update(isNull(), any())).thenReturn(1);
            doThrow(new RuntimeException("通知服务不可用"))
                .when(notifier).notify(anyString(), anyString(), anyString());

            ResponseResult result = service.ban(TARGET_ID, REASON, 7);

            assertEquals(200, result.getCode().intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(Boolean.FALSE, data.get("notified"), "投递失败要让运营看得见");
            verify(auditSink).recordSuccess(any(ApAdminAuditLog.class));
        }

        @Test
        @DisplayName("写库抛异常 → 记失败台账后原样抛出，不吞成「封禁成功」")
        void recordsFailureAndRethrows() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(null)));
            RuntimeException boom = new RuntimeException("DB 连接断了");
            when(userMapper.update(isNull(), any())).thenThrow(boom);

            RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.ban(TARGET_ID, REASON, 7));

            assertEquals(boom, thrown);
            verify(auditSink, never()).recordSuccess(any());
            ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
            verify(auditSink).recordFailure(any(ApAdminAuditLog.class), err.capture());
            assertEquals("DB 连接断了", err.getValue());
        }

        @Test
        @DisplayName("目标持有运营角色时在台账里标注 —— 处置这类账号的事后复核分量不同")
        void notesAdminRoleInDetail() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(null)));
            when(adminRoleResolver.roleCodesOf(TARGET_ID)).thenReturn(List.of("OPERATOR"));
            when(userMapper.update(isNull(), any())).thenReturn(1);

            service.ban(TARGET_ID, REASON, 7);

            assertTrue(capturedSuccessAudit().getDetail().contains("targetRoles=OPERATOR"));
        }
    }

    // ==================== 解封 ====================

    @Nested
    @DisplayName("解封")
    class Unban {

        @Test
        @DisplayName("成功：四个封禁列被显式写成 NULL，WHERE 带 ban_until > now 做并发闸口")
        void unbanClearsFields() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(bannedUntilDaysLater(5))));
            when(userMapper.update(isNull(), any())).thenReturn(1);

            ResponseResult result = service.unban(TARGET_ID, REASON);

            assertEquals(200, result.getCode().intValue());

            LambdaUpdateWrapper<ApUser> update = capturedUpdate();
            String set = update.getSqlSet();
            assertNotNull(set);
            for (String column : List.of("ban_until", "ban_reason", "ban_time", "ban_operator_id")) {
                assertTrue(set.contains(column), "解封要把四列都清掉，缺少 " + column + "：" + set);
            }
            // update-strategy=not_null 下最关键的一条：四个 set 的参数值必须都是 null。
            // 用 updateById 传实体的话这些列会被整段跳过，接口返回"成功"而用户依然登不进来。
            assertTrue(update.getParamNameValuePairs().containsValue(null),
                "解封必须显式写入 null，实际参数表：" + update.getParamNameValuePairs());

            String where = update.getSqlSegment();
            assertTrue(where.contains("ban_until"), "解封是单向闸口，必须限定「当前确实处于封禁中」：" + where);

            ApAdminAuditLog audit = capturedSuccessAudit();
            assertEquals(UserBanService.ACTION_UNBAN, audit.getAction());
            assertTrue(audit.getDetail().contains("原封禁"),
                "四列清空后台账是唯一还看得到原封禁信息的地方：" + audit.getDetail());
        }

        @Test
        @DisplayName("当前未被封禁 → 明确报错，不做无意义写入")
        void refusesWhenNotBanned() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(null)));

            ResponseResult result = service.unban(TARGET_ID, REASON);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("未被封禁"));
            verify(userMapper, never()).update(any(), any());
            verify(auditSink, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("并发下已被别人解封（affected=0）→ 报错，不静默成功")
        void refusesWhenLosesRace() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(bannedUntilDaysLater(5))));
            when(userMapper.update(isNull(), any())).thenReturn(0);

            ResponseResult result = service.unban(TARGET_ID, REASON);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("已被其他运营解封"));
            verify(auditSink, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("不能解封自己：被封账号手里可能还有一张未过期的 accToken")
        void refusesSelfUnban() {
            ResponseResult result = service.unban(OPERATOR_ID, REASON);

            assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), result.getCode().intValue());
            verify(userMapper, never()).update(any(), any());
            verify(auditSink).recordFailure(any(ApAdminAuditLog.class), anyString());
        }

        @Test
        @DisplayName("账号不存在 → 报错，不写库")
        void refusesMissingTarget() {
            when(userMapper.selectList(any())).thenReturn(List.of());

            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                service.unban(TARGET_ID, REASON).getCode().intValue());
            verify(userMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("解封通知失败不影响结果（notified=false）")
        void notifyFailureDoesNotRollbackUnban() {
            when(userMapper.selectList(any())).thenReturn(List.of(target(bannedUntilDaysLater(5))));
            when(userMapper.update(isNull(), any())).thenReturn(1);
            doThrow(new RuntimeException("通知服务不可用"))
                .when(notifier).notify(anyString(), anyString(), anyString());

            ResponseResult result = service.unban(TARGET_ID, REASON);

            assertEquals(200, result.getCode().intValue());
            assertEquals(Boolean.FALSE, ((Map<?, ?>) result.getData()).get("notified"));
        }
    }

    // ==================== 工具 ====================

    private void asOperator(Integer operatorId) {
        ApUser operator = new ApUser();
        operator.setId(operatorId);
        operator.setNickname("运营甲");
        AppThreadLocalUtil.setUser(operator);
    }

    private ApUser target(Date banUntil) {
        ApUser user = new ApUser();
        user.setId(TARGET_ID);
        user.setNickname("测试用户");
        user.setBanUntil(banUntil);
        return user;
    }

    private Date bannedUntilDaysLater(int days) {
        return new Date(System.currentTimeMillis() + days * 86_400_000L);
    }

    private Date permanentUntil() {
        return Date.from(LocalDateTime.of(9999, 12, 31, 23, 59, 59)
            .atZone(ZoneId.systemDefault()).toInstant());
    }

    private Page<ApUser> pageOf(List<ApUser> records) {
        Page<ApUser> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    private Page<ApAdminAuditLog> auditPageOf(List<ApAdminAuditLog> records) {
        Page<ApAdminAuditLog> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    private ApAdminAuditLog auditRow(Long id, String action, int result) {
        ApAdminAuditLog row = new ApAdminAuditLog();
        row.setId(id);
        row.setAction(action);
        row.setReason(REASON);
        row.setResult(result);
        row.setUserId(OPERATOR_ID);
        row.setCreatedTime(new Date());
        return row;
    }

    /** 捕获封禁/解封的更新包装器 */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<ApUser> capturedUpdate() {
        ArgumentCaptor<Wrapper<ApUser>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<ApUser>) captor.getValue();
    }

    private ApAdminAuditLog capturedSuccessAudit() {
        ArgumentCaptor<ApAdminAuditLog> captor = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditSink).recordSuccess(captor.capture());
        return captor.getValue();
    }

    /** 捕获名单查询的 WHERE 片段 */
    @SuppressWarnings("unchecked")
    private String capturedQuerySql() {
        ArgumentCaptor<Wrapper<ApUser>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper).selectPage(any(), captor.capture());
        return captor.getValue().getSqlSegment();
    }
}
