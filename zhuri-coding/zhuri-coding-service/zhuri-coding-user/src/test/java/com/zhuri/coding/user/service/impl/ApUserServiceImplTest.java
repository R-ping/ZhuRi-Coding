package com.zhuri.coding.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.dtos.LoginDto;
import com.zhuri.coding.model.user.dtos.LoginResultVo;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.user.mapper.ApUserMapper;
import com.zhuri.coding.user.service.LoginCodeService;
import com.zhuri.coding.user.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.lang.reflect.Field;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 注意：MyBatis-Plus 3.5.7 中 ServiceImpl.getOne() 调用的是 BaseMapper.selectOne(Wrapper, boolean) 双参数版本

@ExtendWith(MockitoExtension.class)
@DisplayName("ApUserService 用户登录认证")
class ApUserServiceImplTest {

    @Mock
    private TokenService tokenService;
    @Mock
    private BCryptPasswordEncoder passwordEncoder;
    @Mock
    private LoginCodeService loginCodeService;
    @Mock
    private ApUserMapper apUserMapper;

    @InjectMocks
    private ApUserServiceImpl apUserService;

    @BeforeEach
    void setUp() {
        // 通过反射设置 baseMapper，避免 MyBatisPlus baseMapper null 检查
        ReflectionTestUtils.setField(apUserService, "baseMapper", apUserMapper);
    }

    private static final String TEST_PHONE = "13800138000";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "password123";
    private static final String TEST_ENCODED_PASSWORD = "$2a$10$encodedPasswordHash";

    private ApUser createTestUser() {
        ApUser user = new ApUser();
        user.setId(1001);
        user.setNickname("测试用户");
        user.setPhone(TEST_PHONE);
        user.setPassword(TEST_ENCODED_PASSWORD);
        user.setImage("avatar_head_1");
        return user;
    }

    private LoginResultVo createLoginResult() {
        return LoginResultVo.builder()
                .status("login")
                .accessToken("test-access-token")
                .refreshToken("test-refresh-token")
                .userId(1001)
                .nickName("测试用户")
                .phone(TEST_PHONE)
                .avatar("avatar_head_1")
                .build();
    }

    @Nested
    @DisplayName("手机号+密码登录")
    class PhonePasswordLogin {

