package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.ops.ApBannerMapper;
import com.zhuri.coding.content.service.admin.impl.AdminBannerServiceImpl;
import com.zhuri.coding.model.admin.dtos.AdminBannerSaveDto;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminBannerVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.ops.pojos.ApBanner;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Banner 管理单测。
 *
 * <p>盯住"接口返回成功、实际却不是那个样"的几类失效：
 * <ol>
 *   <li><b>新建必须落停用态</b>：新建即启用会绕过"启用要有独立理由"的闸口；</li>
 *   <li><b>linkUrl 白名单</b>：javascript: 这类协议要被服务层拒掉，
 *       而不是原样下发到 C 端变成 XSS（大小写变体 JAVASCRIPT: 也在内）；</li>
 *   <li><b>启停走 CAS</b>：条件更新命中 0 行明确报"刷新后重试"，不静默成功；
 *       重复启用/停用要被状态闸口拒绝；</li>
 *   <li><b>删除仅限停用态</b>：启用中的 Banner 还挂在用户眼前；</li>
 *   <li><b>时间窗清空 = 写 null</b>：updateById 会静默跳过 null 字段，
 *       逐列 set 才能把"不限"真正写进库。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Banner 管理（AdminBannerServiceImpl）")
class AdminBannerServiceImplTest {

    private static final long BANNER_ID = 9L;
    private static final String REASON = "运营位日常更新";

    @Mock
    private ApBannerMapper apBannerMapper;

    @Mock
    private AdminAuditRecorder auditRecorder;

    @InjectMocks
    private AdminBannerServiceImpl service;

