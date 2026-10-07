package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.ops.ApPopupMapper;
import com.zhuri.coding.content.service.admin.impl.AdminPopupServiceImpl;
import com.zhuri.coding.content.service.ops.PopupCloseStore;
import com.zhuri.coding.model.admin.dtos.AdminPopupSaveDto;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.ops.pojos.ApPopup;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
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
 * 弹窗管理单测。
 *
 * <p>与 {@code AdminBannerServiceImplTest} 共用一套 CRUD 骨架的断言思路，
 * 这里只盯弹窗特有的三件事：
 * <ol>
 *   <li><b>时间窗必填</b>：投放语义必须有截止（关闭记录的 Redis TTL 上限就取结束时间），
 *       缺窗口的弹窗不该被建出来；</li>
 *   <li><b>按钮文案与跳转成对</b>：只文案没跳转 = 点了没反应的按钮，
 *       只跳转没文案 = 渲染不出按钮，两个半吊子形态都要在入口拦下；</li>
 *   <li><b>编辑结束时间刷关闭记录 TTL、删除清关闭记录</b>：
 *       Redis 里那份"已关闭"集合的生命周期必须跟着弹窗走，否则用户会被重复弹。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("弹窗管理（AdminPopupServiceImpl）")
class AdminPopupServiceImplTest {

    private static final long POPUP_ID = 11L;
    private static final String REASON = "双旦活动公告";

    @Mock
    private ApPopupMapper apPopupMapper;

    @Mock
    private AdminAuditRecorder auditRecorder;

    @Mock
    private PopupCloseStore popupCloseStore;

