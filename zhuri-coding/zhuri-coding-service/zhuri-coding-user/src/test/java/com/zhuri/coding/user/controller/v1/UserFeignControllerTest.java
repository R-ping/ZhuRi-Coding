package com.zhuri.coding.user.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.model.user.pojos.UserProfile;
import com.zhuri.coding.user.mapper.ApUserMapper;
import com.zhuri.coding.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * UserFeignController 单元测试（供其他服务 Feign 调用的用户基本信息接口）
 *
 * 经 @InjectMocks 注入两个 mapper，覆盖参数校验、用户不存在、Map 组装与
 * 用户资料未初始化（profile 为 null / 局部字段为 null）的空值兜底分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserFeignController 用户Feign接口")
class UserFeignControllerTest {

    @Mock
    private ApUserMapper apUserMapper;
    @Mock
    private UserProfileMapper userProfileMapper;

    @InjectMocks
    private UserFeignController userFeignController;

    private ApUser user(Integer id, String nickname, String image) {
        ApUser u = new ApUser();
        u.setId(id);
        u.setNickname(nickname);
        u.setImage(image);
        return u;
    }

    @Nested
    @DisplayName("getBasicInfo 基本信息")
    class BasicInfo {

        @Test
        @DisplayName("userId 为空 → 参数错误(400)")
        void testNullUserId() {
            ResponseResult r = userFeignController.getBasicInfo(null);
            assertEquals(400, r.getCode());
        }

        @Test
        @DisplayName("用户不存在 → 404")
        void testUserNotExist() {
            when(apUserMapper.selectById(9L)).thenReturn(null);
            assertEquals(404, userFeignController.getBasicInfo(9L).getCode());
        }

        @Test
        @DisplayName("成功：昵称/头像为 null 时兜底为空串")
        void testSuccessWithNullFields() {
            when(apUserMapper.selectById(1L)).thenReturn(user(1, null, null));

            ResponseResult r = userFeignController.getBasicInfo(1L);

            Map<String, Object> data = (Map<String, Object>) r.getData();
            assertEquals(1, data.get("userId"));
            assertEquals("", data.get("nickname"));
            assertEquals("", data.get("avatar"));
        }
    }

    @Nested
    @DisplayName("getPublicInfo 公开信息")
    class PublicInfo {

        @Test
        @DisplayName("userId 为空 → 参数错误(400)")
        void testNullUserId() {
            assertEquals(400, userFeignController.getPublicInfo(null).getCode());
        }

        @Test
        @DisplayName("用户不存在 → 404")
        void testUserNotExist() {
            when(apUserMapper.selectById(9L)).thenReturn(null);
            assertEquals(404, userFeignController.getPublicInfo(9L).getCode());
        }

        @Test
        @DisplayName("用户资料未初始化（profile null）→ 职业/公司/简介空串兜底")
        void testProfileNull() {
            when(apUserMapper.selectById(2L)).thenReturn(user(2, "昵称", "avatar.png"));
            when(userProfileMapper.selectById(2L)).thenReturn(null);

            Map<String, Object> data = (Map<String, Object>) userFeignController.getPublicInfo(2L).getData();
            assertEquals("", data.get("position"));
            assertEquals("", data.get("company"));
            assertEquals("", data.get("bio"));
        }

        @Test
        @DisplayName("成功：资料字段完整透传")
        void testSuccess() {
            when(apUserMapper.selectById(3L)).thenReturn(user(3, "nick", "img.jpg"));
            UserProfile p = new UserProfile();
            p.setPosition("工程师");
            p.setCompany("示例公司");
            p.setBio("简介");
            when(userProfileMapper.selectById(3L)).thenReturn(p);

            Map<String, Object> data = (Map<String, Object>) userFeignController.getPublicInfo(3L).getData();
            assertEquals("nick", data.get("nickname"));
            assertEquals("img.jpg", data.get("avatar"));
            assertEquals("工程师", data.get("position"));
            assertEquals("示例公司", data.get("company"));
            assertEquals("简介", data.get("bio"));
        }
    }

    @Nested
    @DisplayName("getBasicInfoBatch 批量基本信息")
    class BasicInfoBatch {

        @Test
        @DisplayName("空入参 / null → 返回空 Map，不查库")
        void testEmpty() {
            assertEquals(200, userFeignController.getBasicInfoBatch(List.of()).getCode());
            assertTrue(((Map<?, ?>) userFeignController.getBasicInfoBatch(List.of()).getData()).isEmpty());
            assertEquals(200, userFeignController.getBasicInfoBatch(null).getCode());
        }

        @Test
        @DisplayName("正常：以 userId 为 key 返回昵称头像")
        void testOk() {
            when(apUserMapper.selectBatchIds(List.of(1L, 2L)))
                    .thenReturn(List.of(user(1, "张三", "a.png"), user(2, "李四", "b.png")));

            Map<String, Object> data =
                    (Map<String, Object>) userFeignController.getBasicInfoBatch(List.of(1L, 2L)).getData();
            assertEquals(2, data.size());
            assertEquals("张三", ((Map<?, ?>) data.get("1")).get("nickname"));
            assertEquals("b.png", ((Map<?, ?>) data.get("2")).get("avatar"));
        }

        @Test
        @DisplayName("查不到的 id 不出现在结果里，由调用方按空串处理")
        void testMissingIgnored() {
            when(apUserMapper.selectBatchIds(List.of(9L))).thenReturn(List.of());

            Map<String, Object> data =
                    (Map<String, Object>) userFeignController.getBasicInfoBatch(List.of(9L)).getData();
            assertTrue(data.isEmpty());
        }

        @Test
        @DisplayName("昵称/头像为 null → 空串兜底")
        void testNullFields() {
            when(apUserMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(user(3, null, null)));

            Map<String, Object> data =
                    (Map<String, Object>) userFeignController.getBasicInfoBatch(List.of(3L)).getData();
            assertEquals("", ((Map<?, ?>) data.get("3")).get("nickname"));
            assertEquals("", ((Map<?, ?>) data.get("3")).get("avatar"));
        }
    }
}