    @BeforeEach
    void setUp() {
        // Lambda 包装器靠实体元信息解析列名。单测没有 MyBatis 会话，必须先手动初始化
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApBanner.class);
    }

    // ==================== 造数 ====================

    private static ApBanner banner(long id, int status) {
        ApBanner b = new ApBanner();
        b.setId(id);
        b.setTitle("新人引导 Banner");
        b.setImageUrl("https://cdn.zhuri.test/banner.png");
        b.setLinkUrl("/topic/1");
        b.setSortOrder(10);
        b.setStatus(status);
        b.setCreatedTime(new Date());
        b.setUpdatedTime(new Date());
        return b;
    }

    private static AdminBannerSaveDto saveDto() {
        AdminBannerSaveDto dto = new AdminBannerSaveDto();
        dto.setReason(REASON);
        dto.setTitle("新人引导 Banner");
        dto.setImageUrl("https://cdn.zhuri.test/banner.png");
        dto.setLinkUrl("/topic/1");
        dto.setSortOrder(10);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseResult result) {
        return (Map<String, Object>) result.getData();
    }

    private ApAdminAuditLog captureSuccessAudit() {
        ArgumentCaptor<ApAdminAuditLog> captor = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditRecorder).recordSuccess(captor.capture());
        return captor.getValue();
    }

    private static void assertInvalid(ResponseResult result, String expectedFragment) {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode(), result.getMessage());
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage().contains(expectedFragment),
            "错误信息要能直接照着改：" + result.getMessage());
    }

    // ==================== 列表 ====================

    @Nested
    @DisplayName("列表")
    class Listing {
        // 类名不用 Page：会遮蔽 MyBatis-Plus 的 com.baomidou...pagination.Page，
        // 导致嵌套类体内 new Page<>(1, 20) 解析到无参泛型的本类

        @Test
        @DisplayName("非法状态报参数错误而不是返回空列表：空列表会让运营以为'这个状态确实没数据'")
        void rejectsUnknownStatus() {
            assertInvalid(service.page(null, 2, 1, 20), "无法识别的状态");
            verifyNoInteractions(apBannerMapper);
        }

        @Test
        @DisplayName("keyword 模糊 + 状态过滤 + 排序（sort_order ASC, id DESC）；分页参数越界兜底")
        void appliesFiltersSortAndPaging() {
            Page<ApBanner> resultPage = new Page<>(1, 20);
            resultPage.setRecords(List.of(banner(BANNER_ID, ApBanner.STATUS_ENABLED)));
            resultPage.setTotal(1);
            AtomicReference<Page<ApBanner>> pageArg = new AtomicReference<>();
            AtomicReference<Wrapper<ApBanner>> wrapperArg = new AtomicReference<>();
            when(apBannerMapper.selectPage(any(), any())).thenAnswer(inv -> {
                pageArg.set(inv.getArgument(0));
                wrapperArg.set(inv.getArgument(1));
                return resultPage;
            });

            ResponseResult result = service.page("  引导  ", ApBanner.STATUS_ENABLED, 0, 999);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            assertEquals(1, pageArg.get().getCurrent(), "page<=0 兜底 1");
            assertEquals(20, pageArg.get().getSize(), "size 越界兜底默认值，不按调用方的 999 拉数据");
            assertEquals(1L, dataOf(result).get("total"));

            LambdaQueryWrapper<ApBanner> wrapper = (LambdaQueryWrapper<ApBanner>) wrapperArg.get();
            String sql = wrapper.getSqlSegment();
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            assertTrue(sql.contains("title"), "keyword 走备注名模糊：" + sql);
            assertTrue(params.containsValue("%引导%"),
                "keyword 要 trim 后拼进 LIKE（值里不该有残留空格）：" + params);
            assertTrue(params.containsValue(ApBanner.STATUS_ENABLED), "状态过滤值必须下发：" + params);
            assertTrue(sql.contains("sort_order ASC"), "展示顺序升序：" + sql);
            assertTrue(sql.contains("id DESC"), "同序号按 id 兜底，翻页才不抖动：" + sql);

            AdminBannerVO vo = ((List<AdminBannerVO>) dataOf(result).get("list")).get(0);
            assertEquals(BANNER_ID, vo.getId());
            assertEquals("启用", vo.getStatusDesc());
        }
    }

    // ==================== 新建 ====================

    @Nested
    @DisplayName("新建")
    class Create {

        @Test
        @DisplayName("新建落停用态，sortOrder 不传默认 0；审计带动作/对象/理由")
        void createLandsDisabledWithAudit() {
            AdminBannerSaveDto dto = saveDto();
            dto.setSortOrder(null);
            when(apBannerMapper.insert(any(ApBanner.class))).thenAnswer(inv -> {
                ApBanner entity = inv.getArgument(0);
                entity.setId(BANNER_ID);
                return 1;
            });
            when(apBannerMapper.selectById(BANNER_ID))
                .thenReturn(banner(BANNER_ID, ApBanner.STATUS_DISABLED));

            ResponseResult result = service.create(dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            ArgumentCaptor<ApBanner> captor = ArgumentCaptor.forClass(ApBanner.class);
            verify(apBannerMapper).insert(captor.capture());
            ApBanner inserted = captor.getValue();
            assertEquals(ApBanner.STATUS_DISABLED, inserted.getStatus(),
                "新建一律落停用：启用是另一个动作、另一条审计（'什么时候挂出去'要有独立理由）");
            assertEquals("新人引导 Banner", inserted.getTitle());
            assertEquals(0, inserted.getSortOrder(), "sortOrder 不传按 0");
            assertNotNull(inserted.getCreatedTime());

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(ApAdminAuditLog.MODULE_OPS, audit.getModule());
            assertEquals(AdminBannerService.ACTION_CREATE, audit.getAction());
            assertEquals(AdminBannerService.TARGET_BANNER, audit.getTargetType());
            assertEquals(String.valueOf(BANNER_ID), audit.getTargetId());
            assertEquals(REASON, audit.getReason());
        }

        @Test
        @DisplayName("linkUrl 协议白名单：javascript:（含大写变体）、data: 被拒；站内路由与 http(s) 放行")
        void rejectsDisallowedLinkProtocols() {
            AdminBannerSaveDto dto = saveDto();

            dto.setLinkUrl("javascript:alert(1)");
            assertInvalid(service.create(dto), "跳转地址只支持");

            dto.setLinkUrl("JAVASCRIPT:alert(1)");
            assertInvalid(service.create(dto), "跳转地址只支持");

            dto.setLinkUrl("data:text/html;base64,AAAA");
            assertInvalid(service.create(dto), "跳转地址只支持");

            dto.setLinkUrl("ftp://cdn.example.com/a.png");
            assertInvalid(service.create(dto), "跳转地址只支持");

            verify(apBannerMapper, never()).insert(any(ApBanner.class));
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("sortOrder 越界拒绝（0-999），时间窗先后与 STRICT 格式校验生效")
        void validatesFieldsAndWindow() {
            AdminBannerSaveDto dto = saveDto();

            dto.setSortOrder(-1);
            assertInvalid(service.create(dto), "0-999");
            dto.setSortOrder(1000);
            assertInvalid(service.create(dto), "0-999");

            dto.setSortOrder(10);
            dto.setStartTime("2026-10-10 00:00:00");
            dto.setEndTime("2026-10-09 00:00:00");
            assertInvalid(service.create(dto), "不得早于开始");

            // STRICT 解析：2 月 30 日必须被拒，而不是顺延成 3 月 2 日悄悄通过
            dto.setStartTime("2026-02-30 00:00:00");
            dto.setEndTime(null);
            assertInvalid(service.create(dto), "格式应为");

            dto.setStartTime("2026-2-9 8:5:3");
            dto.setEndTime("2026-10-10 00:00:00");
            when(apBannerMapper.insert(any(ApBanner.class))).thenAnswer(inv -> {
                ApBanner entity = inv.getArgument(0);
                entity.setId(BANNER_ID);
                return 1;
            });
            when(apBannerMapper.selectById(BANNER_ID))
                .thenReturn(banner(BANNER_ID, ApBanner.STATUS_DISABLED));
            ResponseResult accepted = service.create(dto);
            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), accepted.getCode(),
                "非补零格式（M-d H:m:s）应放行，前端传什么格式是前端的事");
            ArgumentCaptor<ApBanner> captor = ArgumentCaptor.forClass(ApBanner.class);
            verify(apBannerMapper).insert(captor.capture());
            assertNotNull(captor.getValue().getStartTime(), "非补零时刻要真的解析成时间写库，不能当 null 处理");
            assertNotNull(captor.getValue().getEndTime());
        }

        @Test
        @DisplayName("理由缺失 / 超长 → 参数错误，不碰库、不留审计")
        void rejectsInvalidReason() {
            AdminBannerSaveDto dto = saveDto();
            dto.setReason("   ");
            assertInvalid(service.create(dto), "操作理由");
            dto.setReason("水".repeat(501));
            assertInvalid(service.create(dto), "500");
            verifyNoInteractions(apBannerMapper, auditRecorder);
        }
    }

    // ==================== 编辑 ====================

    @Nested
    @DisplayName("编辑")
    class Update {

        @Test
        @DisplayName("字段与当前一致 → 明确报'无需保存'，不更新、不留审计（静默成功会让运营以为改完了）")
        void rejectsNoChange() {
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(banner(BANNER_ID, ApBanner.STATUS_DISABLED));

            ResponseResult result = service.update(BANNER_ID, saveDto());

            assertInvalid(result, "无需保存");
            verify(apBannerMapper, never()).update(any(), any());
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("时间窗清空 = 逐列 set 写 null；WHERE 锚定 id（先渲染 sqlSegment 再断言参数）")
        @SuppressWarnings("unchecked")
        void writesNullForClearedWindow() {
            ApBanner before = banner(BANNER_ID, ApBanner.STATUS_DISABLED);
            before.setStartTime(new Date());
            before.setEndTime(new Date());
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(before);
            when(apBannerMapper.update(isNull(), any())).thenReturn(1);

            AdminBannerSaveDto dto = saveDto(); // 不带时间窗 → 清空
            ResponseResult result = service.update(BANNER_ID, dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            ArgumentCaptor<Wrapper<ApBanner>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(apBannerMapper).update(isNull(), captor.capture());
            LambdaUpdateWrapper<ApBanner> wrapper = (LambdaUpdateWrapper<ApBanner>) captor.getValue();
            // ⚠️ update wrapper 的 eq 参数注册是惰性的：必须先渲染 getSqlSegment()，
            // paramNameValuePairs 里才会出现 WHERE 参数 —— 顺序反了会假失败
            String where = wrapper.getSqlSegment();
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            String sqlSet = wrapper.getSqlSet();
            assertTrue(sqlSet.contains("start_time"), "时间窗清空也要出现在 SET 里：" + sqlSet);
            assertTrue(sqlSet.contains("end_time"), "时间窗清空也要出现在 SET 里：" + sqlSet);
            assertTrue(params.containsValue(null),
                "清空就是要写 null：updateById 会静默跳过 null 字段，必须逐列 set：" + params);
            assertTrue(where.contains("id ="), "WHERE 必须锚定到这条记录：" + where);

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(AdminBannerService.ACTION_UPDATE, audit.getAction());
            assertTrue(audit.getDetail().contains("时间窗"), "变更摘要要写清动了什么：" + audit.getDetail());
        }
    }

    // ==================== 启停 ====================

    @Nested
    @DisplayName("启停")
    class Toggle {

        @Test
        @DisplayName("启用：CAS（WHERE status=旧值）+ 成功审计带状态流转")
        @SuppressWarnings("unchecked")
        void enableUsesCasGuard() {
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(banner(BANNER_ID, ApBanner.STATUS_DISABLED));
            when(apBannerMapper.update(isNull(), any())).thenReturn(1);

            ResponseResult result = service.enable(BANNER_ID, "  " + REASON + "  ");

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            ArgumentCaptor<Wrapper<ApBanner>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(apBannerMapper).update(isNull(), captor.capture());
            LambdaUpdateWrapper<ApBanner> wrapper = (LambdaUpdateWrapper<ApBanner>) captor.getValue();
            // 先渲染 sqlSegment，WHERE 的 eq 参数才会注册进 paramNameValuePairs（惰性求值）
            String where = wrapper.getSqlSegment();
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            assertTrue(params.containsValue(ApBanner.STATUS_ENABLED), "目标状态=启用：" + params);
            assertTrue(params.containsValue(ApBanner.STATUS_DISABLED),
                "WHERE 必须带旧状态比对，挡住并发启停：" + where + " / " + params);
            assertTrue(where.contains("id ="), "WHERE 必须锚定到这条记录：" + where);

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(AdminBannerService.ACTION_ENABLE, audit.getAction());
            assertEquals(REASON, audit.getReason(), "理由 trim 后落审计");
            assertTrue(audit.getDetail().contains("停用 -> 启用"), audit.getDetail());
        }

        @Test
        @DisplayName("启用已过期的 Banner：动作本身放行（启停表达运营意图），但审计要带提醒")
        void enableExpiredBannerWarnsInAudit() {
            ApBanner expired = banner(BANNER_ID, ApBanner.STATUS_DISABLED);
            expired.setEndTime(new Date(System.currentTimeMillis() - 60_000L));
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(expired);
            when(apBannerMapper.update(isNull(), any())).thenReturn(1);

            ResponseResult result = service.enable(BANNER_ID, REASON);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode(),
                "时间窗过没过是展示层的事，拦它反而制造'明明合法却报错'的困惑");
            assertTrue(captureSuccessAudit().getDetail().contains("结束时间已过"),
                "审计里要提醒运营：这样启用 C 端看不到");
        }

        @Test
        @DisplayName("重复启用 → 明确报'无需启用'，不静默成功；停用同理")
        void rejectsRedundantToggle() {
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(banner(BANNER_ID, ApBanner.STATUS_ENABLED));

            assertInvalid(service.enable(BANNER_ID, REASON), "无需启用");

            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(banner(BANNER_ID, ApBanner.STATUS_DISABLED));
            assertInvalid(service.disable(BANNER_ID, REASON), "无需停用");

            verify(apBannerMapper, never()).update(any(), any());
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("条件更新命中 0 行（并发启停）→ 报'请刷新后重试'，不留成功审计")
        void concurrentChangeRejected() {
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(banner(BANNER_ID, ApBanner.STATUS_DISABLED));
            when(apBannerMapper.update(isNull(), any())).thenReturn(0);

            ResponseResult result = service.enable(BANNER_ID, REASON);

            assertInvalid(result, "刷新后重试");
            verify(auditRecorder, never()).recordSuccess(any());
        }
    }

    // ==================== 删除 ====================

    @Nested
    @DisplayName("删除")
    class Delete {

        @Test
        @DisplayName("启用中的 Banner 不能删除：删除它等于从用户眼前抹去，请先停用")
        void rejectsEnabledBanner() {
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(banner(BANNER_ID, ApBanner.STATUS_ENABLED));

            ResponseResult result = service.delete(BANNER_ID, REASON);

            assertInvalid(result, "请先停用");
            verify(apBannerMapper, never()).delete(any());
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("停用态删除：条件删除带状态比对（CAS），成功审计带动作/对象")
        @SuppressWarnings("unchecked")
        void deletesDisabledBannerWithStatusGuard() {
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(banner(BANNER_ID, ApBanner.STATUS_DISABLED));
            when(apBannerMapper.delete(any(Wrapper.class))).thenReturn(1);

            ResponseResult result = service.delete(BANNER_ID, REASON);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            ArgumentCaptor<Wrapper<ApBanner>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(apBannerMapper).delete(captor.capture());
            LambdaQueryWrapper<ApBanner> wrapper = (LambdaQueryWrapper<ApBanner>) captor.getValue();
            // 先渲染 sqlSegment，WHERE 的 eq 参数才会注册进 paramNameValuePairs（惰性求值）
            String where = wrapper.getSqlSegment();
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            assertTrue(params.containsValue(ApBanner.STATUS_DISABLED),
                "删除必须只对停用态生效（CAS 挡并发）：删除/启停并发时输家要报错而不是多删：" + params);
            assertTrue(where.contains("id ="), "WHERE 必须锚定到这条记录：" + where);

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(AdminBannerService.ACTION_DELETE, audit.getAction());
            assertEquals(String.valueOf(BANNER_ID), audit.getTargetId());
        }

        @Test
        @DisplayName("条件删除命中 0 行（并发启停）→ 报'请刷新后重试'")
        void concurrentChangeRejected() {
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(banner(BANNER_ID, ApBanner.STATUS_DISABLED));
            when(apBannerMapper.delete(any(Wrapper.class))).thenReturn(0);

            ResponseResult result = service.delete(BANNER_ID, REASON);

            assertInvalid(result, "刷新后重试");
            verify(auditRecorder, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("Banner 不存在 → 数据不存在错误，不更新、不留审计")
        void missingBannerRejected() {
            when(apBannerMapper.selectById(BANNER_ID)).thenReturn(null);

            ResponseResult result = service.delete(BANNER_ID, REASON);

            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
            verify(apBannerMapper, never()).delete(any());
            verifyNoInteractions(auditRecorder);
        }
    }
}