    @InjectMocks
    private AdminPopupServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApPopup.class);
    }

    // ==================== 造数 ====================

    private static Date parse(String text) {
        LocalDateTime dt = LocalDateTime.parse(text,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return Date.from(dt.atZone(ZoneId.systemDefault()).toInstant());
    }

    private static ApPopup popup(long id, int status) {
        ApPopup p = new ApPopup();
        p.setId(id);
        p.setTitle("双旦活动公告");
        p.setContent("活动期间创作激励翻倍");
        p.setButtonText("去看看");
        p.setLinkUrl("/activity/1");
        p.setStatus(status);
        p.setStartTime(parse("2026-12-20 00:00:00"));
        p.setEndTime(parse("2027-01-05 23:59:59"));
        return p;
    }

    /** 与 popup() 内容一致、仅可再调时间窗的合法入参 */
    private static AdminPopupSaveDto saveDto() {
        AdminPopupSaveDto dto = new AdminPopupSaveDto();
        dto.setReason(REASON);
        dto.setTitle("双旦活动公告");
        dto.setContent("活动期间创作激励翻倍");
        dto.setButtonText("去看看");
        dto.setLinkUrl("/activity/1");
        dto.setStartTime("2026-12-20 00:00:00");
        dto.setEndTime("2027-01-05 23:59:59");
        return dto;
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

    // ==================== 新建 ====================

    @Nested
    @DisplayName("新建")
    class Create {

        @Test
        @DisplayName("时间窗必填：开始/结束缺失或格式非法都要被拒（Banner 可空，弹窗不行）")
        void requiresTimeWindow() {
            AdminPopupSaveDto dto = saveDto();

            dto.setStartTime(null);
            assertInvalid(service.create(dto), "生效开始必填");

            dto.setStartTime("2026-12-20 00:00:00");
            dto.setEndTime("not-a-date");
            assertInvalid(service.create(dto), "生效结束必填");

            dto.setEndTime("2027-01-05 23:59:59");
            dto.setStartTime("2027-12-31 00:00:00");
            assertInvalid(service.create(dto), "不得早于开始");

            verifyNoInteractions(apPopupMapper, auditRecorder);
        }

        @Test
        @DisplayName("按钮文案与跳转必须成对：只文案 / 只跳转都拒绝，都不填才合法")
        void requiresPairedButton() {
            AdminPopupSaveDto dto = saveDto();

            dto.setButtonText("去看看");
            dto.setLinkUrl(null);
            assertInvalid(service.create(dto), "同时填写");

            dto.setButtonText(null);
            dto.setLinkUrl("/activity/1");
            assertInvalid(service.create(dto), "同时填写");

            // 都不填 = 只有"知道了"关闭按钮，是合法形态
            when(apPopupMapper.insert(any(ApPopup.class))).thenAnswer(inv -> {
                ApPopup entity = inv.getArgument(0);
                entity.setId(POPUP_ID);
                return 1;
            });
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(popup(POPUP_ID, ApPopup.STATUS_DISABLED));
            dto.setLinkUrl(null);
            dto.setButtonText(null);
            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), service.create(dto).getCode());
        }

        @Test
        @DisplayName("新建落停用态；审计带 POPUP_CREATE / POPUP 对象与理由")
        void createLandsDisabledWithAudit() {
            AdminPopupSaveDto dto = saveDto();
            when(apPopupMapper.insert(any(ApPopup.class))).thenAnswer(inv -> {
                ApPopup entity = inv.getArgument(0);
                entity.setId(POPUP_ID);
                return 1;
            });
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(popup(POPUP_ID, ApPopup.STATUS_DISABLED));

            ResponseResult result = service.create(dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            ArgumentCaptor<ApPopup> captor = ArgumentCaptor.forClass(ApPopup.class);
            verify(apPopupMapper).insert(captor.capture());
            ApPopup inserted = captor.getValue();
            assertEquals(ApPopup.STATUS_DISABLED, inserted.getStatus(), "新建一律落停用：启用是独立动作");
            assertEquals(parse("2026-12-20 00:00:00"), inserted.getStartTime(), "入参字符串要显式解析成时刻");
            assertEquals(parse("2027-01-05 23:59:59"), inserted.getEndTime());

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(ApAdminAuditLog.MODULE_OPS, audit.getModule());
            assertEquals(AdminPopupService.ACTION_CREATE, audit.getAction());
            assertEquals(AdminPopupService.TARGET_POPUP, audit.getTargetType());
            assertEquals(String.valueOf(POPUP_ID), audit.getTargetId());
            assertEquals(REASON, audit.getReason());
        }

        @Test
        @DisplayName("正文超长拒绝（2000）；标题缺失拒绝")
        void validatesContentLimits() {
            AdminPopupSaveDto dto = saveDto();
            dto.setContent("长".repeat(2001));
            assertInvalid(service.create(dto), "正文不能超过");

            dto.setContent(null);
            dto.setTitle(null);
            assertInvalid(service.create(dto), "弹窗标题");

            verifyNoInteractions(apPopupMapper, auditRecorder);
        }
    }

    // ==================== 编辑 ====================

    @Nested
    @DisplayName("编辑")
    class Update {

        @Test
        @DisplayName("结束时间变化 → 关闭记录 TTL 同步刷新（按新结束时间）")
        void refreshesCloseStoreTtlOnEndChange() {
            ApPopup before = popup(POPUP_ID, ApPopup.STATUS_DISABLED);
            before.setEndTime(parse("2027-01-01 00:00:00"));
            AtomicReference<Date> ttlArg = new AtomicReference<>();
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(before);
            when(apPopupMapper.update(isNull(), any())).thenReturn(1);
            org.mockito.Mockito.doAnswer(inv -> {
                ttlArg.set(inv.getArgument(1));
                return null;
            }).when(popupCloseStore).refreshTtl(eq(POPUP_ID), any(Date.class));

            AdminPopupSaveDto dto = saveDto(); // endTime=2027-01-05，与 before 不同
            ResponseResult result = service.update(POPUP_ID, dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            assertEquals(parse("2027-01-05 23:59:59"), ttlArg.get(),
                "TTL 上限必须按新结束时间算，否则延后结束的弹窗会在投放期内被重复弹出");
        }

        @Test
        @DisplayName("只改非时间字段、结束时间没变 → 不刷 TTL（无谓的 Redis 往返也是噪音）")
        void skipsTtlRefreshWhenEndUnchanged() {
            ApPopup before = popup(POPUP_ID, ApPopup.STATUS_DISABLED); // 与 saveDto() 时间窗一致
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(before);
            when(apPopupMapper.update(isNull(), any())).thenReturn(1);

            AdminPopupSaveDto dto = saveDto();
            dto.setTitle("跨年加更公告"); // 必须有真实变化才能过幂等闸口， endTime 保持不变
            ResponseResult result = service.update(POPUP_ID, dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            verify(popupCloseStore, never()).refreshTtl(any(), any());
        }

        @Test
        @DisplayName("字段与当前一致 → 明确报'无需保存'，不更新、不刷 TTL、不留审计")
        void rejectsNoChange() {
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(popup(POPUP_ID, ApPopup.STATUS_DISABLED));

            ResponseResult result = service.update(POPUP_ID, saveDto());

            assertInvalid(result, "无需保存");
            verify(apPopupMapper, never()).update(any(), any());
            verifyNoInteractions(popupCloseStore, auditRecorder);
        }
    }

    // ==================== 启停 / 删除 ====================

    @Nested
    @DisplayName("启停 / 删除")
    class ToggleAndDelete {

        @Test
        @DisplayName("重复启用 → 明确报'无需启用'，不静默成功")
        void rejectsRedundantEnable() {
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(popup(POPUP_ID, ApPopup.STATUS_ENABLED));

            assertInvalid(service.enable(POPUP_ID, REASON), "无需启用");

            verify(apPopupMapper, never()).update(any(), any());
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("条件更新命中 0 行（并发启停）→ 报'请刷新后重试'，不留成功审计")
        void concurrentChangeRejected() {
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(popup(POPUP_ID, ApPopup.STATUS_DISABLED));
            when(apPopupMapper.update(isNull(), any())).thenReturn(0);

            ResponseResult result = service.enable(POPUP_ID, REASON);

            assertInvalid(result, "刷新后重试");
            verify(auditRecorder, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("停用态删除成功：purge 关闭记录（兜底清无 TTL 孤儿 key）+ 成功审计")
        void deletesDisabledPopupAndPurgesCloseStore() {
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(popup(POPUP_ID, ApPopup.STATUS_DISABLED));
            when(apPopupMapper.delete(any())).thenReturn(1);

            ResponseResult result = service.delete(POPUP_ID, REASON);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            verify(popupCloseStore).purge(POPUP_ID);

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(AdminPopupService.ACTION_DELETE, audit.getAction());
            assertEquals(AdminPopupService.TARGET_POPUP, audit.getTargetType());
            assertEquals(String.valueOf(POPUP_ID), audit.getTargetId());
        }

        @Test
        @DisplayName("启用中的弹窗不能删除")
        void rejectsEnabledPopup() {
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(popup(POPUP_ID, ApPopup.STATUS_ENABLED));

            assertInvalid(service.delete(POPUP_ID, REASON), "请先停用");

            verify(apPopupMapper, never()).delete(any());
            verifyNoInteractions(popupCloseStore, auditRecorder);
        }

        @Test
        @DisplayName("条件删除命中 0 行（并发启停）→ 报'请刷新后重试'，绝不能误清别人的关闭记录")
        void concurrentDeleteRejected() {
            when(apPopupMapper.selectById(POPUP_ID)).thenReturn(popup(POPUP_ID, ApPopup.STATUS_DISABLED));
            when(apPopupMapper.delete(any())).thenReturn(0);

            ResponseResult result = service.delete(POPUP_ID, REASON);

            assertInvalid(result, "刷新后重试");
            verify(popupCloseStore, never()).purge(any());
            verify(auditRecorder, never()).recordSuccess(any());
        }
    }
}
