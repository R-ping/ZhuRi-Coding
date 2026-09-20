package com.zhuri.coding.reward.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.reward.entity.UserAssets;
import com.zhuri.coding.reward.mapper.UserAssetsMapper;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserAssetsController 回归测试
 *
 * 核心安全诉求：资产/加矿接口【仅限服务内部调用】，外部用户(携带 accToken)禁止访问，
 * 防止伪造 path userId 越权读取他人资产或任意给用户加矿造成资损。
 * 通过 mock AppThreadLocalUtil.getUser() 区分"外部用户调用"(非空) 与 "Feign 内部直连"(为 null)。
 */
class UserAssetsControllerTest {

    @Mock
    private UserAssetsMapper userAssetsMapper;

    @InjectMocks
    private UserAssetsController userAssetsController;

    private MockedStatic<AppThreadLocalUtil> threadLocalMock;

    private final ApUser externalUser = new ApUser();
    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        externalUser.setId(100);
        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
    }

    @AfterEach
    void tearDown() {
        threadLocalMock.close();
    }

    // ==================== getUserAssets ====================

    @Test
    @DisplayName("获取资产 - 外部用户调用返回403(仅限内部)")
    void testGetUserAssetsExternalForbidden() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(externalUser);

        ResponseResult result = userAssetsController.getUserAssets(userId);

        assertEquals(403, result.getCode());
        verify(userAssetsMapper, never()).selectById(userId);
    }

    @Test
    @DisplayName("获取资产 - 内部调用且资产存在返回余额")
    void testGetUserAssetsInternalWithAssets() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);
        UserAssets assets = new UserAssets();
        assets.setOreBalance(150);
        assets.setFrozenOre(20);
        assets.setLuckyValue(10);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);

        ResponseResult result = userAssetsController.getUserAssets(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(150, data.get("oreBalance"));
        assertEquals(20, data.get("frozenOre"));
        assertEquals(10, data.get("luckyValue"));
    }

    @Test
    @DisplayName("获取资产 - 内部调用且资产不存在返回0(default，不出现null)")
    void testGetUserAssetsInternalNoAssetsReturnsZero() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);
        when(userAssetsMapper.selectById(userId)).thenReturn(null);

        ResponseResult result = userAssetsController.getUserAssets(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, data.get("oreBalance"));
        assertEquals(0, data.get("frozenOre"));
        assertEquals(0, data.get("luckyValue"));
    }

    // ==================== getUserOreBalance ====================

    @Test
    @DisplayName("获取矿石余额 - 外部用户调用返回403(仅限内部)")
    void testGetUserOreBalanceExternalForbidden() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(externalUser);

        ResponseResult result = userAssetsController.getUserOreBalance(userId);

        assertEquals(403, result.getCode());
        verify(userAssetsMapper, never()).selectById(userId);
    }

    @Test
    @DisplayName("获取矿石余额 - 内部调用返回余额")
    void testGetUserOreBalanceInternal() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);
        UserAssets assets = new UserAssets();
        assets.setOreBalance(200);
        when(userAssetsMapper.selectById(userId)).thenReturn(assets);

        ResponseResult result = userAssetsController.getUserOreBalance(userId);

        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(200, data.get("oreBalance"));
    }

    // ==================== addOreBalance ====================

    @Test
    @DisplayName("增加矿石 - 外部用户调用返回403(防资损)")
    void testAddOreBalanceExternalForbidden() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(externalUser);

        ResponseResult result = userAssetsController.addOreBalance(userId, 100);

        assertEquals(403, result.getCode());
        verify(userAssetsMapper, never()).addOreBalance(userId, 100);
    }

    @Test
    @DisplayName("增加矿石 - 数量非正数返回400")
    void testAddOreBalanceInvalidAmount() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        ResponseResult result = userAssetsController.addOreBalance(userId, 0);

        assertEquals(400, result.getCode());
        verify(userAssetsMapper, never()).addOreBalance(userId, 0);
    }

    @Test
    @DisplayName("增加矿石 - 内部调用成功增加并返回新余额")
    void testAddOreBalanceInternalSuccess() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);
        // 加矿后余矿
        UserAssets afterAdd = new UserAssets();
        afterAdd.setOreBalance(150);
        when(userAssetsMapper.selectById(userId)).thenReturn(afterAdd);

        ResponseResult result = userAssetsController.addOreBalance(userId, 50);

        assertEquals(200, result.getCode());
        verify(userAssetsMapper).addOreBalance(userId, 50);
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(150, data.get("oreBalance"));
        assertEquals(50, data.get("added"));
    }
}