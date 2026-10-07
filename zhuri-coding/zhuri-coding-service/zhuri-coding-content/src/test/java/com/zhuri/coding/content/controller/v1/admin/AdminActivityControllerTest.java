package com.zhuri.coding.content.controller.v1.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.service.admin.AdminActivityService;
import com.zhuri.coding.model.admin.dtos.AdminActionDto;
import com.zhuri.coding.model.admin.dtos.AdminActivitySaveDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 活动管理控制器单测。
 *
 * <p>只测"请求合不合规矩"这一层：字段齐不齐、理由有没有、长度超没超。
 * "这份数据能不能写进库"（类型/分类白名单、日期先后、话题存在性、状态能不能上线）
 * 在 {@code AdminActivityServiceImplTest} 里 —— 放在服务层是因为它对任何调用方都成立，
 * 不该只在这里拦一道。
 *
 * <p>最要紧的一条是<b>理由必填</b>：上线决定"全站用户能不能看到这条活动"，
 * 下线决定"什么时候撤的"。事后追问只能靠这句话回答，而它是事后补不回来的。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("活动管理控制器（AdminActivityController）")
class AdminActivityControllerTest {

    private static final long ACT_ID = 21L;
    private static final String REASON = "季度征文活动，准备上线";

    @Mock
    private AdminActivityService adminActivityService;

    @InjectMocks
    private AdminActivityController controller;

    private AdminActivitySaveDto saveDto(String title, String start, String end, String reason) {
        AdminActivitySaveDto dto = new AdminActivitySaveDto();
        dto.setTitle(title);
        dto.setStartDate(start);
        dto.setEndDate(end);
        dto.setReason(reason);
        return dto;
    }

    @Nested
    @DisplayName("保存类接口的字段把关")
    class SaveValidation {

        @Test
        @DisplayName("标题/开始日期/结束日期缺失 → 参数错误，且不进业务层")
        void rejectsMissingFields() {
            assertInvalid(controller.create(saveDto(null, "2026-10-10", "2026-10-20", REASON)), "标题");
            assertInvalid(controller.create(saveDto("  ", "2026-10-10", "2026-10-20", REASON)), "标题");
            assertInvalid(controller.create(saveDto("征文", null, "2026-10-20", REASON)), "开始日期");
            assertInvalid(controller.create(saveDto("征文", "  ", "2026-10-20", REASON)), "开始日期");
            assertInvalid(controller.create(saveDto("征文", "2026-10-10", null, REASON)), "结束日期");
            assertInvalid(controller.update(ACT_ID, saveDto("征文", "2026-10-10", "", REASON)), "结束日期");

            // 参数为 null 时是"少东西"而不是"填错了"
            ResponseResult nullDto = controller.create(null);
            assertEquals(AppHttpCodeEnum.PARAM_REQUIRE.getCode(), nullDto.getCode().intValue());

            verify(adminActivityService, never()).create(any());
            verify(adminActivityService, never()).update(any(), any());
        }

        @Test
        @DisplayName("理由为空 / 空白 / 超长 → 参数错误（理由事后补不回来，必须在操作当时要求）")
        void rejectsBadReason() {
            for (String reason : new String[]{null, "", "   "}) {
                assertInvalid(controller.create(saveDto("征文", "2026-10-10", "2026-10-20", reason)), "理由");
                assertInvalid(controller.update(ACT_ID,
                    saveDto("征文", "2026-10-10", "2026-10-20", reason)), "理由");
                assertInvalid(controller.publish(ACT_ID, action(reason)), "理由");
                assertInvalid(controller.offline(ACT_ID, action(reason)), "理由");
                assertInvalid(controller.delete(ACT_ID, reason), "理由");
            }
            assertInvalid(controller.publish(ACT_ID, action("理".repeat(501))), "500");
            assertInvalid(controller.delete(ACT_ID, "理".repeat(501)), "500");

            verify(adminActivityService, never()).create(any());
            verify(adminActivityService, never()).publish(any(), any());
            verify(adminActivityService, never()).offline(any(), any());
            verify(adminActivityService, never()).delete(any(), any());
        }

        @Test
        @DisplayName("字段齐全 → 原样下沉到业务层，且理由已去空白")
        void passesThrough() {
            when(adminActivityService.create(any())).thenReturn(ResponseResult.okResult());
            when(adminActivityService.update(any(), any())).thenReturn(ResponseResult.okResult());
            when(adminActivityService.publish(any(), any())).thenReturn(ResponseResult.okResult());
            when(adminActivityService.offline(any(), any())).thenReturn(ResponseResult.okResult());
            when(adminActivityService.delete(any(), any())).thenReturn(ResponseResult.okResult());

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(),
                controller.create(saveDto("征文", "2026-10-10", "2026-10-20", REASON)).getCode());
            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(),
                controller.update(ACT_ID, saveDto("征文", "2026-10-10", "2026-10-20", REASON)).getCode());
            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(),
                controller.publish(ACT_ID, action("  " + REASON + "  ")).getCode());
            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(),
                controller.offline(ACT_ID, action(REASON)).getCode());
            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(),
                controller.delete(ACT_ID, "  " + REASON + "  ").getCode());

            // 去空白结果传给业务层，避免审计里出现前后带空格的同义理由
            verify(adminActivityService).publish(ACT_ID, REASON);
            verify(adminActivityService).delete(ACT_ID, REASON);
            verify(adminActivityService).update(eq(ACT_ID), any());
        }
    }

    @Nested
    @DisplayName("读接口")
    class Read {

        @Test
        @DisplayName("列表把筛选条件原样透传（拼错编码的判断在服务层，这里不做二次解释）")
        void listPassesFilters() {
            when(adminActivityService.page("征文", "draft", "pin", "backend", 2, 10))
                .thenReturn(ResponseResult.okResult());

            ResponseResult result = controller.list("征文", "draft", "pin", "backend", 2, 10);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            verify(adminActivityService).page("征文", "draft", "pin", "backend", 2, 10);
        }

        @Test
        @DisplayName("详情按 id 下沉")
        void detailPassesId() {
            when(adminActivityService.detail(ACT_ID)).thenReturn(ResponseResult.okResult());

            controller.detail(ACT_ID);

            verify(adminActivityService).detail(ACT_ID);
        }
    }

    // ==================== 工具 ====================

    private static AdminActionDto action(String reason) {
        AdminActionDto dto = new AdminActionDto();
        dto.setReason(reason);
        return dto;
    }

    private static void assertInvalid(ResponseResult result, String messagePart) {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue(),
            "期望参数错误，实际：" + result.getCode() + " " + result.getMessage());
        assertTrue(String.valueOf(result.getMessage()).contains(messagePart),
            "提示里应包含「" + messagePart + "」，实际：" + result.getMessage());
    }
}
