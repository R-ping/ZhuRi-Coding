package com.zhuri.coding.user.controller.v1.admin;

import com.zhuri.coding.model.admin.dtos.AdminActionDto;
import com.zhuri.coding.model.admin.dtos.UserBanDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.user.service.UserBanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户处置控制器单测。
 *
 * <p>只测"入参把关"这一层：控制器的职责就是在脏参数进入事务之前把它挡掉，
 * 业务判定在 {@code UserBanServiceImplTest} 里。最要紧的一条是<b>理由必填</b> ——
 * 它是事后追溯的唯一依据，事后补不回来，所以必须在入参这一关就要求写。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户处置控制器（AdminUserDispositionController）")
class AdminUserDispositionControllerTest {

    private static final Integer USER_ID = 1001;
    private static final String REASON = "连续发布违规内容";

    @Mock
    private UserBanService userBanService;

    @InjectMocks
    private AdminUserDispositionController controller;

    private AdminActionDto actionDto(String reason) {
        AdminActionDto dto = new AdminActionDto();
        dto.setReason(reason);
        return dto;
    }

    private UserBanDto banDto(String reason, Integer days) {
        UserBanDto dto = new UserBanDto();
        dto.setReason(reason);
        dto.setDays(days);
        return dto;
    }

    @Nested
    @DisplayName("入参把关")
    class Validation {

        @Test
        @DisplayName("理由为空 / 空白 → 参数错误，且不进入业务层")
        void rejectsBlankReason() {
            for (String reason : new String[]{null, "", "   "}) {
                ResponseResult r = controller.warn(USER_ID, actionDto(reason));
                assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode().intValue());
                assertTrue(String.valueOf(r.getMessage()).contains("处置理由"));
            }
            verify(userBanService, never()).warn(any(), any());
        }

        @Test
        @DisplayName("理由超长（>500，超过审计表列宽）→ 参数错误")
        void rejectsTooLongReason() {
            ResponseResult r = controller.warn(USER_ID, actionDto("x".repeat(501)));

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode().intValue());
            verify(userBanService, never()).warn(any(), any());
        }

        @Test
        @DisplayName("理由刚好 500 字可以过（边界不能卡在合法值上）")
        void acceptsMaxLengthReason() {
            String reason = "x".repeat(500);
            when(userBanService.warn(USER_ID, reason)).thenReturn(ResponseResult.okResult());

            assertEquals(200, controller.warn(USER_ID, actionDto(reason)).getCode().intValue());
        }

        @Test
        @DisplayName("缺账号ID → 参数错误，不进入业务层")
        void rejectsNullUserId() {
            ResponseResult r = controller.unban(null, actionDto(REASON));

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode().intValue());
            verify(userBanService, never()).unban(any(), any());
        }

        @Test
        @DisplayName("请求体缺失 → 参数错误而不是 NPE")
        void rejectsNullBody() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                controller.warn(USER_ID, null).getCode().intValue());
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                controller.ban(USER_ID, null).getCode().intValue());
        }

        @Test
        @DisplayName("封禁天数超过上限 → 参数错误，并提示改用永久封禁")
        void rejectsTooManyDays() {
            ResponseResult r = controller.ban(USER_ID, banDto(REASON, UserBanDto.MAX_DAYS + 1));

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode().intValue());
            assertTrue(String.valueOf(r.getMessage()).contains("永久封禁"), String.valueOf(r.getMessage()));
            verify(userBanService, never()).ban(any(), any(), any());
        }

        @Test
        @DisplayName("天数刚好等于上限可以过")
        void acceptsMaxDays() {
            when(userBanService.ban(USER_ID, REASON, UserBanDto.MAX_DAYS))
                .thenReturn(ResponseResult.okResult());

            assertEquals(200, controller.ban(USER_ID, banDto(REASON, UserBanDto.MAX_DAYS)).getCode().intValue());
        }
    }

    @Nested
    @DisplayName("天数归一化")
    class DaysNormalization {

        @Test
        @DisplayName("不传天数 = 永久：服务端只认 null 一种口径")
        void nullDaysMeansPermanent() {
            when(userBanService.ban(eq(USER_ID), eq(REASON), isNull())).thenReturn(ResponseResult.okResult());

            assertEquals(200, controller.ban(USER_ID, banDto(REASON, null)).getCode().intValue());

            verify(userBanService).ban(USER_ID, REASON, null);
        }

        @Test
        @DisplayName("0 天也归一到永久 —— 否则会被当成「封了 0 天」，语义上等于没封")
        void zeroDaysMeansPermanent() {
            when(userBanService.ban(eq(USER_ID), eq(REASON), isNull())).thenReturn(ResponseResult.okResult());

            assertEquals(200, controller.ban(USER_ID, banDto(REASON, 0)).getCode().intValue());

            verify(userBanService).ban(USER_ID, REASON, null);
        }

        @Test
        @DisplayName("正数天数原样传下去")
        void positiveDaysPassedThrough() {
            when(userBanService.ban(USER_ID, REASON, 7)).thenReturn(ResponseResult.okResult());

            assertEquals(200, controller.ban(USER_ID, banDto(REASON, 7)).getCode().intValue());

            verify(userBanService).ban(USER_ID, REASON, 7);
        }
    }

    @Nested
    @DisplayName("透传")
    class Delegation {

        @Test
        @DisplayName("理由两端空白被清理后再进业务层")
        void trimsReason() {
            when(userBanService.warn(USER_ID, REASON)).thenReturn(ResponseResult.okResult());

            controller.warn(USER_ID, actionDto("  " + REASON + "  "));

            verify(userBanService).warn(USER_ID, REASON);
        }

        @Test
        @DisplayName("名单与处置记录按原样透传分页参数")
        void passesPagingThrough() {
            when(userBanService.page(2, 10)).thenReturn(ResponseResult.okResult());
            when(userBanService.records(USER_ID, 3, 5)).thenReturn(ResponseResult.okResult());

            assertEquals(200, controller.banList(2, 10).getCode().intValue());
            assertEquals(200, controller.records(USER_ID, 3, 5).getCode().intValue());

            verify(userBanService).page(2, 10);
            verify(userBanService).records(USER_ID, 3, 5);
        }

        @Test
        @DisplayName("解封走的是同一个理由校验")
        void unbanValidatesReason() {
            when(userBanService.unban(USER_ID, REASON)).thenReturn(ResponseResult.okResult());

            assertEquals(200, controller.unban(USER_ID, actionDto(REASON)).getCode().intValue());
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                controller.unban(USER_ID, actionDto(" ")).getCode().intValue());

            verify(userBanService).unban(USER_ID, REASON);
        }
    }
}
