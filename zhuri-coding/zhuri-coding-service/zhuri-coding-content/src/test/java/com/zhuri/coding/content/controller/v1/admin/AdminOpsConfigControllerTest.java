package com.zhuri.coding.content.controller.v1.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.service.admin.AdminOpsConfigService;
import com.zhuri.coding.model.admin.dtos.AdminOpsOrderDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 运营位配置控制器单测。
 *
 * <p>只测"请求合不合规矩"这一层：字段齐不齐、理由有没有、长度超没超。
 * "这份清单能不能写进库"（id 合法性、重复、位次上限、存在性）在
 * {@code AdminOpsConfigServiceImplTest} 里 —— 放在服务层是因为它对任何调用方都成立，
 * 不该只在这里拦一道。
 *
 * <p>最要紧的两条：<b>理由必填</b>（运营位决定全站用户第一眼看到什么，事后只能靠这句话追溯）
 * 与 <b>{@code items} 缺失 ≠ 清空</b>（前者是参数错误，后者是合法操作，
 * 混起来就会出现"前端字段名写错 → 静静地把整个运营位清空"）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营位配置控制器（AdminOpsConfigController）")
class AdminOpsConfigControllerTest {

    private static final String REASON = "话题热度变化，调整推荐位";

    @Mock
    private AdminOpsConfigService adminOpsConfigService;

    @InjectMocks
    private AdminOpsConfigController controller;

    private AdminOpsOrderDto orderDto(List<Long> items, String reason) {
        AdminOpsOrderDto dto = new AdminOpsOrderDto();
        dto.setItems(items);
        dto.setReason(reason);
        return dto;
    }

    @Nested
    @DisplayName("入参把关")
    class Validation {

        @Test
        @DisplayName("理由为空 / 空白 / 超长 → 参数错误，且不进入业务层")
        void rejectsBadReason() {
            for (String reason : new String[]{null, "", "   "}) {
                ResponseResult r = controller.saveHotCircles(orderDto(List.of(1L), reason));
                assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode().intValue());
                assertTrue(String.valueOf(r.getMessage()).contains("操作理由"), String.valueOf(r.getMessage()));

                ResponseResult r2 = controller.saveRecommendTopics(orderDto(List.of(1L), reason));
                assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r2.getCode().intValue());
            }
            ResponseResult tooLong = controller.saveHotCircles(
                orderDto(List.of(1L), "理".repeat(501)));
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), tooLong.getCode().intValue());
            assertTrue(String.valueOf(tooLong.getMessage()).contains("500"));

            verify(adminOpsConfigService, never()).saveHotCircles(any(), any());
            verify(adminOpsConfigService, never()).saveRecommendTopics(any(), any());
        }

        @Test
        @DisplayName("items 缺失 → 参数错误（缺失不等于清空，免得字段名写错就把运营位清空）")
        void rejectsMissingItems() {
            ResponseResult r = controller.saveHotCircles(orderDto(null, REASON));

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode().intValue());
            assertTrue(String.valueOf(r.getMessage()).contains("缺少"), String.valueOf(r.getMessage()));
            assertTrue(String.valueOf(r.getMessage()).contains("空数组"), "要告诉调用方怎么表达清空");
            verify(adminOpsConfigService, never()).saveHotCircles(any(), any());
        }

        @Test
        @DisplayName("整个 body 缺失 → 参数错误，不抛 NPE")
        void rejectsNullDto() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                controller.saveHotCircles(null).getCode().intValue());
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                controller.saveRecommendTopics(null).getCode().intValue());
            verify(adminOpsConfigService, never()).saveHotCircles(any(), any());
        }
    }

    @Nested
    @DisplayName("透传")
    class Passthrough {

        @Test
        @DisplayName("合法入参：原样交给服务层，理由先去首尾空白")
        void passesThrough() {
            List<Long> items = List.of(3L, 1L);
            ResponseResult expected = ResponseResult.okResult();
            when(adminOpsConfigService.saveHotCircles(eq(items), eq(REASON))).thenReturn(expected);

            assertSame(expected, controller.saveHotCircles(orderDto(items, "  " + REASON + "  ")));
        }

        @Test
        @DisplayName("空数组是合法的「清空」，要真的走到服务层")
        void emptyListIsClearingNotError() {
            ResponseResult expected = ResponseResult.okResult();
            when(adminOpsConfigService.saveRecommendTopics(eq(new ArrayList<>()), eq(REASON)))
                .thenReturn(expected);

            assertSame(expected, controller.saveRecommendTopics(orderDto(new ArrayList<>(), REASON)));
        }

        @Test
        @DisplayName("查询接口不做入参校验，原样透传（收口在服务层，免得两处各写一套）")
        void readEndpointsPassThrough() {
            ResponseResult expected = ResponseResult.okResult();
            when(adminOpsConfigService.searchCircles(null, null, null)).thenReturn(expected);
            when(adminOpsConfigService.searchTopics("Java", 2, 50)).thenReturn(expected);

            assertSame(expected, controller.circles(null, null, null));
            assertSame(expected, controller.topics("Java", 2, 50));
        }
    }
}