        @Test
        @DisplayName("登录成功：返回双Token")
        void testPhonePasswordLoginSuccess() {
            // Arrange
            LoginDto dto = new LoginDto();
            dto.setPhoneOrEmail(TEST_PHONE);
            dto.setPassword(TEST_PASSWORD);

            ApUser dbUser = createTestUser();
            when(apUserMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(dbUser);
            when(passwordEncoder.matches(TEST_PASSWORD, TEST_ENCODED_PASSWORD)).thenReturn(true);
            when(tokenService.generateDualToken(1001, "测试用户", TEST_PHONE, "avatar_head_1"))
                    .thenReturn(createLoginResult());

            // Act
            ResponseResult result = apUserService.allLoginAuth(dto, "phonePass");

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            assertNotNull(result.getData());
            LoginResultVo loginResult = (LoginResultVo) result.getData();
            assertEquals("login", loginResult.getStatus());
        }

        @Test
        @DisplayName("登录失败：用户不存在")
        void testPhonePasswordLoginUserNotFound() {
            // Arrange
            LoginDto dto = new LoginDto();
            dto.setPhoneOrEmail(TEST_PHONE);
            dto.setPassword(TEST_PASSWORD);

            when(apUserMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(null);

            // Act
            ResponseResult result = apUserService.allLoginAuth(dto, "phonePass");

            // Assert
            assertNotNull(result);
            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
            assertEquals("用户信息不存在", result.getMessage());
        }

        @Test
        @DisplayName("登录失败：密码错误")
        void testPhonePasswordLoginWrongPassword() {
            // Arrange
            LoginDto dto = new LoginDto();
            dto.setPhoneOrEmail(TEST_PHONE);
            dto.setPassword("wrong-password");

            ApUser dbUser = createTestUser();
            when(apUserMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(dbUser);
            when(passwordEncoder.matches("wrong-password", TEST_ENCODED_PASSWORD)).thenReturn(false);

            // Act
            ResponseResult result = apUserService.allLoginAuth(dto, "phonePass");

            // Assert
            assertNotNull(result);
            assertEquals(AppHttpCodeEnum.LOGIN_PASSWORD_ERROR.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("邮箱+密码登录")
    class EmailPasswordLogin {

        @Test
        @DisplayName("登录成功：返回双Token")
        void testEmailPasswordLoginSuccess() {
            // Arrange
            LoginDto dto = new LoginDto();
            dto.setPhoneOrEmail(TEST_EMAIL);
            dto.setPassword(TEST_PASSWORD);

            ApUser dbUser = createTestUser();
            dbUser.setEmail(TEST_EMAIL);
            when(apUserMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(dbUser);
            when(passwordEncoder.matches(TEST_PASSWORD, TEST_ENCODED_PASSWORD)).thenReturn(true);
            when(tokenService.generateDualToken(1001, "测试用户", TEST_PHONE, "avatar_head_1"))
                    .thenReturn(createLoginResult());

            // Act
            ResponseResult result = apUserService.allLoginAuth(dto, "emailPass");

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            assertNotNull(result.getData());
        }

        @Test
        @DisplayName("登录失败：用户不存在")
        void testEmailPasswordLoginUserNotFound() {
            // Arrange
            LoginDto dto = new LoginDto();
            dto.setPhoneOrEmail(TEST_EMAIL);
            dto.setPassword(TEST_PASSWORD);

            when(apUserMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(null);

            // Act
            ResponseResult result = apUserService.allLoginAuth(dto, "emailPass");

            // Assert
            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("手机号验证码登录/注册")
    class PhoneCodeLogin {

        @Test
        @DisplayName("登录成功：已有用户，返回双Token")
        void testPhoneCodeLoginExistingUser() {
            // Arrange
            LoginDto dto = new LoginDto();
            dto.setPhoneOrEmail(TEST_PHONE);
            dto.setCode("123456");
            dto.setPlatform("wechat");

            when(loginCodeService.verifyAndConsume("wechat", TEST_PHONE, "123456")).thenReturn(true);
            ApUser dbUser = createTestUser();
            when(apUserMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(dbUser);
            when(tokenService.generateDualToken(1001, "测试用户", TEST_PHONE, "avatar_head_1"))
                    .thenReturn(createLoginResult());

            // Act
            ResponseResult result = apUserService.allLoginAuth(dto, "phoneCode");

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
        }

        @Test
        @DisplayName("注册成功：新用户自动注册并登录")
        void testPhoneCodeLoginNewUser() {
            // Arrange
            LoginDto dto = new LoginDto();
            dto.setPhoneOrEmail(TEST_PHONE);
            dto.setCode("123456");
            dto.setPlatform("wechat");

            when(loginCodeService.verifyAndConsume("wechat", TEST_PHONE, "123456")).thenReturn(true);
            // 第一次查询返回null（新用户）
            when(apUserMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(null);
            when(tokenService.generateDualToken(any(), anyString(), eq(TEST_PHONE), anyString()))
                    .thenReturn(createLoginResult());

            // Act
            ResponseResult result = apUserService.allLoginAuth(dto, "phoneCode");

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            // 验证新用户已保存
            verify(apUserMapper).insert(any(ApUser.class));
        }

        @Test
        @DisplayName("登录失败：验证码错误")
        void testPhoneCodeLoginWrongCode() {
            // Arrange
            LoginDto dto = new LoginDto();
            dto.setPhoneOrEmail(TEST_PHONE);
            dto.setCode("wrong-code");
            dto.setPlatform("wechat");

            when(loginCodeService.verifyAndConsume("wechat", TEST_PHONE, "wrong-code")).thenReturn(false);

            // Act
            ResponseResult result = apUserService.allLoginAuth(dto, "phoneCode");

            // Assert
            assertNotNull(result);
            assertEquals(AppHttpCodeEnum.LOGIN_CODE_ERROR.getCode(), result.getCode());
        }

        @Test
        @DisplayName("登录失败：验证码已过期")
        void testPhoneCodeLoginExpiredCode() {
            // Arrange
            LoginDto dto = new LoginDto();
            dto.setPhoneOrEmail(TEST_PHONE);
            dto.setCode("123456");
            dto.setPlatform("wechat");

            when(loginCodeService.verifyAndConsume("wechat", TEST_PHONE, "123456")).thenReturn(false);

            // Act
            ResponseResult result = apUserService.allLoginAuth(dto, "phoneCode");

            // Assert
            assertEquals(AppHttpCodeEnum.LOGIN_CODE_ERROR.getCode(), result.getCode());
        }
    }
}