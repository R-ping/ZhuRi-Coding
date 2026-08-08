package com.heima.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.dto.PasswordUpdateDTO;
import com.heima.model.user.dto.PrivacyMessageDTO;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.pojos.UserOauth;
import com.heima.model.user.pojos.UserProfile;
import com.heima.model.user.vo.BindingsVO;
import com.heima.user.mapper.ApUserMapper;
import com.heima.user.mapper.UserOauthMapper;
import com.heima.user.mapper.UserProfileMapper;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService 账户管理")
class AccountServiceImplTest {

    @Mock
    private ApUserMapper apUserMapper;
    @Mock
    private UserOauthMapper userOauthMapper;
    @Mock
    private UserProfileMapper userProfileMapper;
    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @InjectMocks
    private AccountServiceImpl accountService;

    private ApUser createTestUser() {
        ApUser user = new ApUser();
        user.setId(1001);
        user.setNickname("测试用户");
        user.setPhone("13800138000");
        user.setPassword("$2a$10$encodedPassword");
        return user;
    }

    @BeforeEach
    void setUp() {
        AppThreadLocalUtil.setUser(createTestUser());
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    @Nested
    @DisplayName("获取绑定信息")
    class GetBindings {

        @Test
        @DisplayName("获取成功：返回手机号脱敏和OAuth绑定状态")
        void testGetBindingsSuccess() {
            // Arrange
            List<UserOauth> oauthList = new ArrayList<>();
            UserOauth wechat = new UserOauth();
            wechat.setProvider(1);
            wechat.setNickname("微信用户");
            wechat.setAvatar("wechat-avatar.jpg");
            oauthList.add(wechat);
            UserOauth github = new UserOauth();
            github.setProvider(3);
            github.setNickname("github-user");
            github.setAvatar("github-avatar.jpg");
            oauthList.add(github);

            when(userOauthMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(oauthList);

            // Act
            ResponseResult result = accountService.getBindings();

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            assertTrue(result.getData() instanceof BindingsVO);
            BindingsVO vo = (BindingsVO) result.getData();
            assertEquals("138****8000", vo.getPhone());
            assertTrue(vo.getWechat().getBound());
            assertTrue(vo.getGithub().getBound());
            assertNull(vo.getWeibo().getBound());
        }

        @Test
        @DisplayName("获取失败：未登录")
        void testGetBindingsNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();

            // Act
            ResponseResult result = accountService.getBindings();

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("更新密码")
    class UpdatePassword {

        @Test
        @DisplayName("更新成功：密码正确且新密码有效")
        void testUpdatePasswordSuccess() {
            // Arrange
            PasswordUpdateDTO dto = new PasswordUpdateDTO();
            dto.setOldPassword("oldPass123");
            dto.setNewPassword("newPass456");

            when(apUserMapper.selectById(1001)).thenReturn(createTestUser());
            when(passwordEncoder.matches("oldPass123", "$2a$10$encodedPassword")).thenReturn(true);
            when(passwordEncoder.encode("newPass456")).thenReturn("$2a$10$newEncodedPassword");

            // Act
            ResponseResult result = accountService.updatePassword(dto);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(apUserMapper).updateById(any(ApUser.class));
        }

        @Test
        @DisplayName("更新失败：旧密码为空")
        void testUpdatePasswordOldPasswordEmpty() {
            // Arrange
            PasswordUpdateDTO dto = new PasswordUpdateDTO();
            dto.setOldPassword("");
            dto.setNewPassword("newPass456");

            // Act
            ResponseResult result = accountService.updatePassword(dto);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("旧密码不能为空", result.getMessage());
            verify(apUserMapper, never()).updateById(any(ApUser.class));
        }

        @Test
        @DisplayName("更新失败：新密码少于6位")
        void testUpdatePasswordNewPasswordTooShort() {
            // Arrange
            PasswordUpdateDTO dto = new PasswordUpdateDTO();
            dto.setOldPassword("oldPass123");
            dto.setNewPassword("12345");

            // Act
            ResponseResult result = accountService.updatePassword(dto);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("新密码至少6位", result.getMessage());
        }

        @Test
        @DisplayName("更新失败：旧密码错误")
        void testUpdatePasswordWrongOldPassword() {
            // Arrange
            PasswordUpdateDTO dto = new PasswordUpdateDTO();
            dto.setOldPassword("wrongOldPass");
            dto.setNewPassword("newPass456");

            when(apUserMapper.selectById(1001)).thenReturn(createTestUser());
            when(passwordEncoder.matches("wrongOldPass", "$2a$10$encodedPassword")).thenReturn(false);

            // Act
            ResponseResult result = accountService.updatePassword(dto);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("旧密码错误", result.getMessage());
        }

        @Test
        @DisplayName("更新失败：未登录")
        void testUpdatePasswordNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();
            PasswordUpdateDTO dto = new PasswordUpdateDTO();
            dto.setOldPassword("oldPass123");
            dto.setNewPassword("newPass456");

            // Act
            ResponseResult result = accountService.updatePassword(dto);

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("删除账号")
    class DeleteAccount {

        @Test
    @DisplayName("删除成功：软删除，标记status=0")
    void testDeleteAccountSuccess() {
        // 使用 mockConstruction 避免 LambdaUpdateWrapper 的 lambda 缓存初始化失败
        try (MockedConstruction<LambdaUpdateWrapper> mocked = mockConstruction(LambdaUpdateWrapper.class,
                (mock, context) -> {
                    when(mock.eq(any(), any())).thenReturn(mock);
                    when(mock.set(any(), any())).thenReturn(mock);
                })) {
            // Act
            ResponseResult result = accountService.deleteAccount();

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(apUserMapper).update(eq(null), any());
        }
    }

        @Test
        @DisplayName("删除失败：未登录")
        void testDeleteAccountNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();

            // Act
            ResponseResult result = accountService.deleteAccount();

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("更新私信权限")
    class UpdatePrivacyMessage {

        @Test
        @DisplayName("更新成功：已有profile，更新")
        void testUpdatePrivacyMessageUpdateExisting() {
            // Arrange
            PrivacyMessageDTO dto = new PrivacyMessageDTO();
            dto.setScope(1);

            UserProfile existingProfile = new UserProfile();
            existingProfile.setUserId(1001L);
            existingProfile.setPrivacyMessage(0);
            when(userProfileMapper.selectById(1001L)).thenReturn(existingProfile);

            // Act
            ResponseResult result = accountService.updatePrivacyMessage(dto);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(userProfileMapper).updateById(any(UserProfile.class));
            verify(userProfileMapper, never()).insert(any(UserProfile.class));
        }

        @Test
        @DisplayName("更新成功：无profile，新建")
        void testUpdatePrivacyMessageCreateNew() {
            // Arrange
            PrivacyMessageDTO dto = new PrivacyMessageDTO();
            dto.setScope(2);

            when(userProfileMapper.selectById(1001L))
                    .thenReturn(null)   // 第一次查无
                    .thenReturn(null);  // 第二次查无，走insert

            // Act
            ResponseResult result = accountService.updatePrivacyMessage(dto);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(userProfileMapper).insert(any(UserProfile.class));
            verify(userProfileMapper, never()).updateById(any(UserProfile.class));
        }

        @Test
        @DisplayName("更新失败：scope无效")
        void testUpdatePrivacyMessageInvalidScope() {
            // Arrange
            PrivacyMessageDTO dto = new PrivacyMessageDTO();
            dto.setScope(5);

            // Act
            ResponseResult result = accountService.updatePrivacyMessage(dto);

            // Assert
            assertEquals(503, result.getCode());
        }

        @Test
        @DisplayName("更新失败：scope为null")
        void testUpdatePrivacyMessageNullScope() {
            // Arrange
            PrivacyMessageDTO dto = new PrivacyMessageDTO();
            dto.setScope(null);

            // Act
            ResponseResult result = accountService.updatePrivacyMessage(dto);

            // Assert
            assertEquals(503, result.getCode());
        }

        @Test
        @DisplayName("更新失败：未登录")
        void testUpdatePrivacyMessageNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();
            PrivacyMessageDTO dto = new PrivacyMessageDTO();
            dto.setScope(1);

            // Act
            ResponseResult result = accountService.updatePrivacyMessage(dto);

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }
}